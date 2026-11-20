const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");

function loadReplayPlayback() {
  const exports = {};
  const source = fs.readFileSync(
    path.join(__dirname, "../src/dev/hrReplay/replayPlayback.ts"),
    "utf8",
  );
  vm.runInNewContext(
    ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText,
    { exports, Date, Math, Number },
  );
  return exports;
}
const { ReplayPlayback } = loadReplayPlayback();

function playbackAt(session, speed = 1) {
  const clock = { nowMs: 10000 };
  const playback = new ReplayPlayback(session, speed, () => clock.nowMs);
  playback.startSession();
  return { playback, clock };
}

const recordedSession = {
  schema: "veymur-hr-replay-v1",
  durationMs: 20000,
  events: [
    { atMs: 1000, kind: "hr", bpm: 80 },
    { atMs: 2000, kind: "battery", percent: 77 },
    { atMs: 3000, kind: "hr", bpm: 95 },
    { atMs: 4000, kind: "disconnected" },
    { atMs: 12000, kind: "connected" },
    { atMs: 13000, kind: "hr", bpm: 101 },
    { atMs: 14000, kind: "pause" },
    { atMs: 15000, kind: "hr", bpm: 99 },
    { atMs: 16000, kind: "resume" },
  ],
};

test("heart rate becomes fresh as recorded samples arrive", () => {
  const { playback, clock } = playbackAt(recordedSession);
  assert.equal(playback.snapshot().hrStatus, "missing");
  clock.nowMs += 3500;
  assert.equal(playback.advance(), true);
  const snapshot = playback.snapshot();
  assert.equal(snapshot.hr, 95);
  assert.equal(snapshot.hrAgeMs, 500);
  assert.equal(snapshot.hrStatus, "fresh");
  assert.equal(snapshot.batteryPercent, 77);
  assert.equal(snapshot.hrCount, 2);
  assert.equal(snapshot.activeMs, 3500);
});

test("a recorded disconnection hides the pulse until the sensor reconnects", () => {
  const { playback, clock } = playbackAt(recordedSession);
  clock.nowMs += 9500;
  playback.advance();
  const disconnected = playback.snapshot();
  assert.equal(disconnected.connectedDeviceId, null);
  assert.equal(disconnected.hr, null);
  assert.equal(disconnected.hrStatus, "stale");
  clock.nowMs += 4000;
  playback.advance();
  assert.equal(playback.snapshot().hr, 101);
});

test("recorded pause stops active time and heart rate counting", () => {
  const { playback, clock } = playbackAt(recordedSession);
  clock.nowMs += 15500;
  playback.advance();
  const paused = playback.snapshot();
  assert.equal(paused.sessionState, "paused");
  assert.equal(paused.activeMs, 14000);
  assert.equal(paused.hrCount, 3);
  assert.equal(paused.hr, 99);
  clock.nowMs += 1000;
  playback.advance();
  assert.equal(playback.snapshot().sessionState, "recording");
});

test("user pause and resume control the session state", () => {
  const { playback, clock } = playbackAt(recordedSession);
  clock.nowMs += 2000;
  playback.pause();
  assert.equal(playback.state, "paused");
  clock.nowMs += 5000;
  playback.advance();
  assert.equal(playback.snapshot().activeMs, 2000);
  playback.resume();
  assert.equal(playback.state, "recording");
});

test("the timeline loops after the recorded duration", () => {
  const { playback, clock } = playbackAt(recordedSession);
  clock.nowMs += 21500;
  playback.advance();
  const looped = playback.snapshot();
  assert.equal(looped.hr, 80);
  assert.equal(looped.hrAgeMs, 500);
  assert.equal(looped.connectedDeviceId, "REPLAY");
});

test("replay speed scales the timeline and reported ages", () => {
  const { playback, clock } = playbackAt(recordedSession, 4);
  clock.nowMs += 875;
  playback.advance();
  const snapshot = playback.snapshot();
  assert.equal(snapshot.hr, 95);
  assert.equal(snapshot.hrAgeMs, 500);
  assert.equal(snapshot.activeMs, 3500);
});

test("finish ends the session and a new start replays from the beginning", () => {
  const { playback, clock } = playbackAt(recordedSession);
  clock.nowMs += 3500;
  playback.advance();
  assert.equal(playback.finish(), "replay-1");
  const finished = playback.snapshot();
  assert.equal(finished.sessionId, null);
  assert.equal(finished.hr, null);
  assert.equal(playback.startSession(), "replay-2");
  assert.equal(playback.snapshot().hrStatus, "missing");
});
