# MOTO GPS Watch（外设端参考实现）

把 OPPO Watch 等圆形 Android 手表变成 [moto-gps-waveshare](../../README.md) 的"车把圆屏"：
iPhone 负责定位、搜索与路线规划，手表通过同一套 BLE 协议只做极简圆屏显示。
协议契约：[shared/protocol/ble-navigation-v1.md](../../shared/protocol/ble-navigation-v1.md)。

对标 ESP32 固件的外设端角色（`platforms/esp32/main/ble_nav_transport_nimble.cpp`），
iPhone App（central）**无需任何改动**即可扫描并连接本 App（按服务 UUID 扫描）。

## 当前状态

| 模块 | 状态 |
| --- | --- |
| 帧/CRC/分片重组/序号 | 已实现，编码结果与 `ble-navigation-v1.golden.txt` 黄金字节逐字节一致（Python 交叉验证） |
| GATT 外设端（广播、RX/TX、CCCD、MTU） | 已实现，真机未验证 |
| 握手/心跳/看门狗/ACK 缓存 | 已实现，真机未验证 |
| RouteGeometry staging + 原子提交 | 已实现 |
| 导航页 UI（路线、底图、转向图标、距离、进度弧） | 已实现 |
| 手势 → DeviceCommand（切页/音乐遥控） | 已实现发送 |
| 路况分段着色 / 罗盘页 / 音乐页 | 未实现 |

**已在 Windows（JDK 17.0.21 + Gradle 8.9 + AGP 8.5.2 + compileSdk 34）构建通过**：
`assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`（7.4 MB，
SHA-256 `232a102e852454affd43a2a8410b27411efbbf508d2e7291b4c0018f5765964d`）。
第一阶段验收标准：手表安装本 App → 打开 → iPhone MOTO GPS App 显示连接成功 →
"演示导航"下手表出现转向与距离。**真机 BLE 联调尚未进行**。

## 构建

需要 Android Studio（或 JDK 17 + Android SDK 35）：

```sh
cd platforms/android-watch
./gradlew :app:assembleDebug   # Android Studio 打开本目录即可
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，仅供测试）。

## 安装到 OPPO Watch

1. 手表：设置 → 关于 → 连续点击"版本号"开启开发者选项 → 打开 USB 调试（或无线调试）。
2. `adb install -r app-debug.apk`（手表用 WiFi adb：`adb connect <手表IP>:5555`）。
3. 打开 App，授予蓝牙权限，保持前台。
4. iPhone 打开 MOTO GPS App → 自动扫描 → 系统弹出配对提示点"配对"（协议要求加密链路）。
5. iPhone 点"演示导航"，手表应显示路线、转向图标与距离。

## 已知限制 / 风险

- **加密权限**：特征使用 `PERMISSION_WRITE_ENCRYPTED`，部分 Android 手表栈对
  iPhone 发起的 pairing/bonding 兼容性需要实测；若配对失败，按规范不允许降级为
  非加密（需排查 bonding 状态）。
- **续航**：常亮屏 + BLE 约数小时，未做续航优化。
- **国行 ColorOS Watch** 的第三方 APK 兼容性（圆形屏、无输入法）需实测。
- iOS 侧后台 BLE 可靠性是上游已知问题（见 `docs/KNOWN_ISSUES.md`），不因显示端
  换成手表而改善。

## 许可

沿用上游 PolyForm Noncommercial 1.0.0（见 [LICENSE.md](../../LICENSE.md)）。
基于上游协议与实现思路的社区移植，非官方支持版本。
