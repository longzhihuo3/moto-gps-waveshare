import assert from "node:assert/strict";
import test from "node:test";

import {
  buildOverpassQuery,
  fetchRouteSpeedLimitKph,
  parseMaxspeedValues,
  samplePolylineForSpeedLimit,
  selectRouteSpeedLimit,
} from "../src/osm-speed-limit.js";

const polyline = [
  { longitude_deg: 117.0, latitude_deg: 36.6 },
  { longitude_deg: 117.01, latitude_deg: 36.6 },
];

test("samples a long polyline at a bounded interval", () => {
  const samples = samplePolylineForSpeedLimit(polyline);
  assert.ok(samples.length >= 2);
  for (const [lat, lon] of samples) {
    assert.ok(Number.isFinite(lat) && Number.isFinite(lon));
  }
});

test("returns no samples for a degenerate polyline", () => {
  assert.deepEqual(samplePolylineForSpeedLimit([]), []);
  assert.deepEqual(samplePolylineForSpeedLimit([polyline[0]]), []);
});

test("builds an Overpass query with one clause per sample", () => {
  const query = buildOverpassQuery([
    [36.6, 117.0],
    [36.61, 117.01],
  ]);
  assert.match(query, /^\[out:json\]\[timeout:6\];\(/);
  assert.equal(query.match(/way\(around:35/g)?.length, 2);
  assert.match(query, /\);out tags;$/);
});

test("parses numeric maxspeed values and rejects the rest", () => {
  const values = parseMaxspeedValues([
    { tags: { maxspeed: "50" } },
    { tags: { maxspeed: "80" } },
    { tags: { maxspeed: "variable" } },
    { tags: { maxspeed: "50 mph" } },
    { tags: {} },
    { tags: { maxspeed: "5" } },
    {},
  ]);
  assert.deepEqual(values, [50, 80]);
});

test("selects the modal limit and prefers the lower value on ties", () => {
  assert.equal(selectRouteSpeedLimit([50, 50, 60]), 50);
  assert.equal(selectRouteSpeedLimit([60, 50]), 50);
  assert.equal(selectRouteSpeedLimit([]), null);
});

test("fetch returns null when Overpass is unreachable", async () => {
  const failingFetch = async () => {
    throw new Error("network down");
  };
  assert.equal(
    await fetchRouteSpeedLimitKph(polyline, { fetchImpl: failingFetch, timeoutMs: 200 }),
    null,
  );
});

test("fetch attaches a real limit from a successful Overpass response", async () => {
  const fetchImpl = async () => ({
    ok: true,
    json: async () => ({
      elements: [
        { type: "way", tags: { maxspeed: "60" } },
        { type: "way", tags: { maxspeed: "60" } },
        { type: "way", tags: { maxspeed: "40" } },
      ],
    }),
  });
  assert.equal(
    await fetchRouteSpeedLimitKph(polyline, { fetchImpl, timeoutMs: 200 }),
    60,
  );
});
