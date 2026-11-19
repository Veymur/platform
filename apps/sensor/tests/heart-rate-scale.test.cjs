const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function load() {
  const exports = {};
  const source = fs.readFileSync(
    path.join(__dirname, "../src/ui/heartRateScale.ts"),
    "utf8",
  );
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText,
    { exports, Math, Number, Array },
  );
  return exports;
}
const m = load();
const plain = (value) => JSON.parse(JSON.stringify(value));

function assertReadableScale(scale, values, band = null) {
  assert.ok(scale, "scale exists");
  assert.equal(scale.ticks[0], scale.min);
  assert.equal(scale.ticks.at(-1), scale.max);
  assert.ok(scale.ticks.length >= 2 && scale.ticks.length <= 5);
  const step = scale.ticks[1] - scale.ticks[0];
  scale.ticks.forEach((tick, index) => {
    assert.equal(tick, scale.min + index * step);
    assert.equal(tick % step, 0);
  });
  assert.ok(scale.min >= 0);
  const shown = values.filter((value) => value !== null);
  if (band) shown.push(band.min, band.max);
  shown.forEach((value) => {
    assert.ok(value >= scale.min && value <= scale.max, `${value} is inside`);
  });
}

test("high pulse is not clipped at 150 bpm", () => {
  for (const peak of [160, 180, 200]) {
    const values = [120, 140, peak, 150, null, 130];
    const scale = m.heartRateScale(values);
    assertReadableScale(scale, values);
    assert.ok(scale.max >= peak);
  }
});

test("pulse below the former 80 bpm floor stays visible", () => {
  const values = [52, 55, 58, 61];
  const scale = m.heartRateScale(values);
  assertReadableScale(scale, values);
  assert.ok(scale.min <= 52);
});

test("crossing 150 bpm widens the scale instead of hiding the peak", () => {
  const values = [138, 144, 149, 153, 158, 162];
  const scale = m.heartRateScale(values);
  assertReadableScale(scale, values);
  assert.ok(scale.max >= 162);
});

test("a constant value or a single sample gets a minimum readable span", () => {
  for (const values of [
    [72, 72, 72, 72],
    [null, null, 72, null],
  ]) {
    const scale = m.heartRateScale(values);
    assertReadableScale(scale, values);
    assert.ok(scale.max - scale.min >= 40);
  }
});

test("gaps and non-finite values never become scale points", () => {
  const values = [null, 90, Number.NaN, null, 100, Infinity];
  const scale = m.heartRateScale(values);
  assertReadableScale(scale, [90, 100]);
  assert.ok(Number.isFinite(scale.max));
});

test("an empty window has no scale", () => {
  assert.equal(m.heartRateScale([]), null);
  assert.equal(m.heartRateScale([null, null]), null);
});

test("the selected range band always fits together with the pulse", () => {
  const band = { min: 105, max: 130 };
  const values = [60, 64, 70];
  const scale = m.heartRateScale(values, band);
  assertReadableScale(scale, values, band);
  const above = [175, 182];
  assertReadableScale(m.heartRateScale(above, band), above, band);
});

test("a scale near zero never goes negative", () => {
  const values = [3, 5];
  assertReadableScale(m.heartRateScale(values), values);
});

test("extreme spans still keep at most five evenly spaced ticks", () => {
  const values = [40, 900];
  assertReadableScale(m.heartRateScale(values), values);
});

test("the live scale holds while new samples fit and expands at once when they do not", () => {
  const first = m.stableHeartRateScale(null, [70, 80, 90]);
  assertReadableScale(first, [70, 80, 90]);
  assert.equal(m.stableHeartRateScale(first, [70, 80, 90, 96]), first);
  const expanded = m.stableHeartRateScale(first, [70, 80, 90, 112]);
  assert.notEqual(expanded, first);
  assertReadableScale(expanded, [70, 80, 90, 112]);
});

test("the live scale shrinks only after the window needs at most half of it", () => {
  const wide = m.stableHeartRateScale(null, [80, 190]);
  assert.equal(m.stableHeartRateScale(wide, [100, 150]), wide);
  const narrow = m.stableHeartRateScale(wide, [100, 104]);
  assert.notEqual(narrow, wide);
  assert.deepEqual(plain(narrow), plain(m.heartRateScale([100, 104])));
});

test("an emptied live window keeps the last scale", () => {
  const scale = m.stableHeartRateScale(null, [90, 100]);
  assert.equal(m.stableHeartRateScale(scale, [null, null]), scale);
});

test("re-evaluating the same window returns the same scale object", () => {
  const values = [150, 170, 185];
  const scale = m.stableHeartRateScale(null, values);
  assert.equal(m.stableHeartRateScale(scale, values), scale);
});
