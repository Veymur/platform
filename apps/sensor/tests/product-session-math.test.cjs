const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function load() {
  const exports = {};
  const source = fs.readFileSync(
    path.join(__dirname, "../src/product/sessionMath.ts"),
    "utf8",
  );
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText,
    { exports, Intl, Date, JSON, Math, Number, String, Array, Set, Infinity },
  );
  return exports;
}
const m = load();
const plain = (value) => JSON.parse(JSON.stringify(value));
const hr = (activeMs, bpm, segment = "s1", extra = {}) => ({
  segment_id: segment,
  payload_json: JSON.stringify({
    bpm,
    contact: true,
    contact_supported: true,
    active_ms: activeMs,
    observation_metadata: { quality_flags: ["valid_format"] },
    ...extra,
  }),
});

test("user bounds become three non-overlapping ranges with an inclusive upper number", () => {
  const ranges = m.rangesFromBounds({ from: 105, to: 130 });
  assert.deepEqual(plain(ranges), [
    {
      id: "below",
      label: "Below 105 bpm",
      lower_bpm_inclusive: null,
      upper_bpm_exclusive: 105,
    },
    {
      id: "within",
      label: "105–130 bpm",
      lower_bpm_inclusive: 105,
      upper_bpm_exclusive: 131,
    },
    {
      id: "above",
      label: "Above 130 bpm",
      lower_bpm_inclusive: 131,
      upper_bpm_exclusive: null,
    },
  ]);
  assert.deepEqual(plain(m.boundsFromRanges(ranges)), { from: 105, to: 130 });
  assert.equal(m.rangeContaining(130, ranges).id, "within");
  assert.equal(m.rangeContaining(131, ranges).id, "above");
  assert.equal(m.rangeContaining(104, ranges).id, "below");
  assert.equal(
    m.boundsFromRanges([
      {
        id: "custom",
        label: "Custom",
        lower_bpm_inclusive: 90,
        upper_bpm_exclusive: 110,
      },
    ]),
    null,
  );
  assert.equal(m.rangeContaining(100, null), null);
});

test("range input accepts only whole numbers inside the documented limits", () => {
  assert.deepEqual(plain(m.parseRangeBounds(" 30 ", "230")), {
    bounds: { from: 30, to: 230 },
  });
  for (const [from, to] of [
    ["", "120"],
    ["29", "120"],
    ["100", "231"],
    ["120", "120"],
    ["130", "120"],
    ["100.5", "120"],
    ["-1", "120"],
    ["1e2", "120"],
  ]) {
    assert.ok("error" in m.parseRangeBounds(from, to), `${from}-${to}`);
  }
});

test("defaults keep alerts off and never prescribe ranges", () => {
  const settings = m.defaultSettings("Europe/Kyiv");
  assert.equal(settings.alerts.enabled, false);
  assert.equal(settings.hr_ranges_id, null);
  assert.equal(settings.route_enabled, false);
  assert.equal(settings.timezone_id, "Europe/Kyiv");
  assert.deepEqual(plain(m.alertDelivery("silent")), {
    sound: false,
    vibration: false,
  });
  assert.equal(m.alertModeOf(m.alertDelivery("vibration")), "vibration");
  assert.equal(m.alertModeOf(m.alertDelivery("sound")), "sound");
});

test("unusable HR records are kept as interval boundaries without a value", () => {
  assert.deepEqual(plain(m.hrSampleFromRecord(hr(1000, 80))), {
    activeMs: 1000,
    bpm: 80,
    segmentId: "s1",
  });
  assert.equal(
    m.hrSampleFromRecord(hr(1000, 80, "s1", { contact: false })).bpm,
    null,
  );
  assert.equal(
    m.hrSampleFromRecord(
      hr(1000, 80, "s1", {
        observation_metadata: {
          quality_flags: ["valid_format", "suspect_signal"],
        },
      }),
    ).bpm,
    null,
  );
  assert.equal(
    m.hrSampleFromRecord(
      hr(1000, 80, "s1", { observation_metadata: { quality_flags: [] } }),
    ).bpm,
    null,
  );
  assert.equal(m.hrSampleFromRecord(hr(1000, 0)).bpm, null);
  assert.equal(
    m.hrSampleFromRecord(
      hr(1000, 80, "s1", { contact: false, contact_supported: false }),
    ).bpm,
    80,
  );
  assert.equal(
    m.hrSampleFromRecord({ segment_id: "s1", payload_json: "not json" }),
    null,
  );
  assert.equal(
    m.hrSampleFromRecord({
      segment_id: "s1",
      payload_json: JSON.stringify({ bpm: 80 }),
    }),
    null,
  );
});

test("session chart leaves gaps instead of stretching the last value and marks pauses", () => {
  const samples = [
    m.hrSampleFromRecord(hr(0, 100)),
    m.hrSampleFromRecord(hr(5000, 110)),
    m.hrSampleFromRecord(hr(30000, 120, "s2")),
  ];
  const columns = plain(m.sessionChartColumns(samples, 40000, 5000));
  assert.equal(columns.length, 8);
  assert.deepEqual(
    columns.map((c) => c.bpm),
    [100, 110, null, null, null, null, 120, null],
  );
  assert.deepEqual(
    columns.map((c) => c.pauseBefore),
    [false, false, false, false, false, false, true, false],
  );
  assert.deepEqual(plain(m.sessionChartColumns([], 0, 5000)), []);
  assert.deepEqual(
    plain(m.sessionChartColumns(samples, 40000, 5000, 4)).map((c) => c.bpm),
    [105, null, null, 120],
  );
});

test("an unusable sample truncates the previous interval and missing pulse is never zero", () => {
  const samples = [
    m.hrSampleFromRecord(hr(0, 100)),
    m.hrSampleFromRecord(hr(1000, 90, "s1", { contact: false })),
  ];
  const columns = plain(m.sessionChartColumns(samples, 10000, 5000));
  assert.deepEqual(
    columns.map((c) => c.bpm),
    [100, null],
  );
  assert.ok(columns.every((c) => c.bpm !== 0));
});

test("live chart shows only fresh readings in its window", () => {
  let samples = [];
  samples = m.appendLiveSample(samples, { atMs: 1000, bpm: 80 });
  samples = m.appendLiveSample(samples, { atMs: 1000, bpm: 81 });
  samples = m.appendLiveSample(samples, { atMs: 3000, bpm: null });
  samples = m.appendLiveSample(samples, { atMs: 200000, bpm: 90 });
  assert.deepEqual(plain(samples), [{ atMs: 200000, bpm: 90 }]);
  const columns = m.liveChartColumns(
    [
      { atMs: 1000, bpm: 80 },
      { atMs: 3000, bpm: null },
    ],
    4000,
    4000,
    2,
  );
  assert.deepEqual(
    plain(columns).map((c) => c.bpm),
    [80, null],
  );
  assert.deepEqual(plain(m.describeColumns(columns)), {
    min: 80,
    max: 80,
    gaps: 1,
  });
});

test("formatting keeps unknown values as text and uses the selected units", () => {
  assert.equal(m.formatClock(3725000), "01:02:05");
  assert.equal(m.formatDuration(45000), "45 s");
  assert.equal(m.formatDuration(38 * 60000), "38 min");
  assert.equal(m.formatDuration(65 * 60000), "1 h 05 min");
  assert.equal(m.formatDistance(3200, "metric"), "3.20 km");
  assert.equal(m.formatDistance(1609.344, "imperial"), "1.00 mi");
  assert.equal(m.formatPercent(0.914), "91%");
  assert.equal(m.formatAge(null), "No reading yet");
  assert.equal(m.formatAge(500), "Updated just now");
  assert.equal(m.formatAge(6400), "Updated 6 s ago");
  assert.equal(
    m.withoutErrorCode("PERMISSION_DENIED: Grant Bluetooth."),
    "Grant Bluetooth.",
  );
  assert.equal(m.isValidTimeZone("Europe/Kyiv"), true);
  assert.equal(m.isValidTimeZone("Mars/Base"), false);
  assert.equal(m.isValidTimeZone(" "), false);
  assert.match(m.formatDateTime(Date.UTC(2026, 9, 19, 10, 30), "UTC"), /2026/);
  assert.match(
    m.formatDateTime(Date.UTC(2026, 9, 19, 10, 30), "Bad/Zone"),
    /UTC$/,
  );
});

test("provisional aggregate parameters match the documented validation set", () => {
  assert.deepEqual(plain(m.provisionalAggregateParameters), {
    hr_stale_after_ms: 5000,
    gps_max_accuracy_m: 30,
    gps_max_gap_ms: 5000,
    gps_max_speed_m_s: 20,
    map_hr_window_ms: 2000,
  });
});
