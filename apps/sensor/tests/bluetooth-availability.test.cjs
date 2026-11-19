const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function load() {
  const exports = {};
  const source = fs.readFileSync(
    path.join(__dirname, "../src/recorder/bluetoothAvailability.ts"),
    "utf8",
  );
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText,
    { exports },
  );
  return exports;
}
const m = load();
const idle = {
  availability: "on",
  recorderReady: true,
  alreadySearched: false,
  sensorConnected: false,
  sensorSelected: false,
  discoveryActive: false,
};

test("adapter off, missing permission and unknown state stay distinct", () => {
  assert.equal(m.bluetoothAvailability(false, true), "off");
  assert.equal(m.bluetoothAvailability(false, false), "off");
  assert.equal(m.bluetoothAvailability(false, null), "off");
  assert.equal(m.bluetoothAvailability(true, false), "permission_denied");
  assert.equal(m.bluetoothAvailability(null, false), "permission_denied");
  assert.equal(m.bluetoothAvailability(true, null), "checking");
  assert.equal(m.bluetoothAvailability(undefined, null), "checking");
  assert.equal(m.bluetoothAvailability(true, true), "on");
  assert.equal(m.bluetoothAvailability(null, true), "unknown");
});

test("automatic search never starts while Bluetooth is off or not allowed", () => {
  for (const availability of ["off", "permission_denied", "checking"])
    assert.equal(
      m.automaticSensorSearchAllowed({ ...idle, availability }),
      false,
      availability,
    );
  assert.equal(m.automaticSensorSearchAllowed(idle), true);
  assert.equal(
    m.automaticSensorSearchAllowed({ ...idle, availability: "unknown" }),
    true,
  );
});

test("automatic search runs at most once and never over an active sensor or search", () => {
  for (const blocker of [
    "alreadySearched",
    "sensorConnected",
    "sensorSelected",
    "discoveryActive",
  ])
    assert.equal(
      m.automaticSensorSearchAllowed({ ...idle, [blocker]: true }),
      false,
      blocker,
    );
  assert.equal(
    m.automaticSensorSearchAllowed({ ...idle, recorderReady: false }),
    false,
  );
});
