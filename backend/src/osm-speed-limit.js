import { gcj02ToWgs84 } from "./coordinates.js";

// OpenStreetMap Overpass API：为路线附加真实道路限速（maxspeed 标签）。
// 高德开放 API 不提供限速数据，OSM 是唯一可编程获取的真实来源。
// 全部函数 best-effort：任何失败返回 null，绝不影响路线主流程。

// 端点顺序按国内网络实测连通性排列：osm.ch 可直连；其余为海外备用，
// 未来网络环境变化或换部署位置时自动逐个尝试。
const OVERPASS_ENDPOINTS = [
  "https://overpass.osm.ch/api/interpreter",
  "https://overpass-api.de/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
];

const SAMPLE_INTERVAL_M = 600;
const MAX_SAMPLES = 24;
const MATCH_RADIUS_M = 35;
const EARTH_RADIUS_M = 6371000;

function toRadians(deg) {
  return (deg * Math.PI) / 180;
}

function distanceM(a, b) {
  const dLat = toRadians(b.latitude_deg - a.latitude_deg);
  const dLon = toRadians(b.longitude_deg - a.longitude_deg);
  const lat1 = toRadians(a.latitude_deg);
  const lat2 = toRadians(b.latitude_deg);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
}

/**
 * 沿折线每 ~600m 取一个采样点（GCJ-02 → WGS-84，OSM 用 WGS-84 坐标，
 * 不纠偏会偏移 300~600m 匹配到错误道路）。
 */
export function samplePolylineForSpeedLimit(polyline) {
  if (!Array.isArray(polyline) || polyline.length < 2) return [];
  const samples = [polyline[0]];
  let accumulatedM = 0;
  let nextThresholdM = SAMPLE_INTERVAL_M;
  for (let i = 1; i < polyline.length; i += 1) {
    accumulatedM += distanceM(polyline[i - 1], polyline[i]);
    while (accumulatedM >= nextThresholdM && samples.length < MAX_SAMPLES) {
      samples.push(polyline[i]);
      nextThresholdM += SAMPLE_INTERVAL_M;
    }
    if (samples.length >= MAX_SAMPLES) break;
  }
  if (samples.length < MAX_SAMPLES) samples.push(polyline[polyline.length - 1]);
  return samples
    .slice(0, MAX_SAMPLES)
    .map((point) => gcj02ToWgs84(point))
    .map((point) => [point.latitude_deg, point.longitude_deg]);
}

export function buildOverpassQuery(samples) {
  const clauses = samples
    .map(
      ([lat, lon]) =>
        `way(around:${MATCH_RADIUS_M},${lat.toFixed(5)},${lon.toFixed(5)})["maxspeed"];`,
    )
    .join("");
  return `[out:json][timeout:6];(${clauses});out tags;`;
}

/** 解析 Overpass 返回的 maxspeed 标签（kph 整数），丢弃非数值/越界。 */
export function parseMaxspeedValues(elements) {
  if (!Array.isArray(elements)) return [];
  const values = [];
  for (const element of elements) {
    const raw = element?.tags?.maxspeed;
    if (typeof raw !== "string") continue;
    const match = raw.match(/^(\d{2,3})$/);
    if (!match) continue; // "variable"/"signals"/"50 mph" 等一律不采信（仅接受纯 kph 数字）
    const value = Number.parseInt(match[1], 10);
    if (value >= 10 && value <= 150) values.push(value);
  }
  return values;
}

/** 取众数（同频取较小值，偏保守）。 */
export function selectRouteSpeedLimit(values) {
  if (values.length === 0) return null;
  const counts = new Map();
  for (const value of values) {
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  let best = null;
  let bestCount = 0;
  for (const [value, count] of counts) {
    if (count > bestCount || (count === bestCount && value < best)) {
      best = value;
      bestCount = count;
    }
  }
  return best;
}

export async function fetchRouteSpeedLimitKph(
  polyline,
  { fetchImpl = globalThis.fetch, timeoutMs = 4500 } = {},
) {
  if (typeof fetchImpl !== "function") return null;
  try {
    const samples = samplePolylineForSpeedLimit(polyline);
    if (samples.length === 0) return null;
    const body = new URLSearchParams({ data: buildOverpassQuery(samples) });

    const attempt = async (endpoint) => {
      const response = await fetchImpl(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: body.toString(),
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!response.ok) throw new Error(`overpass http ${response.status}`);
      const payload = await response.json();
      return selectRouteSpeedLimit(parseMaxspeedValues(payload?.elements));
    };

    const result = await Promise.any(OVERPASS_ENDPOINTS.map(attempt));
    return typeof result === "number" && result > 0 ? result : null;
  } catch {
    return null; // 限速是增强能力，失败静默（路线照常，表上不显示限速牌）
  }
}
