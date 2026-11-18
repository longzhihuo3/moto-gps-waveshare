> **Language:** English · [中文](ARCHITECTURE.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Architecture and data boundaries

```text
iPhone CoreLocation (WGS84)
        │
        ├── Embedded AMapNaviKit (primary path, no gateway needed)
        │     two-step privacy compliance → riding/walking/motorcycle planning
        │     → UUID-identified queue → 5-min cache
        │     motorcycle: post-planning OSM Overpass maxspeed enrichment (3s timeout, silent skip)
        │
        └── (optional) HTTPS → Node gateway → AMap Web services
                  RouteBundle (GCJ-02)
                  ▼
shared C++ NavCore / NavApp (route progress and off-route logic runs on the iPhone)
             │
       BLE snapshot + route + map window (snapshot 0x10 carries remaining distance/duration/speed/limit)
             ▼
OPPO Watch X · Android app (heading fusion v3 + offscreen bitmap rendering)
             ▼
466×466 round display: navigation / speedometer / music pages
```

The iPhone is the authoritative end for the route and the position; the round display is not another independent route planner.
For the BLE service and frame format see `shared/protocol/ble-navigation-v1.md`.
Touch page changes and music buttons are passed back to the iPhone over BLE, and the iPhone calls Apple Music playback control.

## Embedded navigation SDK (AMapNaviKit) boundaries

- **Privacy compliance first**: `AMapSearchAPI` and `AMapNaviManagerConfig` both need
  `updatePrivacyShow` / `updatePrivacyAgree` before any manager is instantiated,
  otherwise search fails with "SDK not initialized";
- **Bundle ID binding**: AMap keys are bound per Bundle ID. SideStore re-signing
  rewrites it (the original is kept in `ALTBundleID`), producing `INVALID_USER_SCODE`
  — rebind the device's real ID in the AMap console, then delete and reinstall;
- **Callback signature trap**: SDK v11 renamed the success-callback selectors into
  chained keywords; the Swift importer splits them into different argument labels.
  Implementations must match the importer-generated signatures or they compile but
  never fire;
- **No speed-limit data**: neither planning results nor live navigation info carry a
  speed limit. Motorcycle-mode limits are enriched from OSM Overpass maxspeed
  (~600 m sampling, GCJ→WGS correction, 35 m matching, modal value to the smaller,
  3 s timeout with a silent skip); riding/walking stay limit-less;
- **Request identity**: planning sessions run through a UUID-identified queue —
  completion/timeout are matched by ID, so concurrent sessions cannot cross-talk;
  results are cached for 5 minutes (a ~55 m grid key) so repeat planning skips the SDK.

The Jinan OSM SQLite database is stored on the iPhone only. The phone queries the roads and buildings around the vehicle and then sends a
window of limited capacity to the round display; it is background scene data and does not provide offline search, nationwide route calculation or live traffic conditions.
The coordinate system is unified before display; WGS84 and GCJ-02 points must not be drawn mixed directly.

Web compiles the same C++ Presenter and LVGL into Wasm; the HTML only provides the platform control shell.
But the iOS-native route overview, system permissions, Bluetooth and Apple Music control are platform features and will not run unchanged in the
browser or on the ESP32. A shared UI does not mean the hardware refresh rate or the colours have passed pixel-level acceptance.

## Privacy and deployment

- The AMap key stays on the backend you deploy yourself and is not written into the app, the web page or the round-display firmware.
- Real requests send the origin/destination to your own gateway and to AMap; this is not fully offline, private navigation.
- Do not upload the author's server configuration, signatures, paired-device records or factory full-chip Flash backups.
- The default gateway is replaced with an unusable example, so that third-party builds do not consume the author's service quota.
- The gateway currently has only basic input validation, rate limiting and CORS; **CORS is not authentication**. Before public deployment you must configure
  access control, TLS, upstream quota limits and monitoring yourself; do not expose an unprotected AMap proxy.
- BLE Just Works + bonding does not provide MITM authentication. Device ownership binding, clearing the pairing and
  changing phones still need further productisation.
