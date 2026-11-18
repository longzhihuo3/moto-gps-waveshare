import Foundation
import MotoNavigationCore

enum AppConfiguration {
    static var gatewayBaseURL: URL {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--moto-reset-gateway") {
            UserDefaults.standard.removeObject(forKey: GatewayConfiguration.defaultsKey)
        }
        #endif
        return GatewayConfiguration.resolvedURL(
            bundledAddress: Bundle.main.object(forInfoDictionaryKey: "MOTOGPSGatewayBaseURL") as? String
        ) ?? URL(string: "https://example.invalid/moto-gps/api/")!
    }

    /// AMap iOS SDK key. When set (Info.plist `MOTOGPSAMapKey`), route
    /// planning, destination search and voice prompts run on-device through
    /// AMapNaviKit and the self-hosted gateway becomes optional. When empty
    /// the app falls back to the gateway providers.
    static var amapKey: String {
        let raw = Bundle.main.object(forInfoDictionaryKey: "MOTOGPSAMapKey") as? String ?? ""
        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static var amapKeyConfigured: Bool {
        #if DEBUG
        // UI 测试用：模拟「未配置 AMap Key」的旧行为，让网关设置入口可测。
        if ProcessInfo.processInfo.arguments.contains("--moto-disable-amap") {
            return false
        }
        #endif
        return !amapKey.isEmpty
    }

    /// 出行模式：riding=自行车（非机动车路线）、motorcycle=摩托车（机动车
    /// 路线）、walking=步行。默认 riding；持久化在 UserDefaults
    /// `MotoGPS.travelMode`，首页切换即时生效并保存。
    static var naviTravelMode: AmapNaviKitRouteProvider.TravelMode {
        AmapNaviKitRouteProvider.TravelMode(
            rawValue: UserDefaults.standard.string(forKey: "MotoGPS.travelMode") ?? ""
        ) ?? .riding
    }

    static func setTravelMode(_ mode: AmapNaviKitRouteProvider.TravelMode) {
        UserDefaults.standard.set(mode.rawValue, forKey: "MotoGPS.travelMode")
    }
}
