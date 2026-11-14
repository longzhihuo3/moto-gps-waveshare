import { setDefaultResultOrder } from "node:dns";
import { readFile } from "node:fs/promises";
import { setDefaultAutoSelectFamily } from "node:net";

import { createAmapProvider } from "./amap-provider.js";
import { createAmapPlacesProvider } from "./amap-places.js";
import { createAmapCitiesProvider } from "./amap-cities.js";
import { createMapTileProvider } from "./map-tiles.js";
import { transformAmapRouteOptionsV2, transformAmapRouteV2 } from "./amap-transformer.js";
import { createGateway } from "./gateway.js";

// restapi.amap.com publishes AAAA records, and an overseas VPS often has no
// IPv6 route and over 250 ms of RTT to AMap. Node's happy-eyeballs then aborts
// the IPv4 attempt after 250 ms, IPv6 fails at once, and every AMap fetch ends
// in ETIMEDOUT. Plain IPv4-first connects let each request use its own deadline.
setDefaultAutoSelectFamily(false);
setDefaultResultOrder("ipv4first");

async function createFixtureProvider() {
  const fixtureUrl = new URL("../fixtures/amap-route-v2.json", import.meta.url);
  const fixture = JSON.parse(await readFile(fixtureUrl, "utf8"));
  return {
    async planRoute() {
      return transformAmapRouteV2(fixture, { generatedAtMs: Date.now() });
    },
    async planRouteOptions() {
      return transformAmapRouteOptionsV2(fixture, { generatedAtMs: Date.now() });
    },
    async searchPlaces(query) {
      return {
        protocol_version: 1,
        query: query.keywords,
        places: [],
      };
    },
  };
}

function createDisabledProvider() {
  const unavailable = () => {
    const error = new Error("AMap live navigation is not configured");
    error.code = "SERVER_MISCONFIGURED";
    error.retryable = false;
    throw error;
  };
  return {
    async planRoute() {
      unavailable();
    },
    async planRouteOptions() {
      unavailable();
    },
    async searchPlaces() {
      unavailable();
    },
  };
}

async function main() {
  const providerMode = process.env.MOTO_PROVIDER ?? "fixture";
  let provider;
  if (providerMode === "amap") {
    const key = process.env.AMAP_WEB_SERVICE_KEY;
    if (typeof key !== "string" || key.trim() === "") {
      throw new Error("AMAP_WEB_SERVICE_KEY is required when MOTO_PROVIDER=amap");
    }
    // MOTO_TRAVEL_MODE=riding 时强制走高德骑行路径规划（自行车/两轮），
    // App 端无需改动，ETA 自动变为骑行时间（协议 v1 route_mode 只有 driving）。
    const travelMode = process.env.MOTO_TRAVEL_MODE === "riding" ? "riding" : "driving";
    console.log(`AMap travel mode: ${travelMode}`);
    provider = {
      ...createAmapProvider({ key, travelMode }),
      ...createAmapPlacesProvider({ key }),
      ...createAmapCitiesProvider({ key }),
    };
  } else if (providerMode === "fixture") {
    provider = await createFixtureProvider();
  } else if (providerMode === "disabled") {
    provider = createDisabledProvider();
  } else {
    throw new Error(`unsupported MOTO_PROVIDER: ${providerMode}`);
  }
  const port = Number(process.env.PORT ?? 8787);
  const host = process.env.HOST ?? "0.0.0.0";
  const allowedOrigin = process.env.WEB_ORIGIN ?? "*";
  const mapProvider = createMapTileProvider({
    url: process.env.MOTO_MAP_PMTILES_URL ?? (providerMode === "amap" ? "auto" : "disabled"),
    cacheDirectory: process.env.MOTO_MAP_CACHE_DIR,
    maximumCacheBytes: process.env.MOTO_MAP_CACHE_MAX_BYTES === undefined
      ? undefined : Number(process.env.MOTO_MAP_CACHE_MAX_BYTES),
  });
  await mapProvider?.initialize();
  const server = createGateway({ provider, mapProvider, allowedOrigin, providerMode });

  server.listen(port, host, () => {
    console.log(`MOTO GPS gateway listening on http://${host}:${port} (${providerMode})`);
  });

  const close = () => { mapProvider?.close(); server.close(); };
  process.once("SIGINT", close);
  process.once("SIGTERM", close);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
