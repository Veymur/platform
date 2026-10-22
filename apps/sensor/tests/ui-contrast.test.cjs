const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function loadTokens() {
  const exports = {};
  const source = fs.readFileSync(
    path.join(__dirname, "../src/ui/tokens.ts"),
    "utf8",
  );
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS },
    }).outputText,
    { exports },
  );
  return exports;
}
const tokens = loadTokens();
const luminance = (hex) => {
  const channels = [1, 3, 5]
    .map((index) => parseInt(hex.slice(index, index + 2), 16) / 255)
    .map((value) =>
      value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4,
    );
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
};
const contrast = (foreground, background) => {
  const [light, dark] = [luminance(foreground), luminance(background)].sort(
    (a, b) => b - a,
  );
  return (light + 0.05) / (dark + 0.05);
};
const textPairs = [
  ["ink", "bg"],
  ["ink", "raised"],
  ["ink", "sunk"],
  ["ink-2", "bg"],
  ["ink-2", "raised"],
  ["ink-3", "bg"],
  ["ink-3", "raised"],
  ["tide", "bg"],
  ["tide", "raised"],
  ["on-solid", "solid"],
  ["ink", "tide-wash"],
  ["ink", "ember-wash"],
];
const markPairs = [
  ["edge", "raised"],
  ["edge", "bg"],
  ["tide-mark", "raised"],
  ["ink-3", "raised"],
  ["ember", "ember-wash"],
];

for (const theme of ["light", "dark"]) {
  test(`${theme} theme text used by product screens meets WCAG AA 4.5:1`, () => {
    for (const [foreground, background] of textPairs) {
      const ratio = contrast(
        tokens[theme][foreground],
        tokens[theme][background],
      );
      assert.ok(
        ratio >= 4.5,
        `${foreground} on ${background}: ${ratio.toFixed(2)}`,
      );
    }
  });
  test(`${theme} theme borders, chart marks and status marks meet 3:1`, () => {
    for (const [foreground, background] of markPairs) {
      const ratio = contrast(
        tokens[theme][foreground],
        tokens[theme][background],
      );
      assert.ok(
        ratio >= 3,
        `${foreground} on ${background}: ${ratio.toFixed(2)}`,
      );
    }
  });
}

test("pressed primary button text is AA in light theme and a reported design gap in dark theme", () => {
  assert.ok(
    contrast(tokens.light["on-solid"], tokens.light["solid-press"]) >= 4.5,
  );
  const dark = contrast(tokens.dark["on-solid"], tokens.dark["solid-press"]);
  assert.ok(dark >= 3 && dark < 4.5, dark.toFixed(2));
});
