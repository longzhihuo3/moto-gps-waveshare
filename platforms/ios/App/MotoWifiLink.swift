import Foundation
import Network

/// Wi-Fi 热点直传（方案 1）：
/// iPhone 开个人热点后，手表以客户端身份加入（网段恒为 172.20.10.0/28，
/// iPhone 网关 .1，手表 .2-.14）。本类在确认本机处于该网段时扫描手表的
/// TCP 服务（端口 23333），连上后推送两类 BLE 装不下的大数据：
///   - route    ：完整路线折线（一次一帧，BLE 上要分块慢慢爬）
///   - snapshot ：与 BLE 同语义的 JSON 快照（可跑比 BLE 5Hz 更高的频率）
/// BLE 通道完全不受影响：命令、保底心跳照旧走 BLE；Wi-Fi 只是加速车道。
final class MotoWifiLink {
    static let shared = MotoWifiLink()
    private init() {}

    private let port: UInt16 = 23333
    private let queue = DispatchQueue(label: "moto.wifilink")
    private var connection: NWConnection?
    private var scanConnections: [NWConnection] = []
    private var lastScanStartedAt: TimeInterval = 0
    private var lastRouteKey: String?
    private var receiveBuffer = Data()

    private(set) var isActive = false

    /// 链路建立/断开回调（主线程）。UI 可据此显示「Wi-Fi 直传」指示。
    var onLinkChanged: ((Bool) -> Void)?

    // MARK: - 对外入口

    /// 每次 BLE 快照发布时调用：顺手驱动发现与推送（全部内部去重/限频）。
    func publish(_ state: MotoNavCoreSnapshot) {
        queue.async { [weak self] in
            guard let self else { return }
            self.ensureConnectedLocked()
            self.publishRouteLocked(state)
            self.sendLocked(Self.snapshotJSON(state))
        }
    }

    /// 规划脉冲（与 BLE 合成快照同语义）。
    func publishSynthetic(stateName: String, routeRequestInFlight: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            self.ensureConnectedLocked()
            let stateInt: Int
            switch stateName {
            case "acquiring": stateInt = 1
            case "planning": stateInt = 2
            case "navigating": stateInt = 3
            case "rerouting": stateInt = 4
            case "arrived": stateInt = 5
            default: stateInt = 0
            }
            var flags = 0x03 // hasDestination | hasFix（合成脉冲时定位可用）
            if routeRequestInFlight { flags |= 0x20 }
            let json: [String: Any] = [
                "type": "snapshot",
                "state": stateInt,
                "network": 2,
                "displayPage": 0,
                "maneuver": 0,
                "traffic": 0,
                "flags": flags,
                "roadName": "",
                "instruction": "",
            ]
            self.sendLocked(json)
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.teardownLocked()
        }
    }

    // MARK: - 发现与连接

    /// 只在确认本机处于 iPhone 热点网段（172.20.10.x）时才扫描，其余网络直接跳过。
    private func ensureConnectedLocked() {
        guard connection == nil else { return }
        let now = Date().timeIntervalSince1970
        guard now - lastScanStartedAt > 5 else { return }
        lastScanStartedAt = now
        guard Self.localIPv4Addresses().contains(where: { $0.hasPrefix("172.20.10.") }) else {
            return
        }
        // 热点网段 /28：手表几乎总在 .2（DHCP 第一位），但仍并行试 .2-.14，
        // 第一个 TCP 握手成功的获胜，其余全部取消。
        var candidates: [NWConnection] = []
        for host in 2...14 {
            let endpoint = NWEndpoint.hostPort(
                host: NWEndpoint.Host("172.20.10.\(host)"),
                port: NWEndpoint.Port(rawValue: port)!
            )
            let connection = NWConnection(to: endpoint, using: .tcp)
            connection.stateUpdateHandler = { [weak self] state in
                self?.handleScanState(state, connection: connection)
            }
            connection.start(queue: queue)
            candidates.append(connection)
        }
        scanConnections = candidates
    }

    private func handleScanState(_ state: NWConnection.State, connection: NWConnection) {
        switch state {
        case .ready:
            guard self.connection == nil else {
                connection.cancel()
                return
            }
            scanConnections.forEach { if $0 !== connection { $0.cancel() } }
            scanConnections = []
            self.connection = connection
            receiveBuffer.removeAll()
            lastRouteKey = nil
            isActive = true
            DispatchQueue.main.async { [weak self] in self?.onLinkChanged?(true) }
            sendLocked(["type": "hello", "ver": 1, "name": Self.deviceDisplayName()])
            receiveLoop()
        case .failed, .cancelled:
            scanConnections.removeAll { $0 === connection }
            if self.connection === connection { teardownLocked() }
        default:
            break
        }
    }

    private func teardownLocked() {
        connection?.cancel()
        connection = nil
        scanConnections.forEach { $0.cancel() }
        scanConnections = []
        receiveBuffer.removeAll()
        if isActive {
            isActive = false
            DispatchQueue.main.async { [weak self] in self?.onLinkChanged?(false) }
        }
    }

    /// 常驻接收：服务器会回 welcome/pong，读出来只为丢弃（保持 TCP 流控健康）。
    private func receiveLoop() {
        guard let connection else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            if isComplete || error != nil {
                self.queue.async { self.teardownLocked() }
                return
            }
            if let data { self.receiveBuffer.append(data) } // 丢弃内容
            if self.connection === connection { self.receiveLoop() }
        }
    }

    // MARK: - 负载构造

    /// 完整路线：routeView 存在且签名变化时才发（避免 5Hz 重复推）。
    private func publishRouteLocked(_ state: MotoNavCoreSnapshot) {
        guard state.hasRouteView, !state.routeID.isEmpty, !state.routeViewPoints.isEmpty
        else { return }
        var points: [Int] = []
        points.reserveCapacity(state.routeViewPoints.count * 2)
        for point in state.routeViewPoints {
            points.append(Int((point.latitudeDeg * 1_000_000).rounded()))
            points.append(Int((point.longitudeDeg * 1_000_000).rounded()))
        }
        let key = "\(state.routeID)#\(state.routeGeneration)#\(points.count)"
        guard key != lastRouteKey else { return }
        lastRouteKey = key
        // token 仅作展示关联，与 BLE routeToken 无强一致需求
        var token: UInt64 = 0
        for byte in state.routeID.utf8 {
            token = (token &* 31 &+ UInt64(byte)) % 1_000_000_007
        }
        let json: [String: Any] = [
            "type": "route",
            "token": token,
            "points": points,
        ]
        sendLocked(json)
    }

    /// MotoNavCoreSnapshot → 与手表 BLE 快照同语义的 JSON（枚举值取自
    /// shared/ble_protocol/ble_protocol.hpp，与手表端解析一一对应）。
    static func snapshotJSON(_ state: MotoNavCoreSnapshot) -> [String: Any] {
        let stateInt: Int
        switch state.stateName {
        case "acquiring": stateInt = 1
        case "planning": stateInt = 2
        case "navigating": stateInt = 3
        case "rerouting": stateInt = 4
        case "arrived": stateInt = 5
        default: stateInt = 0
        }
        let networkInt: Int
        switch state.networkName {
        case "connecting": networkInt = 1
        case "online": networkInt = 2
        default: networkInt = 0
        }
        let pageInt: Int
        switch state.displayPageName {
        case "speed": pageInt = 1
        case "compass": pageInt = 2
        case "music": pageInt = 3
        default: pageInt = 0
        }
        let maneuverInt: Int
        switch state.maneuverTypeName {
        case "continue": maneuverInt = 1
        case "slight_left": maneuverInt = 2
        case "left": maneuverInt = 3
        case "sharp_left": maneuverInt = 4
        case "u_turn_left": maneuverInt = 5
        case "slight_right": maneuverInt = 6
        case "right": maneuverInt = 7
        case "sharp_right": maneuverInt = 8
        case "u_turn_right": maneuverInt = 9
        case "roundabout": maneuverInt = 10
        case "exit": maneuverInt = 11
        case "arrive": maneuverInt = 12
        default: maneuverInt = 0
        }
        var flags = 0
        if state.hasDestination { flags |= 0x01 }
        if state.hasUsableFix { flags |= 0x02 }
        if state.gnssStale { flags |= 0x04 }
        if state.offRoute { flags |= 0x08 }
        if state.routeRequestInFlight { flags |= 0x20 }
        if state.hasRouteView { flags |= 0x80 }
        return [
            "type": "snapshot",
            "state": stateInt,
            "network": networkInt,
            "displayPage": pageInt,
            "maneuver": maneuverInt,
            "traffic": 0,
            "flags": flags,
            "routeGeneration": Int64(state.routeGeneration),
            "maneuverId": Int64(state.maneuverID),
            "distanceToManeuverM": Int(max(0, state.distanceToManeuverM.rounded())),
            "remainingDistanceM": Int(max(0, state.remainingDistanceM.rounded())),
            "remainingDurationS": Int(max(0, state.remainingDurationS)),
            "routeProgressM": Int(max(0, state.routeProgressM.rounded())),
            "totalDistanceM": Int(max(0, state.totalDistanceM.rounded())),
            "speedDeciKph": Int(max(0, (state.speedMPS * 36).rounded())),
            "speedLimitKph": Int(state.speedLimitKPH),
            "headingCdeg": normalizedHeadingCentidegrees(state.headingDeg),
            "accuracyDm": Int(max(0, (state.horizontalAccuracyM * 10).rounded())),
            "crossTrackDm": Int((state.crossTrackDistanceM * 10).rounded()),
            "roundaboutExit": Int(state.roundaboutExit),
            "roadName": state.roadName,
            "instruction": state.instructionText,
        ]
    }

    private static func normalizedHeadingCentidegrees(_ heading: Double) -> Int {
        guard heading.isFinite else { return 0 }
        var normalized = heading.truncatingRemainder(dividingBy: 360)
        if normalized < 0 { normalized += 360 }
        return Int((normalized * 100).rounded()) % 36_000
    }

    // MARK: - 帧发送

    private func sendLocked(_ json: [String: Any]) {
        guard let connection, isActive || connection.state == .ready else { return }
        guard JSONSerialization.isValidJSONObject(json),
              let payload = try? JSONSerialization.data(withJSONObject: json)
        else { return }
        var frame = Data(count: 4)
        frame[0] = UInt8((payload.count >> 24) & 0xFF)
        frame[1] = UInt8((payload.count >> 16) & 0xFF)
        frame[2] = UInt8((payload.count >> 8) & 0xFF)
        frame[3] = UInt8(payload.count & 0xFF)
        frame.append(payload)
        connection.send(content: frame, completion: .contentProcessed { _ in })
    }

    // MARK: - 工具

    private static func localIPv4Addresses() -> [String] {
        var addresses: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return addresses }
        defer { freeifaddrs(ifaddr) }
        var pointer = ifaddr
        while let current = pointer {
            defer { pointer = current.pointee.ifa_next }
            guard let sockaddr = current.pointee.ifa_addr,
                  sockaddr.pointee.sa_family == UInt8(AF_INET)
            else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                sockaddr, socklen_t(sockaddr.pointee.sa_len),
                &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST
            )
            if result == 0, let ip = String(validatingUTF8: host) {
                addresses.append(ip)
            }
        }
        return addresses
    }

    private static func deviceDisplayName() -> String {
        #if canImport(UIKit)
        return UIDevice.current.name
        #else
        return "iPhone"
        #endif
    }
}

#if canImport(UIKit)
import UIKit
#endif
