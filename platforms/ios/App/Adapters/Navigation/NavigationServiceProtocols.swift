import Foundation
import MotoNavigationCore

/// Capabilities the navigation flow needs from whichever provider backs it.
/// `AmapGatewayRouteProvider` (self-hosted gateway) and
/// `AmapNaviKitRouteProvider` (on-device AMap SDK) both implement these.
public protocol NavigationRouteOptionsProviding: Sendable {
    /// Candidate routes for the confirmation screen. Implementations may
    /// return fewer alternatives than the gateway; one route is acceptable.
    func routeOptions(for request: RouteRequest) async throws -> [RoutePlan]
}

/// Destination keyword search. `origin` biases the ranking toward the rider.
public protocol PlaceSearching: Sendable {
    func search(keywords: String, near origin: WGS84Point?) async throws -> [PlaceSearchResult]
}
