import Foundation
import AMapSearchKit
import MotoNavigationCore

/// Destination keyword search through AMapSearchKit so the app works without
/// the self-hosted gateway. Each search owns a fresh AMapSearchAPI instance;
/// in-flight sessions are matched back by request identity.
final class AmapNaviKitPlaceProvider: NSObject, PlaceSearching, AMapSearchDelegate, @unchecked Sendable {
    func search(keywords: String, near origin: WGS84Point?) async throws -> [PlaceSearchResult] {
        let query = keywords.trimmingCharacters(in: .whitespacesAndNewlines)
        guard query.count >= 2 else { return [] }
        guard AmapNaviKitBootstrap.isInstalled, AMapServices.shared() != nil else {
            throw AmapNaviKitError.sdkUnavailable
        }
        guard let searchAPI = AMapSearchAPI() else {
            throw AmapNaviKitError.sdkUnavailable
        }

        let request = AMapPOIKeywordsSearchRequest()
        request.keywords = query
        request.page = 1
        request.offset = 20
        if let origin, origin.isValid {
            let centerGCJ = GCJ02CoordinateMath.wgs84ToGCJ02(
                longitudeDeg: origin.longitudeDeg,
                latitudeDeg: origin.latitudeDeg
            )
            request.location = AMapGeoPoint.location(
                withLatitude: CGFloat(centerGCJ.latitudeDeg),
                longitude: CGFloat(centerGCJ.longitudeDeg)
            )
        }

        return try await withCheckedThrowingContinuation { continuation in
            searchAPI.delegate = self
            POISearchSession.begin(
                request: request,
                api: searchAPI,
                continuation: continuation
            )
        }
    }

    func onPOISearchDone(_ request: AMapPOISearchBaseRequest?, response: AMapPOISearchResponse?) {
        POISearchSession.deliver(request: request, response: response)
    }

    func aMapSearchRequest(_ request: Any?, didFailWithError error: Error?) {
        POISearchSession.deliver(request: request, error: error)
    }
}

/// Holds one in-flight search. Completions are routed back to the owning
/// session by request object identity.
private final class POISearchSession {
    private static let lock = NSLock()
    private static var activeSessions: [ObjectIdentifier: POISearchSession] = [:]

    private let requestObject: AMapPOISearchBaseRequest
    private let continuation: CheckedContinuation<[PlaceSearchResult], Error>

    private init(request: AMapPOISearchBaseRequest, continuation: CheckedContinuation<[PlaceSearchResult], Error>) {
        requestObject = request
        self.continuation = continuation
    }

    static func begin(
        request: AMapPOIKeywordsSearchRequest,
        api: AMapSearchAPI,
        continuation: CheckedContinuation<[PlaceSearchResult], Error>
    ) {
        let session = POISearchSession(request: request, continuation: continuation)
        lock.lock()
        activeSessions[ObjectIdentifier(request)] = session
        lock.unlock()
        api.aMapPOIKeywordsSearch(request)
    }

    static func deliver(request: AMapPOISearchBaseRequest?, response: AMapPOISearchResponse?) {
        let session = popSession(for: request)
        guard let session else { return }
        let places = (response?.pois ?? []).compactMap(Self.convert)
        session.continuation.resume(returning: places)
    }

    static func deliver(request: Any?, error: Error?) {
        let session = popSession(for: request)
        guard let session else { return }
        session.continuation.resume(
            throwing: AmapNaviKitError.rejected(error?.localizedDescription ?? "地点搜索失败")
        )
    }

    private static func popSession(for request: Any?) -> POISearchSession? {
        guard let keywordsRequest = request as? AMapPOISearchBaseRequest else { return nil }
        lock.lock()
        defer { lock.unlock() }
        return activeSessions.removeValue(forKey: ObjectIdentifier(keywordsRequest))
    }

    private static func convert(_ poi: AMapPOI) -> PlaceSearchResult? {
        guard let location = poi.location else { return nil }
        let wgs = GCJ02CoordinateMath.gcj02ToWGS84(
            longitudeDeg: Double(location.longitude),
            latitudeDeg: Double(location.latitude)
        )
        let point = WGS84Point(longitudeDeg: wgs.longitudeDeg, latitudeDeg: wgs.latitudeDeg)
        guard point.isValid else { return nil }

        let city = poi.city ?? ""
        let district = poi.district ?? ""
        var areaComponents: [String] = []
        if !city.isEmpty { areaComponents.append(city) }
        if !district.isEmpty { areaComponents.append(district) }

        let distanceM: Double? = poi.distance > 0 ? Double(poi.distance) : nil
        return PlaceSearchResult(
            id: poi.uid ?? "geo:\(poi.name ?? "")",
            name: poi.name ?? "",
            address: poi.address ?? "",
            city: city,
            district: district,
            displayArea: areaComponents.joined(separator: "·"),
            location: point,
            distanceM: distanceM
        )
    }
}
