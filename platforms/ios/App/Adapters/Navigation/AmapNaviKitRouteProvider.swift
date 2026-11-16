import Foundation
import AMapNaviKit
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
/// The AMapNaviKit route managers are process-wide singletons and their
/// planning callback carries no request identity, so plans are serialized
/// through one FIFO queue.
final class AmapNaviKitRouteProvider: NSObject, NavigationRouteProviding, NavigationRouteOptionsProviding, @unchecked Sendable {
    enum TravelMode: String {
        case riding
        case walking

        /// Cruising speed used to estimate route duration when the SDK does
        /// not expose a per-route duration for ride/walk planning.
        var speedMPS: Double { self == .riding ? 4.2 : 1.25 }
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

            // The SDK callback normally arrives within a few seconds; never
            // leave a rider staring at "正在规划路线" forever.
            DispatchQueue.global().asyncAfter(deadline: .now() + 15) { [weak self] in
                self?.failPending(ifOlderThan: mode, error: AmapNaviKitError.timedOut)
            }

            let originGCJ = GCJ02CoordinateMath.wgs84ToGCJ02(
                longitudeDeg: originWGS84.longitudeDeg,
                latitudeDeg: originWGS84.latitudeDeg
            )
            let destinationGCJ = GCJ02CoordinateMath.wgs84ToGCJ02(
                longitudeDeg: destinationWGS84.longitudeDeg,
                latitudeDeg: destinationWGS84.latitudeDeg
            )
            let start = AMapNaviPoint.location(
                withLatitude: CGFloat(originGCJ.latitudeDeg),
                longitude: CGFloat(originGCJ.longitudeDeg)
            )
            let end = AMapNaviPoint.location(
                withLatitude: CGFloat(destinationGCJ.latitudeDeg),
                longitude: CGFloat(destinationGCJ.longitudeDeg)
            )

            let accepted: Bool
            if mode == .riding {
                let manager = AMapNaviRideManager.sharedInstance()
                manager.delegate = self
                accepted = manager.calculateRoute(withStart: start, wayPoints: nil, end: end)
            } else {
                let manager = AMapNaviWalkManager.sharedInstance()
                manager.delegate = self
                accepted = manager.calculateRoute(withStart: start, wayPoints: nil, end: end)
            }
            if !accepted {
                failPending(ifOlderThan: mode, error: AmapNaviKitError.rejected("路线规划未能发起，请稍后重试"))
            }
        }
    }

    func rideManager(_ rideManager: AMapNaviRideManager, onCalculateRouteFinished route: AMapNaviRoute?, withError error: Error?) {
        handleFinished(route: route, error: error)
    }

    func walkManager(_ walkManager: AMapNaviWalkManager, onCalculateRouteFinished route: AMapNaviRoute?, withError error: Error?) {
        handleFinished(route: route, error: error)
    }

    private func handleFinished(route: AMapNaviRoute?, error: Error?) {
        lock.lock()
        let pending = pendingQueue.isEmpty ? nil : pendingQueue.removeFirst()
        lock.unlock()
        guard let pending else { return }

        if let error {
            pending.continuation.resume(throwing: AmapNaviKitError.rejected(error.localizedDescription))
            return
        }
        guard let route, let plan = Self.convert(route, mode: pending.mode) else {
            pending.continuation.resume(throwing: AmapNaviKitError.noRoute)
            return
        }
        pending.continuation.resume(returning: [plan])
    }

    private func failPending(ifOlderThan mode: AmapNaviKitRouteProvider.TravelMode, error: AmapNaviKitError) {
        lock.lock()
        guard let index = pendingQueue.firstIndex(where: { $0.mode == mode }) else {
            lock.unlock()
            return
        }
        let pending = pendingQueue.remove(at: index)
        lock.unlock()
        pending.continuation.resume(throwing: error)
    }

    private static func convert(_ route: AMapNaviRoute, mode: AmapNaviKitRouteProvider.TravelMode) -> RoutePlan? {
        var polyline: [GCJ02Point] = []
        var maneuvers: [RouteManeuver] = []
        var offsetM: Double = 0
        var previous: GCJ02Point?

        for (index, step) in (route.steps ?? []).enumerated() {
            let stepCoordinates = (step.coordinates ?? []).map {
                GCJ02Point(longitudeDeg: Double($0.longitude), latitudeDeg: Double($0.latitude))
            }
            if let first = stepCoordinates.first, let previous {
                offsetM += GCJ02CoordinateMath.distanceM(
                    fromLongitude: previous.longitudeDeg,
                    fromLatitude: previous.latitudeDeg,
                    toLongitude: first.longitudeDeg,
                    toLatitude: first.latitudeDeg
                )
            }
            let maneuver = RouteManeuver(
                id: UInt32(index + 1),
                type: maneuverType(from: step.action),
                routeOffsetM: offsetM,
                roadName: step.roadName ?? "",
                instruction: Self.instructionText(action: step.action, roadName: step.roadName)
            )
            maneuvers.append(maneuver)
            for coordinate in stepCoordinates {
                if let last = polyline.last,
                   abs(last.longitudeDeg - coordinate.longitudeDeg) < 1e-7,
                   abs(last.latitudeDeg - coordinate.latitudeDeg) < 1e-7
                {
                    continue
                }
                polyline.append(coordinate)
            }
            previous = stepCoordinates.last
        }

        guard polyline.count >= 2 else { return nil }

        var totalDistanceM = 0.0
        for index in 1 ..< polyline.count {
            totalDistanceM += GCJ02CoordinateMath.distanceM(
                fromLongitude: polyline[index - 1].longitudeDeg,
                fromLatitude: polyline[index - 1].latitudeDeg,
                toLongitude: polyline[index].longitudeDeg,
                toLatitude: polyline[index].latitudeDeg
            )
        }
        guard totalDistanceM > 0 else { return nil }

        let generatedAtMs = UInt64(Date().timeIntervalSince1970 * 1_000)
        return RoutePlan(
            routeID: "navikit-\(Int(totalDistanceM.rounded()))-\(generatedAtMs)",
            provider: "amap-navikit",
            generatedAtMs: generatedAtMs,
            totalDistanceM: totalDistanceM,
            totalDurationS: max(1, Int((totalDistanceM / mode.speedMPS).rounded())),
            polyline: polyline,
            maneuvers: maneuvers,
            traffic: [],
            speedLimitKph: nil
        )
    }

    private static func maneuverType(from action: AMapNaviAction) -> ManeuverType {
        switch action {
        case .left: .left
        case .right: .right
        case .slightLeft: .slightLeft
        case .slightRight: .slightRight
        case .sharpLeft: .sharpLeft
        case .sharpRight: .sharpRight
        case .uTurn: .uTurnLeft
        case .enterRoundabout: .roundabout
        case .exitRoundabout: .exit
        default: .continue
        }
    }

    private static func instructionText(action: AMapNaviAction, roadName: String?) -> String {
        let phrase: String
        switch action {
        case .left: phrase = "左转"
        case .right: phrase = "右转"
        case .slightLeft: phrase = "向左前方行驶"
        case .slightRight: phrase = "向右前方行驶"
        case .sharpLeft: phrase = "向左后方急转"
        case .sharpRight: phrase = "向右后方急转"
        case .uTurn: phrase = "掉头"
        case .enterRoundabout: phrase = "进入环岛"
        case .exitRoundabout: phrase = "驶出环岛"
        default: phrase = "直行"
        }
        guard let roadName, !roadName.isEmpty else { return phrase }
        return "\(phrase)进入\(roadName)"
    }
}
