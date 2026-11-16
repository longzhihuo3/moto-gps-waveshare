import Foundation
import MotoNavigationCore

/// WGS-84 ↔ GCJ-02 conversion used when talking to AMapNaviKit. The SDK
/// consumes and produces GCJ-02 coordinates while the app's location source
/// and route contracts are WGS-84. This is the standard offset approximation
/// (sub-meter accuracy for route planning purposes), implemented locally so
/// the app does not need the gateway's coordinate endpoints.
enum GCJ02CoordinateMath {
    private static let pi = Double.pi
    private static let a = 6_378_245.0
    private static let ee = 0.006_693_421_622_965_943_23

    static func wgs84ToGCJ02(longitudeDeg: Double, latitudeDeg: Double) -> (longitudeDeg: Double, latitudeDeg: Double) {
        if outOfChina(longitudeDeg: longitudeDeg, latitudeDeg: latitudeDeg) {
            return (longitudeDeg, latitudeDeg)
        }
        var dLat = transformLatitude(x: longitudeDeg - 105.0, y: latitudeDeg - 35.0)
        var dLon = transformLongitude(x: longitudeDeg - 105.0, y: latitudeDeg - 35.0)
        let radLat = latitudeDeg / 180.0 * pi
        var magic = sin(radLat)
        magic = 1 - ee * magic * magic
        let sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * pi)
        dLon = (dLon * 180.0) / (a / sqrtMagic * cos(radLat) * pi)
        return (longitudeDeg + dLon, latitudeDeg + dLat)
    }

    /// Iterative inverse: converges below a centimeter after two passes.
    static func gcj02ToWGS84(longitudeDeg: Double, latitudeDeg: Double) -> (longitudeDeg: Double, latitudeDeg: Double) {
        if outOfChina(longitudeDeg: longitudeDeg, latitudeDeg: latitudeDeg) {
            return (longitudeDeg, latitudeDeg)
        }
        var wgsLongitude = longitudeDeg
        var wgsLatitude = latitudeDeg
        for _ in 0 ..< 2 {
            let offset = wgs84ToGCJ02(longitudeDeg: wgsLongitude, latitudeDeg: wgsLatitude)
            wgsLongitude -= offset.longitudeDeg - longitudeDeg
            wgsLatitude -= offset.latitudeDeg - latitudeDeg
        }
        return (wgsLongitude, wgsLatitude)
    }

    static func distanceM(
        fromLongitude: Double, fromLatitude: Double,
        toLongitude: Double, toLatitude: Double
    ) -> Double {
        let earthRadiusM = 6_371_000.0
        let startLatitude = fromLatitude * pi / 180
        let endLatitude = toLatitude * pi / 180
        let latitudeDelta = (toLatitude - fromLatitude) * pi / 180
        let longitudeDelta = (toLongitude - fromLongitude) * pi / 180
        let haversine = pow(sin(latitudeDelta / 2), 2) +
            cos(startLatitude) * cos(endLatitude) * pow(sin(longitudeDelta / 2), 2)
        return 2 * earthRadiusM * asin(sqrt(min(1, max(0, haversine))))
    }

    private static func outOfChina(longitudeDeg: Double, latitudeDeg: Double) -> Bool {
        longitudeDeg < 72.004 || longitudeDeg > 137.8347 ||
            latitudeDeg < 0.8293 || latitudeDeg > 55.8271
    }

    private static func transformLatitude(x: Double, y: Double) -> Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        result += (20.0 * sin(y * pi) + 40.0 * sin(y / 3.0 * pi)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * pi) + 320 * sin(y * pi / 30.0)) * 2.0 / 3.0
        return result
    }

    private static func transformLongitude(x: Double, y: Double) -> Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        result += (20.0 * sin(x * pi) + 40.0 * sin(x / 3.0 * pi)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * pi) + 300.0 * sin(x / 30.0 * pi)) * 2.0 / 3.0
        return result
    }
}
