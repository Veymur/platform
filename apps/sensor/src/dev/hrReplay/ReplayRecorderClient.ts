import {
  decodeSnapshot,
  RecorderClient,
  RecorderClientError,
} from "../../recorder/RecorderClient";
import type {
  DiagnosticStatus,
  ExportResult,
  RecorderSnapshot,
  SessionResult,
} from "../../recorder/types";
import { ReplayPlayback, type ReplaySession } from "./replayPlayback";

const tickIntervalMs = 250;
const heartbeatIntervalMs = 1000;

type SnapshotSubscriber = {
  listener: (snapshot: RecorderSnapshot) => void;
  onError: (error: unknown) => void;
};

function unavailableInReplay(action: string): never {
  throw new RecorderClientError(
    "INVALID_STATE",
    `${action} is not available while replaying a recorded session.`,
  );
}

export class ReplayRecorderClient extends RecorderClient {
  private readonly playback: ReplayPlayback;
  private readonly subscribers = new Set<SnapshotSubscriber>();
  private ticker: ReturnType<typeof setInterval> | null = null;
  private lastPublishedAtMs = 0;
  private latestSnapshot: RecorderSnapshot;
  private nativeStorageReady = false;
  private stopObservingNativeStorage: (() => void) | null = null;

  constructor(session: ReplaySession, speed: number) {
    super();
    this.playback = new ReplayPlayback(session, speed, () => Date.now());
    this.playback.startSession();
    this.latestSnapshot = this.replaySnapshot();
  }

  async getSnapshot(): Promise<RecorderSnapshot> {
    this.playback.advance();
    this.publish();
    return this.latestSnapshot;
  }

  subscribe(
    listener: (snapshot: RecorderSnapshot) => void,
    onError: (error: unknown) => void,
  ): () => void {
    const subscriber = { listener, onError };
    this.subscribers.add(subscriber);
    this.observeNativeStorage();
    this.ensureTicker();
    Promise.resolve().then(() => {
      if (this.subscribers.has(subscriber)) listener(this.latestSnapshot);
    });
    return () => {
      this.subscribers.delete(subscriber);
      if (this.subscribers.size > 0) return;
      this.stopTicker();
      this.stopObservingNativeStorage?.();
      this.stopObservingNativeStorage = null;
    };
  }

  async search(): Promise<{ accepted: true }> {
    return { accepted: true };
  }

  async cancelSearch(): Promise<{ accepted: true }> {
    return { accepted: true };
  }

  async connect(): Promise<{ accepted: true }> {
    return { accepted: true };
  }

  async start(): Promise<{ sessionId: string }> {
    const sessionId = this.playback.startSession();
    this.publish();
    return { sessionId };
  }

  async pause(): Promise<SessionResult> {
    this.playback.pause();
    return this.sessionResult();
  }

  async resume(): Promise<SessionResult> {
    this.playback.resume();
    return this.sessionResult();
  }

  async finish(): Promise<{ sessionId: string; state: "completed" }> {
    const sessionId = this.playback.finish();
    this.publish();
    if (!sessionId) unavailableInReplay("Finish without a session");
    return { sessionId, state: "completed" };
  }

  async listDiagnosticCaptures(): Promise<DiagnosticStatus[]> {
    return [];
  }

  async startDiagnostic(): Promise<DiagnosticStatus> {
    return unavailableInReplay("Diagnostic capture");
  }

  async stopDiagnostic(): Promise<DiagnosticStatus> {
    return unavailableInReplay("Diagnostic capture");
  }

  async exportDiagnostic(): Promise<ExportResult> {
    return unavailableInReplay("Diagnostic export");
  }

  async deleteDiagnostic(): Promise<{ deleted: boolean }> {
    return unavailableInReplay("Diagnostic deletion");
  }

  async exportLatest(): Promise<ExportResult> {
    return unavailableInReplay("Session export");
  }

  private sessionResult(): SessionResult {
    const sessionId = this.playback.activeSessionId;
    this.publish();
    if (!sessionId) unavailableInReplay("Pause and resume without a session");
    const state = this.playback.state;
    return {
      sessionId,
      state: state === "paused" ? "paused" : "recording",
    };
  }

  private ensureTicker() {
    if (this.ticker) return;
    this.ticker = setInterval(() => {
      const changed = this.playback.advance();
      if (changed || Date.now() - this.lastPublishedAtMs >= heartbeatIntervalMs)
        this.publish();
    }, tickIntervalMs);
  }

  private stopTicker() {
    if (!this.ticker) return;
    clearInterval(this.ticker);
    this.ticker = null;
  }

  private observeNativeStorage() {
    if (this.stopObservingNativeStorage) return;
    this.stopObservingNativeStorage = super.subscribe(
      (nativeSnapshot) => {
        if (nativeSnapshot.ready === this.nativeStorageReady) return;
        this.nativeStorageReady = nativeSnapshot.ready;
        this.publish();
      },
      (error) => {
        for (const subscriber of this.subscribers) subscriber.onError(error);
      },
    );
  }

  private replaySnapshot(): RecorderSnapshot {
    return decodeSnapshot({
      ...this.playback.snapshot(),
      ready: this.nativeStorageReady,
    });
  }

  private publish() {
    let snapshot: RecorderSnapshot;
    try {
      snapshot = this.replaySnapshot();
    } catch (error) {
      for (const subscriber of this.subscribers) subscriber.onError(error);
      return;
    }
    this.latestSnapshot = snapshot;
    this.lastPublishedAtMs = Date.now();
    for (const subscriber of this.subscribers) subscriber.listener(snapshot);
  }
}
