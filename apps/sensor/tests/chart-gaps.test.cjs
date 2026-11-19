const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function load() {
  const exports = {};
  const source = fs.readFileSync(
    path.join(__dirname, "../src/ui/chartGaps.ts"),
    "utf8",
  );
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText,
    { exports, Math },
  );
  return exports;
}
const m = load();
const plain = (value) => JSON.parse(JSON.stringify(value));

test("each run of missing values is one gap", () => {
  assert.deepEqual(
    plain(m.chartGapRuns([80, null, null, 82, null, 84, null])),
    [
      { from: 1, to: 2 },
      { from: 4, to: 4 },
      { from: 6, to: 6 },
    ],
  );
  assert.deepEqual(plain(m.chartGapRuns([80, 81, 82])), []);
});

test("the uncovered start of a window is not reported as a gap", () => {
  const values = [null, null, null, 90, 91, null, 92];
  assert.deepEqual(plain(m.chartGapRuns(values, 3)), [{ from: 5, to: 5 }]);
  assert.deepEqual(plain(m.chartGapRuns(values)), [
    { from: 0, to: 2 },
    { from: 5, to: 5 },
  ]);
  assert.deepEqual(plain(m.chartGapRuns([null, null], 2)), []);
});
