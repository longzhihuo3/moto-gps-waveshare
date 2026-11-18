import Foundation
import MotoNavigationCore

/// OSM Overpass 真实道路限速（maxspeed 标签），best-effort。
///
/// 高德不透出限速数据（导航 SDK 的规划结果 AMapNaviLink 与实时回调 AMapNaviInfo
/// 均无限速字段），OSM maxspeed 是唯一可编程来源。策略与后端 osm-speed-limit.js
/// 一致：沿折线每 ~600m 采样（GCJ-02 → WGS-84，不纠偏会偏移 300~600m 匹配到
/// 错误道路），Overpass 查 35m 邻域 maxspeed，取众数（同频取较小值，偏保守）。
///
/// 任何失败静默返回 nil —— 限速是增强能力，绝不影响算路主流程
/// （表盘不显示限速牌即可）。Overpass 为免费公共服务，不消耗高德配额。
enum OSMRouteSpeedLimit {

    /// 端点顺序按国内网络实测连通性排列（与后端一致）：osm.ch 可直连。
    private static let endpoints = [
        "https://overpass.osm.ch/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    ]
    private static let sampleIntervalM: Double = 600
    private static let maxSamples = 24
    private static let matchRadiusM = 35
    private static let requestTimeout: TimeInterval = 3

    /// 驾车（摩托）路线的众数限速（kph）。polyline 为高德原生 GCJ-02 坐标。
    /// 三端点并发赛跑，谁先返回有效值用谁（对应 JS 版 Promise.any）。
    static func fetchSpeedLimitKph(polylineGCJ02: [GCJ02Point]) async -> Int? {
        let samples = samplePolyline(polylineGCJ02)
        guard samples.count >= 2 else { return nil }
        let query = buildOverpassQuery(samples)
        return await withTaskGroup(of: Int?.self) { group in
            for endpoint in endpoints {
                group.addTask { await attempt(endpoint: endpoint, query: query) }
            }
            var winner: Int?
            for await result in group where result != nil {
                winner = result
                group.cancelAll()
                break
            }
            return winner
        }
    }

    // 沿折线每 ~600m 采样并纠偏到 WGS-84（OSM 使用 WGS-84 坐标）。
    private static func samplePolyline(_ polyline: [GCJ02Point]) -> [[Double]] {
        guard polyline.count >= 2 else { return [] }
        var samples: [GCJ02Point] = [polyline[0]]
        var accumulatedM = 0.0
        var nextThresholdM = sampleIntervalM
        for index in 1..<polyline.count {
            accumulatedM += GCJ02CoordinateMath.distanceM(
                fromLongitude: polyline[index - 1].longitudeDeg,
                fromLatitude: polyline[index - 1].latitudeDeg,
                toLongitude: polyline[index].longitudeDeg,
                toLatitude: polyline[index].latitudeDeg
            )
            while accumulatedM >= nextThresholdM, samples.count < maxSamples {
                samples.append(polyline[index])
                nextThresholdM += sampleIntervalM
            }
            if samples.count >= maxSamples { break }
        }
        if samples.count < maxSamples, let last = polyline.last, samples.last != last {
            samples.append(last)
        }
        return samples.prefix(maxSamples).map { point in
            let wgs = GCJ02CoordinateMath.gcj02ToWGS84(
                longitudeDeg: point.longitudeDeg,
                latitudeDeg: point.latitudeDeg
            )
            return [wgs.latitudeDeg, wgs.longitudeDeg]
        }
    }

    private static func buildOverpassQuery(_ samples: [[Double]]) -> String {
        let clauses = samples
            .map { String(format: "way(around:%d,%.5f,%.5f)[\"maxspeed\"];", matchRadiusM, $0[0], $0[1]) }
            .joined()
        return "[out:json][timeout:6];(\(clauses));out tags;"
    }

    private struct OverpassResponse: Decodable {
        struct Element: Decodable {
            struct Tags: Decodable {
                let maxspeed: String?
            }
            let tags: Tags?
        }
        let elements: [Element]
    }

    private static func attempt(endpoint: String, query: String) async -> Int? {
        guard let url = URL(string: endpoint) else { return nil }
        var request = URLRequest(url: url, timeoutInterval: requestTimeout)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        request.httpBody = "data=\(encoded)".data(using: .utf8)
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            let payload = try JSONDecoder().decode(OverpassResponse.self, from: data)
            return selectModalLimit(parseMaxspeedValues(payload.elements))
        } catch {
            return nil // 超时/网络失败：静默放弃，限速牌不显示
        }
    }

    /// 解析 maxspeed 标签（仅接受纯 kph 数字，丢弃 "variable"/"50 mph" 等不可信值）。
    private static func parseMaxspeedValues(_ elements: [OverpassResponse.Element]) -> [Int] {
        var values: [Int] = []
        for element in elements {
            guard let raw = element.tags?.maxspeed,
                  let value = Int(raw),
                  (10...150).contains(value) else { continue }
            values.append(value)
        }
        return values
    }

    /// 众数（同频取较小值，偏保守）。无值返回 nil。
    private static func selectModalLimit(_ values: [Int]) -> Int? {
        guard !values.isEmpty else { return nil }
        var counts: [Int: Int] = [:]
        for value in values { counts[value, default: 0] += 1 }
        var best: Int?
        var bestCount = 0
        for (value, count) in counts {
            if count > bestCount || (count == bestCount && value < (best ?? Int.max)) {
                best = value
                bestCount = count
            }
        }
        return best
    }
}
