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
        case motorcycle
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

/// Serializes AMapNaviKit route planning with a per-request timeout.
/// Ride/walk/drive managers are process-wide singletons whose callbacks carry
/// no request identity, so every pending request gets its own UUID; timeouts
/// cancel by identity and callbacks match by travel mode instead of queue
/// position, so a stale timer can never kill a healthy request or misdeliver
/// another request's result.
private final class AmapNaviKitPlanningSession: NSObject, AMapNaviRideManagerDelegate, AMapNaviWalkManagerDelegate, AMapNaviDriveManagerDelegate, @unchecked Sendable {
    static let shared = AmapNaviKitPlanningSession()

    private struct Pending {
        let id: UUID
        let mode: AmapNaviKitRouteProvider.TravelMode
        let originWGS84: WGS84Point
        let destinationWGS84: WGS84Point
        let startedAt: ContinuousClock.Instant
        let continuation: CheckedContinuation<[RoutePlan], Error>
    }

    private struct CacheEntry {
        let plans: [RoutePlan]
        let expiresAt: Date
    }

    /// Identical origin/destination requests within the TTL return instantly;
    /// AMap's own server latency dominates planning time (~10s observed), so
    /// repeated previews of the same trip should not pay it again.
    private static let cacheTTL: TimeInterval = 300
    /// Origin/destination rounded to this grid size count as the same request.
    private static let cacheGridDeg = 0.0005 // ~55 m

    private let lock = NSLock()
    private var pendingQueue: [Pending] = []
    private var sdkLogTail: [String] = []
    private var planCache: [String: CacheEntry] = [:]

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
        let cacheKey = Self.cacheKey(mode: mode, origin: originWGS84, destination: destinationWGS84)
        lock.lock()
        let cached = planCache[cacheKey]
        if let cached, cached.expiresAt > Date() {
            lock.unlock()
            appendSdkLog("[app] cache hit \(cacheKey.prefix(16)) plans=\(cached.plans.count)")
            return cached.plans
        }
        if cached != nil {
            planCache[cacheKey] = nil
        }
        lock.unlock()

        return try await withCheckedThrowingContinuation { continuation in
            let requestID = UUID()
            lock.lock()
            pendingQueue.append(Pending(
                id: requestID,
                mode: mode,
                originWGS84: originWGS84,
                destinationWGS84: destinationWGS84,
                startedAt: ContinuousClock.now,
                continuation: continuation
            ))
            lock.unlock()
            appendSdkLog("[app] begin id=\(requestID.uuidString.prefix(8)) mode=\(mode.rawValue) \(originWGS84.longitudeDeg),\(originWGS84.latitudeDeg)->\(destinationWGS84.longitudeDeg),\(destinationWGS84.latitudeDeg)")

            // The SDK callback normally arrives within a few seconds; never
            // leave a rider staring at "正在规划路线" forever. Cancels by request
            // identity, not queue position.
            DispatchQueue.global().asyncAfter(deadline: .now() + 45) { [weak self] in
                self?.failPending(id: requestID, base: AmapNaviKitError.timedOut)
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
                failPending(id: requestID, base: AmapNaviKitError.rejected("坐标转换失败"))
                return
            }

            let accepted: Bool
            switch mode {
            case .riding:
                let manager = AMapNaviRideManager.sharedInstance()
                manager.delegate = self
                accepted = manager.calculateRideRoute(withStart: start, end: end)
            case .walking:
                let manager = AMapNaviWalkManager.sharedInstance()
                manager.delegate = self
                accepted = manager.calculateWalkRoute(withStart: [start], end: [end])
            case .motorcycle:
                // Motorcycle follows motor-vehicle routing (car roads), so its
                // travel time differs from bicycle routing by design.
                let manager = AMapNaviDriveManager.sharedInstance()
                manager.delegate = self
                accepted = manager.calculateDriveRoute(
                    withStart: [start],
                    end: [end],
                    wayPoints: nil,
                    drivingStrategy: .drivingStrategySingleDefault
                )
            }
            if !accepted {
                failPending(id: requestID, base: AmapNaviKitError.rejected("路线规划未能发起，请稍后重试"))
            }
        }
    }

    /// v11 renamed the success callbacks: the ObjC selector is
    /// rideManagerOnCalculateRouteSuccess:calculateRouteResult:. The clang
    /// importer splits it into Swift rideManager(onCalculateRouteSuccess:),
    /// where the first (manager) argument carries the onCalculateRouteSuccess
    /// label. The old-format rideManager(_:onCalculateRouteSuccess:) maps to a
    /// selector that no longer exists and is never invoked by the SDK.
    func rideManager(onCalculateRouteSuccess rideManager: AMapNaviRideManager) {
        finishWithRoutes(from: rideManager, mode: .riding)
    }

    func rideManager(
        onCalculateRouteSuccess rideManager: AMapNaviRideManager,
        calculateRouteResult: AMapNaviCalculateRouteResult
    ) {
        finishWithRoutes(from: rideManager, mode: .riding)
    }

    func rideManager(_ rideManager: AMapNaviRideManager, onCalculateRouteFailure error: Error) {
        finishWithFailure(error, mode: .riding)
    }

    func rideManager(
        _ rideManager: AMapNaviRideManager,
        onCalculateRouteFailure error: Error,
        calculateRouteResult: AMapNaviCalculateRouteResult
    ) {
        finishWithFailure(error, mode: .riding)
    }

    func walkManager(onCalculateRouteSuccess walkManager: AMapNaviWalkManager) {
        finishWithRoutes(from: walkManager, mode: .walking)
    }

    func walkManager(
        onCalculateRouteSuccess walkManager: AMapNaviWalkManager,
        calculateRouteResult: AMapNaviCalculateRouteResult
    ) {
        finishWithRoutes(from: walkManager, mode: .walking)
    }

    func walkManager(_ walkManager: AMapNaviWalkManager, onCalculateRouteFailure error: Error) {
        finishWithFailure(error, mode: .walking)
    }

    func walkManager(
        _ walkManager: AMapNaviWalkManager,
        onCalculateRouteFailure error: Error,
        calculateRouteResult: AMapNaviCalculateRouteResult
    ) {
        finishWithFailure(error, mode: .walking)
    }

    /// Drive callbacks (motorcycle mode). Success selector is
    /// driveManagerOnCalculateRouteSuccess: (importer-renders as
    /// driveManager(onCalculateRouteSuccess:)) plus the newer variants with
    /// type/result parameters that keep the driveManager: prefix form.
    func driveManager(onCalculateRouteSuccess driveManager: AMapNaviDriveManager) {
        finishWithRoutes(from: driveManager, mode: .motorcycle)
    }

    func driveManager(
        _ driveManager: AMapNaviDriveManager,
        onCalculateRouteSuccessWith type: AMapNaviRoutePlanType
    ) {
        finishWithRoutes(from: driveManager, mode: .motorcycle)
    }

    func driveManager(
        _ driveManager: AMapNaviDriveManager,
        onCalculateRouteSuccessWith type: AMapNaviRoutePlanType,
        calculateRouteResult: AMapNaviCalculateRouteResult
    ) {
        finishWithRoutes(from: driveManager, mode: .motorcycle)
    }

    func driveManager(_ driveManager: AMapNaviDriveManager, onCalculateRouteFailure error: Error) {
        finishWithFailure(error, mode: .motorcycle)
    }

    func driveManager(
        _ driveManager: AMapNaviDriveManager,
        onCalculateRouteFailure error: Error,
        routePlanType: AMapNaviRoutePlanType
    ) {
        finishWithFailure(error, mode: .motorcycle)
    }

    private func finishWithRoutes(from manager: AMapNaviRideManager, mode: AmapNaviKitRouteProvider.TravelMode) {
        appendSdkLog("[app] ride success routes=\(manager.naviRoutes().count)")
        finishWithRoutes(routeIDs: manager.naviRouteIDs(), routesByID: manager.naviRoutes(), mode: mode)
    }

    private func finishWithRoutes(from manager: AMapNaviWalkManager, mode: AmapNaviKitRouteProvider.TravelMode) {
        appendSdkLog("[app] walk success routes=\(manager.naviRoutes().count)")
        finishWithRoutes(routeIDs: manager.naviRouteIDs(), routesByID: manager.naviRoutes(), mode: mode)
    }

    private func finishWithRoutes(from manager: AMapNaviDriveManager, mode: AmapNaviKitRouteProvider.TravelMode) {
        // Drive manager exposes naviRouteIDs/naviRoutes as properties (the
        // ride/walk managers expose them as methods).
        appendSdkLog("[app] drive success routes=\(manager.naviRoutes?.count ?? 0)")
        finishWithRoutes(routeIDs: manager.naviRouteIDs ?? [], routesByID: manager.naviRoutes ?? [:], mode: mode)
    }

    private static func cacheKey(
        mode: AmapNaviKitRouteProvider.TravelMode,
        origin: WGS84Point,
        destination: WGS84Point
    ) -> String {
        func grid(_ value: Double) -> Int {
            Int((value / cacheGridDeg).rounded())
        }
        return "\(mode.rawValue)|\(grid(origin.longitudeDeg)),\(grid(origin.latitudeDeg))->\(grid(destination.longitudeDeg)),\(grid(destination.latitudeDeg))"
    }

    private func storeInCache(_ pending: Pending, plans: [RoutePlan]) {
        lock.lock()
        planCache[Self.cacheKey(mode: pending.mode, origin: pending.originWGS84, destination: pending.destinationWGS84)] = CacheEntry(
            plans: plans,
            expiresAt: Date().addingTimeInterval(Self.cacheTTL)
        )
        lock.unlock()
    }

    /// Consume the oldest pending request of the given mode. The SDK callback
    /// carries no request identity, so mode is the best available match; in
    /// practice the app issues one mode at a time, but matching by mode still
    /// prevents a stale callback from stealing another mode's result.
    private func popFirstPending(mode: AmapNaviKitRouteProvider.TravelMode) -> Pending? {
        lock.lock()
        defer { lock.unlock() }
        guard let index = pendingQueue.firstIndex(where: { $0.mode == mode }) else { return nil }
        return pendingQueue.remove(at: index)
    }

    private func finishWithRoutes(
        routeIDs: [NSNumber],
        routesByID: [NSNumber: AMapNaviRoute],
        mode: AmapNaviKitRouteProvider.TravelMode
    ) {
        guard let pending = popFirstPending(mode: mode) else { return }
        let elapsed = ContinuousClock.now - pending.startedAt
        let elapsedMs = elapsed.components.seconds * 1_000
            + elapsed.components.attoseconds / 1_000_000_000_000_000
        appendSdkLog("[app] converted plans mode=\(mode.rawValue) elapsed=\(elapsedMs)ms")

        let orderedRoutes = routeIDs.compactMap { routesByID[$0] }
        let plans = orderedRoutes.compactMap { Self.convert($0, mode: pending.mode) }
        guard !plans.isEmpty else {
            pending.continuation.resume(throwing: AmapNaviKitError.noRoute)
            return
        }
        storeInCache(pending, plans: plans)
        pending.continuation.resume(returning: Array(plans.prefix(3)))
    }

    private func finishWithFailure(_ error: Error, mode: AmapNaviKitRouteProvider.TravelMode) {
        guard let pending = popFirstPending(mode: mode) else { return }
        let nsError = error as NSError
        let message = "\(error.localizedDescription)（code \(nsError.code)，\(nsError.domain)）"
        pending.continuation.resume(throwing: AmapNaviKitError.rejected(message))
    }

    /// Cancel the exact request that timed out (or failed pre-flight), never
    /// whatever happens to sit at the queue head now.
    private func failPending(id: UUID, base: AmapNaviKitError) {
        lock.lock()
        guard let index = pendingQueue.firstIndex(where: { $0.id == id }) else {
            lock.unlock()
            return
        }
        let pending = pendingQueue.remove(at: index)
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
        let trafficLightCount = route.routeSegments
            .flatMap { $0.links }
            .filter { $0.isHadTrafficLights }
            .count
        // traffic / speedLimitKph stay empty on purpose: per AMapNaviKit v11
        // headers, link trafficStatus is driving-only and the planning result
        // carries no speed-limit field for riding/walking, so there is nothing
        // to map. The iOS gateway path never filled them either (the OSM
        // speed-limit pipe serves the Android phone app only).
        return RoutePlan(
            routeID: "navikit-\(route.routeLength)-\(generatedAtMs)-\(maneuvers.count)",
            provider: "amap-navikit",
            generatedAtMs: generatedAtMs,
            totalDistanceM: Double(route.routeLength),
            totalDurationS: max(1, route.routeTime),
            polyline: polyline,
            maneuvers: maneuvers,
            traffic: [],
            speedLimitKph: nil,
            trafficLightCount: trafficLightCount
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
