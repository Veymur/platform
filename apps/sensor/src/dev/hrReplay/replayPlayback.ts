import type { RecorderSnapshot, SessionState } from "../../recorder/types";

export type ReplayEvent =
  | { atMs: number; kind: "hr"; bpm: number }
  | { atMs: number; kind: "pause" | "resume" | "disconnected" | "connected" }
  | { atMs: number; kind: "battery"; percent: number };

export type ReplaySession = {
  schema: "veymur-hr-replay-v1";
  durationMs: number;
  events: ReplayEvent[];
};

export const replayDeviceId = "REPLAY";
const freshHeartRateLimitMs = 5000;
const minimumLoopDurationMs = 1000;

type LastHeartRate = { bpm: number; receivedAtWallMs: number };

export class ReplayPlayback {
  private sessionId: string | null = null;
  private sessionNumber = 0;
  private sessionState: SessionState = "completed";
  private nextEventIndex = 0;
  private timelineStartedAtWallMs = 0;
  private lastAdvancedAtWallMs = 0;
  private activeMs = 0;
  private heartRateCount = 0;
  private connected = true;
  private batteryPercent: number | null = null;
  private lastHeartRate: LastHeartRate | null = null;
  private lastCommitUtcMs: number | null = null;
  private revision = 0;
  private readonly loopDurationMs: number;

  constructor(
    private readonly session: ReplaySession,
    private readonly speed: number,
    private readonly wallNowMs: () => number,
  ) {
    const lastEventAtMs = session.events.at(-1)?.atMs ?? 0;
    this.loopDurationMs = Math.max(
      minimumLoopDurationMs,
      session.durationMs,
      lastEventAtMs + 1,
    );
  }

  get activeSessionId(): string | null {
    return this.sessionId;
  }

  get state(): SessionState {
    return this.sessionState;
  }

  startSession(): string {
    const now = this.wallNowMs();
    this.sessionNumber += 1;
    this.sessionId = `replay-${this.sessionNumber}`;
    this.sessionState = "recording";
    this.nextEventIndex = 0;
    this.timelineStartedAtWallMs = now;
    this.lastAdvancedAtWallMs = now;
    this.activeMs = 0;
    this.heartRateCount = 0;
    this.connected = true;
    this.batteryPercent = null;
    this.lastHeartRate = null;
    this.lastCommitUtcMs = null;
    return this.sessionId;
  }

  pause() {
    this.advance();
    if (this.sessionState === "recording") this.sessionState = "paused";
  }

  resume() {
    this.advance();
    if (this.sessionState === "paused") this.sessionState = "recording";
  }

  finish(): string | null {
    this.advance();
    const finishedSessionId = this.sessionId;
    this.sessionId = null;
    this.sessionState = "completed";
    return finishedSessionId;
  }

  advance(): boolean {
    if (!this.sessionId) return false;
    const now = this.wallNowMs();
    let changed = false;
    while (true) {
      const nextEvent = this.session.events[this.nextEventIndex];
      const loopEndsAtWallMs =
        this.timelineStartedAtWallMs + this.loopDurationMs / this.speed;
      const nextEventAtWallMs = nextEvent
        ? this.timelineStartedAtWallMs + nextEvent.atMs / this.speed
        : loopEndsAtWallMs;
      if (nextEventAtWallMs > now) break;
      this.accumulateActiveTime(nextEventAtWallMs);
      if (nextEvent) {
        this.apply(nextEvent, nextEventAtWallMs);
        this.nextEventIndex += 1;
      } else {
        this.timelineStartedAtWallMs = loopEndsAtWallMs;
        this.nextEventIndex = 0;
      }
      changed = true;
    }
    this.accumulateActiveTime(now);
    return changed;
  }

  snapshot(): RecorderSnapshot {
    const now = this.wallNowMs();
    const inSession = this.sessionId !== null;
    const heartRateAgeMs =
      inSession && this.lastHeartRate
        ? Math.max(
            0,
            Math.round((now - this.lastHeartRate.receivedAtWallMs) * this.speed),
          )
        : null;
    const fresh =
      this.connected &&
      this.lastHeartRate !== null &&
      heartRateAgeMs !== null &&
      heartRateAgeMs <= freshHeartRateLimitMs;
    this.revision += 1;
    return {
      diagnostic: {
        captureId: null,
        state: "off",
        savedEvents: 0,
        savedProcessing: 0,
        knownLostEvents: 0,
        knownLostProcessing: 0,
        unknownTail: false,
        checkpointPersisted: false,
        lastErrorCode: null,
      },
      selectedDeviceId: replayDeviceId,
      discoveryActive: false,
      alertsStatus: "enabled",
      storageWarning: null,
      revision: this.revision,
      ready: true,
      status: inSession ? "Replaying a recorded session" : "Replay ready",
      sessionId: this.sessionId,
      sessionState: this.sessionState,
      connectedDeviceId: this.connected ? replayDeviceId : null,
      batteryPercent: this.connected ? this.batteryPercent : null,
      hr: fresh ? this.lastHeartRate!.bpm : null,
      hrAgeMs: heartRateAgeMs,
      hrStatus: fresh ? "fresh" : this.lastHeartRate ? "stale" : "missing",
      devices: [],
      hrCount: this.heartRateCount,
      gpsCount: 0,
      activeMs: Math.round(this.activeMs),
      lastCommitUtcMs: this.lastCommitUtcMs,
      gpsStatus: "Route off",
      gpsDistanceM: null,
      bluetoothEnabled: true,
      routeEnabled: false,
      recoveredCount: 0,
      errorCode: null,
    };
  }

  private accumulateActiveTime(untilWallMs: number) {
    if (untilWallMs <= this.lastAdvancedAtWallMs) return;
    if (this.sessionState === "recording")
      this.activeMs += (untilWallMs - this.lastAdvancedAtWallMs) * this.speed;
    this.lastAdvancedAtWallMs = untilWallMs;
  }

  private apply(event: ReplayEvent, atWallMs: number) {
    if (event.kind === "hr") {
      if (!this.connected) return;
      this.lastHeartRate = { bpm: event.bpm, receivedAtWallMs: atWallMs };
      if (this.sessionState === "recording") {
        this.heartRateCount += 1;
        this.lastCommitUtcMs = Date.now();
      }
    } else if (event.kind === "pause") {
      if (this.sessionState === "recording") this.sessionState = "paused";
    } else if (event.kind === "resume") {
      if (this.sessionState === "paused") this.sessionState = "recording";
    } else if (event.kind === "disconnected") {
      this.connected = false;
    } else if (event.kind === "connected") {
      this.connected = true;
    } else if (event.kind === "battery") {
      this.batteryPercent = event.percent;
    }
  }
}
