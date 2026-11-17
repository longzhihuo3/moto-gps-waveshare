import Foundation
import AMapNaviKit
import AMapSearchKit
import MotoNavigationCore

enum AmapNaviKitError: LocalizedError {
    case sdkUnavailable
    case noRoute
    case rejected(String)
    case timedOut

    var errorDescription: String? {
        switch self {
        case .sdkUnavailable:
            "高德导航 SDK 未初始化"
        case .noRoute:
            "高德没有返回可用路线"
        case let .rejected(message):
            message
        case .timedOut:
            "路线规划超时，请检查网络后重试"
        }
    }
}

/// Installs the AMap API key once. Without a configured key the app keeps
/// using the self-hosted gateway providers, so an absent key never breaks
/// an existing installation.
enum AmapNaviKitBootstrap {
    private static var installed = false

    static func install(apiKey: String) {
        let trimmed = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !installed else { return }

        // Privacy compliance must be acknowledged before ANY AMap SDK API
        // works; without it AMapServices.shared() returns nil and every
        // request is rejected by the SDK.
        AMapSearchAPI.updatePrivacyShow(.didShow, privacyInfo: .didContain)
        AMapSearchAPI.updatePrivacyAgree(.didAgree)
        AMapNaviManagerConfig.shared().updatePrivacyShow(.didShow, privacyInfo: .didContain)
        AMapNaviManagerConfig.shared().updatePrivacyAgree(.didAgree)

        guard let services = AMapServices.shared() else { return }
        services.apiKey = trimmed
        services.enableHTTPS = true
        installed = true
    }

    static var isInstalled: Bool { installed }
}

/// Plans walking / cycling routes directly through AMapNaviKit so navigation
/// works without the self-hosted gateway. The shared C++ NavCore still owns
/// maneuver progression; this provider only supplies the RoutePlan.
///
/// AMapNaviKit v11 route managers are process-wide singletons and their
/// callbacks carry no request identity, so plans are serialized through one
/// FIFO queue with a timeout.
final class AmapNaviKitRouteProvider: NSObject, NavigationRouteProviding, NavigationRouteOptionsProviding, @unchecked Sendable {
    enum TravelMode: String {
        case riding
        case walking
    }

    private let travelMode: TravelMode

    init(travelMode: TravelMode) {
        self.travelMode = travelMode
        super.init()
    }

    func route(for request: RouteRequest) async throws -> RouteEnvelope {
        let plans = try await AmapNaviKitPlanningSession.shared.calculateRoute(
            mode: travelMode,
            originWGS84: request.origin,
            destinationWGS84: request.destination
        )
        guard let first = plans.first else {
            throw AmapNaviKitError.noRoute
        }
        return RouteEnvelope(requestID: request.requestID, route: first)
    }

    func routeOptions(for request: RouteRequest) async throws -> [RoutePlan] {
        let plans = try await AmapNaviKitPlanningSession.shared.calculateRoute(
            mode: travelMode,
            originWGS84: request.origin,
            destinationWGS84: request.destination
        )
        guard !plans.isEmpty else {
            throw AmapNaviKitError.noRoute
        }
        return plans
    }
}

/// Serializes AMapNaviKit route planning. One pending plan at a time with a
/// timeout, because the delegate callback cannot be matched to a request.
private final class AmapNaviKitPlanningSession: NSObject, AMapNaviRideManagerDelegate, AMapNaviWalkManagerDelegate, @unchecked Sendable {
    static let shared = AmapNaviKitPlanningSession()

    private struct Pending {
        let mode: AmapNaviKitRouteProvider.TravelMode
        let continuation: CheckedContinuation<[RoutePlan], Error>
    }

    private let lock = NSLock()
    private var pendingQueue: [Pending] = []
    private var sdkLogTail: [String] = []

    private override init() {
        super.init()
        Self.installSdkLogDelegate(self)
    }

    /// AMapNaviLogger.h is not part of the SDK umbrella header, so Swift cannot
    /// see the type. The class still exists in the binary, so reach it through
    /// the Objective-C runtime.
    private static func installSdkLogDelegate(_ target: NSObject) {
        guard let cls = NSClassFromString("AMapNaviLogger") as? NSObject.Type,
              let instance = cls.perform(NSSelectorFromString("shareInstance"))?
                  .takeUnretainedValue() as? NSObject
        else { return }
        instance.perform(NSSelectorFromString("setLogDelegate:"), with: target)
    }

    fileprivate func appendSdkLog(_ line: String) {
        let trimmed = line.count > 240 ? String(line.suffix(240)) : line
        lock.lock()
        sdkLogTail.append(trimmed)
        if sdkLogTail.count > 24 { sdkLogTail.removeFirst(sdkLogTail.count - 24) }
        lock.unlock()
    }

    /// Matches -[AMapNaviLoggerProtocol onNaviLog:logContent:]; declared @objc
    /// because the formal protocol type is invisible to Swift.
    @objc func onNaviLog(_ logLevel: Int, logContent: String) {
        appendSdkLog("[\(logLevel)] \(logContent)")
    }

    private func sdkLogSummary() -> String {
        lock.lock()
        defer { lock.unlock() }
        guard !sdkLogTail.isEmpty else { return "（SDK无日志）" }
        return "｜SDK日志：" + sdkLogTail.suffix(6).joined(separator: " ␤ ")
    }

    func calculateRoute(
        mode: AmapNaviKitRouteProvider.TravelMode,
        originWGS84: WGS84Point,
        destinationWGS84: WGS84Point
    ) async throws -> [RoutePlan] {
        guard AmapNaviKitBootstrap.isInstalled, AMapServices.shared() != nil else {
            throw AmapNaviKitError.sdkUnavailable
        }
        return try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            pendingQueue.append(Pending(mode: mode, continuation: continuation))
            lock.unlock()
            appendSdkLog("[app] begin mode=\(mode.rawValue) \(originWGS84.longitudeDeg),\(originWGS84.latitudeDeg)->\(destinationWGS84.longitudeDeg),\(destinationWGS84.latitudeDeg)")

            // The SDK callback normally arrives within a few seconds; never
            // leave a rider staring at "正在规划路线" forever.
            DispatchQueue.global().asyncAfter(deadline: .now() + 45) { [weak self] in
                self?.failOldestPendingWithDiagnostics(AmapNaviKitError.timedOut)
            }

            let startGCJ = GCJ02CoordinateMath.wgs84ToGCJ02(
                longitudeDeg: originWGS84.longitudeDeg,
                latitudeDeg: originWGS84.latitudeDeg
            )
            let endGCJ = GCJ02CoordinateMath.wgs84ToGCJ02(
                longitudeDeg: destinationWGS84.longitudeDeg,
                latitudeDeg: destinationWGS84.latitudeDeg
            )
            guard let start = AMapNaviPoint.location(
                withLatitude: CGFloat(startGCJ.latitudeDeg),
                longitude: CGFloat(startGCJ.longitudeDeg)
            ), let end = AMapNaviPoint.location(
                withLatitude: CGFloat(endGCJ.latitudeDeg),
                longitude: CGFloat(endGCJ.longitudeDeg)
            ) else {
                failOldestPending(error: AmapNaviKitError.rejected("坐标转换失败"))
                return
            }

            let accepted: Bool
            if mode == .riding {
                let manager = AMapNaviRideManager.sharedInstance()
                manager.delegate = self
                accepted = manager.calculateRideRoute(withStart: start, end: end)
            } else {
                let manager = AMapNaviWalkManager.sharedInstance()
                manager.delegate = self
                accepted = manager.calculateWalkRoute(withStart: [start], end: [end])
            }
            if !accepted {
                failOldestPendingWithDiagnostics(AmapNaviKitError.rejected("路线规划未能发起，请稍后重试"))
            }
        }
    }

    func rideManager(
        _ rideManager: AMapNaviRideManager,
        onCalculateRouteSuccess calculateRouteResult: AMapNaviCalculateRouteResult
    ) {
        finishWithRoutes(from: rideManager)
    }

    func rideManager(_ rideManager: AMapNaviRideManager, onCalculateRouteFailure error: Error) {
        finishWithFailure(error)
    }

    func walkManager(
        _ walkManager: AMapNaviWalkManager,
        onCalculateRouteSuccess calculateRouteResult: AMapNaviCalculateRouteResult
    ) {
        finishWithRoutes(from: walkManager)
    }

    func walkManager(_ walkManager: AMapNaviWalkManager, onCalculateRouteFailure error: Error) {
        finishWithFailure(error)
    }

    private func finishWithRoutes(from manager: AMapNaviRideManager) {
        finishWithRoutes(routeIDs: manager.naviRouteIDs(), routesByID: manager.naviRoutes())
    }

    private func finishWithRoutes(from manager: AMapNaviWalkManager) {
        finishWithRoutes(routeIDs: manager.naviRouteIDs(), routesByID: manager.naviRoutes())
    }

    private func finishWithRoutes(
        routeIDs: [NSNumber],
        routesByID: [NSNumber: AMapNaviRoute]
    ) {
        lock.lock()
        let pending = pendingQueue.isEmpty ? nil : pendingQueue.removeFirst()
        lock.unlock()
        guard let pending else { return }

        let orderedRoutes = routeIDs.compactMap { routesByID[$0] }
        let plans = orderedRoutes.compactMap { Self.convert($0, mode: pending.mode) }
        guard !plans.isEmpty else {
            pending.continuation.resume(throwing: AmapNaviKitError.noRoute)
            return
        }
        pending.continuation.resume(returning: Array(plans.prefix(3)))
    }

    private func finishWithFailure(_ error: Error) {
        lock.lock()
        let pending = pendingQueue.isEmpty ? nil : pendingQueue.removeFirst()
        lock.unlock()
        let nsError = error as NSError
        let message = "\(error.localizedDescription)（code \(nsError.code)，\(nsError.domain)）"
        pending?.continuation.resume(throwing: AmapNaviKitError.rejected(message))
    }

    private func failOldestPending(error: AmapNaviKitError) {
        lock.lock()
        guard !pendingQueue.isEmpty else {
            lock.unlock()
            return
        }
        let pending = pendingQueue.removeFirst()
        lock.unlock()
        pending.continuation.resume(throwing: error)
    }

    private func failOldestPendingWithDiagnostics(_ base: AmapNaviKitError) {
        lock.lock()
        guard !pendingQueue.isEmpty else {
            lock.unlock()
            return
        }
        let pending = pendingQueue.removeFirst()
        lock.unlock()
        pending.continuation.resume(throwing: AmapNaviKitError.rejected(base.localizedDescription + sdkLogSummary()))
    }

    private static func convert(_ route: AMapNaviRoute, mode: AmapNaviKitRouteProvider.TravelMode) -> RoutePlan? {
        let polyline = route.routeCoordinates.map {
            GCJ02Point(longitudeDeg: Double($0.longitude), latitudeDeg: Double($0.latitude))
        }
        guard polyline.count >= 2, route.routeLength > 0 else { return nil }

        var maneuvers: [RouteManeuver] = []
        var offsetM: Double = 0
        var maneuverID: UInt32 = 0
        for segment in route.routeSegments {
            let icon = segment.iconType
            if isRealManeuver(icon), let first = segment.coordinates.first {
                maneuverID += 1
                maneuvers.append(
                    RouteManeuver(
                        id: maneuverID,
                        type: maneuverType(from: icon),
                        routeOffsetM: Double(offsetM),
                        roadName: roadName(of: segment),
                        instruction: Self.instructionText(icon: icon)
                    )
                )
            }
            offsetM += Double(segment.length)
        }
        // NavCore needs a terminal maneuver so the watch can render arrival.
        maneuverID += 1
        maneuvers.append(
            RouteManeuver(
                id: maneuverID,
                type: .arrive,
                routeOffsetM: Double(route.routeLength),
                roadName: "",
                instruction: "到达目的地"
            )
        )

        let generatedAtMs = UInt64(Date().timeIntervalSince1970 * 1_000)
        return RoutePlan(
            routeID: "navikit-\(route.routeLength)-\(generatedAtMs)-\(maneuvers.count)",
            provider: "amap-navikit",
            generatedAtMs: generatedAtMs,
            totalDistanceM: Double(route.routeLength),
            totalDurationS: max(1, route.routeTime),
            polyline: polyline,
            maneuvers: maneuvers,
            traffic: [],
            speedLimitKph: nil
        )
    }

    private static func roadName(of segment: AMapNaviSegment) -> String {
        segment.links.compactMap { $0.roadName }.first { !$0.isEmpty } ?? ""
    }

    private static func isRealManeuver(_ icon: AMapNaviIconType) -> Bool {
        switch icon {
        case .left, .right, .leftFront, .rightFront, .leftBack, .rightBack,
             .leftAndAround, .uTurnRight, .enterRoundabout, .outRoundabout,
             .entryRingLeft, .entryRingRight, .entryRingContinue, .entryRingUTurn,
             .entryLeftRingLeft, .entryLeftRingRight, .entryLeftRingContinue,
             .entryLeftRingUTurn, .entryLeftRing, .leaveLeftRing,
             .arrivedDestination, .mergeLeft, .mergeRight,
             .turnLeftDiagonal, .turnRightDiagonal, .turnLeftAcross, .turnRightAcross,
             .acrossTurnLeft, .acrossTurnRight:
            return true
        default:
            return false
        }
    }

    private static func maneuverType(from icon: AMapNaviIconType) -> ManeuverType {
        switch icon {
        case .left: .left
        case .right: .right
        case .leftFront: .slightLeft
        case .rightFront: .slightRight
        case .leftBack: .sharpLeft
        case .rightBack: .sharpRight
        case .leftAndAround, .uTurnRight: .uTurnLeft
        case .enterRoundabout, .entryRingLeft, .entryRingRight, .entryRingContinue,
             .entryRingUTurn, .entryLeftRingLeft, .entryLeftRingRight,
             .entryLeftRingContinue, .entryLeftRingUTurn, .entryLeftRing:
            .roundabout
        case .outRoundabout, .leaveLeftRing: .exit
        case .arrivedDestination: .arrive
        default: .continue
        }
    }

    private static func instructionText(icon: AMapNaviIconType) -> String {
        switch icon {
        case .left: "左转"
        case .right: "右转"
        case .leftFront: "向左前方行驶"
        case .rightFront: "向右前方行驶"
        case .leftBack: "向左后方急转"
        case .rightBack: "向右后方急转"
        case .leftAndAround, .uTurnRight: "掉头"
        case .enterRoundabout, .entryRingLeft, .entryRingRight, .entryRingContinue,
             .entryRingUTurn, .entryLeftRingLeft, .entryLeftRingRight,
             .entryLeftRingContinue, .entryLeftRingUTurn, .entryLeftRing:
            "进入环岛"
        case .outRoundabout, .leaveLeftRing: "驶出环岛"
        case .arrivedDestination: "到达目的地"
        case .mergeLeft: "靠左行驶"
        case .mergeRight: "靠右行驶"
        default: "直行"
        }
    }
}
