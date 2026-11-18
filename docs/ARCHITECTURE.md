> **语言 / Language:** 中文 · [English](ARCHITECTURE.en.md)

# 架构与数据边界

```text
iPhone CoreLocation (WGS84)
        │
        ├── 内嵌 AMapNaviKit（主链路，无需网关）
        │     隐私合规两步前置 → 骑行/步行/摩托规划 → UUID 队列 → 5min 缓存
        │     摩托模式：算路后沿路线采样查 OSM Overpass maxspeed（3s 超时静默跳过）
        │
        └──（可选）HTTPS → Node gateway → AMap Web 服务
                  RouteBundle (GCJ-02)
                  ▼
共享 C++ NavCore / NavApp（iPhone 中执行进度与偏航逻辑）
        │
  BLE snapshot + route + map window（0x10 快照含剩余路程/剩余时间/速度/限速）
        ▼
OPPO Watch X · Android App（航向融合 v3 + 离屏位图渲染）
        ▼
466×466 圆屏：导航页 / 仪表盘速度页 / 音乐页
```

iPhone 是路线与位置的权威端，圆屏不是另一个独立路线规划器。
BLE 服务与帧格式见 `shared/protocol/ble-navigation-v1.md`。
触摸切页和音乐按键沿 BLE 反向传给 iPhone，后者调用 Apple Music 播放控制。

## 内嵌导航 SDK（AMapNaviKit）边界

- **隐私合规前置**：任何 manager 实例化前必须完成 `AMapSearchAPI` 与
  `AMapNaviManagerConfig` 的 `updatePrivacyShow` / `updatePrivacyAgree` 两步，
  否则搜索直接报「SDK 未初始化」；
- **Bundle ID 绑定**：高德 Key 按应用绑定 Bundle ID。SideStore 重签名会改写
  Bundle ID（原 ID 存 `ALTBundleID`），导致 `INVALID_USER_SCODE`——需在高德控制台
  改绑本机实际 ID 后删重装；
- **回调签名陷阱**：v11 SDK 把成功回调选择器改为连续关键词链，Swift 导入器会
  拆成不同的参数标签形式；实现必须按导入器生成的签名，否则能编译但永不触发；
- **限速数据缺失**：SDK 规划结果与实时导航信息均不透出限速。摩托模式限速由
  OSM Overpass maxspeed 补齐（600m 采样、GCJ→WGS 纠偏、35m 匹配、众数取小、
  3s 超时静默跳过），骑行/步行保持无限速；
- **请求身份化**：规划会话 UUID 身份化队列，完成/超时按 ID 匹配，防止并发串话；
  结果缓存 5 分钟（约 55m 网格键），重复规划不再走 SDK。

济南 OSM SQLite 库只存放在 iPhone。手机查询车辆周围的道路、建筑，再将有限容量的
窗口传给圆屏；它是背景场景数据，不提供离线搜索、全国算路或实时路况。
显示前统一坐标系，不能把 WGS84 和 GCJ-02 的点直接混绘。

Web 使用同一份 C++ Presenter 与 LVGL 编译成 Wasm；HTML 只做平台控制外壳。
但 iOS 原生路线全览、系统权限、蓝牙和 Apple Music 控制是平台功能，不会原样运行在
浏览器或 ESP32 上。共享 UI 不代表硬件刷新率或色彩已经像素级验收。

## 隐私与部署

- 高德 Key 只在自己部署的后端，不写入 App、网页或圆屏固件。
- 真实请求需要将起点/目的地发送给自己的网关与高德；并非完全离线私密导航。
- 不上传作者服务器配置、签名、已配对设备记录和原厂整片 Flash 备份。
- 默认网关被替换成不可用示例，避免第三方构建消耗作者的服务配额。
- 网关当前只有基础输入校验、限流和 CORS；**CORS 不是认证**。公开部署前必须自行
  配置访问控制、TLS、上游配额限制与监控，不要无保护公开高德代理。
- BLE Just Works + bonding 不提供 MITM 身份验证。设备所有权绑定、清除配对与
  换手机流程仍需进一步产品化。
