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

    /// 步行/骑行模式。默认骑行（摩托车/骑行场景）；在 UserDefaults
    /// `MotoGPS.travelMode` 写入 walking/riding 可切换（重启 App 生效）。
    static var naviTravelMode: AmapNaviKitRouteProvider.TravelMode {
        AmapNaviKitRouteProvider.TravelMode(
            rawValue: UserDefaults.standard.string(forKey: "MotoGPS.travelMode") ?? ""
        ) ?? .riding
    }
}
