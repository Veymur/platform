const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function load() {
  const exports = {};
  const source = fs.readFileSync(
    path.join(__dirname, "../src/recorder/routeAvailability.ts"),
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
const recording = { routeEnabled: true, gpsStatus: "Waiting for coordinates" };

test("location off is reported only for a requested route", () => {
  assert.equal(m.locationOffForRequestedRoute(true, false), true);
  assert.equal(m.locationOffForRequestedRoute(true, true), false);
  assert.equal(m.locationOffForRequestedRoute(true, null), false);
  assert.equal(m.locationOffForRequestedRoute(false, false), false);
});

test("route started while location is off reads as unavailable for now, not off", () => {
  const locationOff = {
    routeEnabled: true,
    gpsStatus: "GPS disabled; heart rate recording continues",
  };
  assert.equal(m.sessionRouteUnavailable(locationOff), true);
  assert.equal(m.sessionRouteLabel(locationOff, false), "Unavailable for now");
  assert.equal(m.sessionRouteLabel(locationOff, true), "Unavailable for now");
});

test("every route loss status is unavailable for now", () => {
  for (const gpsStatus of [
    "No recent coordinates; heart rate recording continues",
    "Location permission denied; heart rate recording continues",
    "Route unavailable: provider error",
  ]) {
    assert.equal(
      m.sessionRouteLabel({ routeEnabled: true, gpsStatus }, false),
      "Unavailable for now",
      gpsStatus,
    );
  }
});

test("route label follows recording, pause and disabled route", () => {
  assert.equal(m.sessionRouteLabel(recording, false), "Recording");
  assert.equal(
    m.sessionRouteLabel({ routeEnabled: true, gpsStatus: "Paused" }, true),
    "Paused",
  );
  assert.equal(
    m.sessionRouteLabel(
      { routeEnabled: false, gpsStatus: "Route disabled" },
      false,
    ),
    "Off",
  );
  assert.equal(m.sessionRouteUnavailable(recording), false);
  assert.equal(
    m.sessionRouteUnavailable({
      routeEnabled: false,
      gpsStatus: "GPS disabled; heart rate recording continues",
    }),
    false,
  );
});
