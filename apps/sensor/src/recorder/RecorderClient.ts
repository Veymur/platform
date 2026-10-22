import { nativeRecorder, getModuleInfo } from "../../modules/veymur-recorder";
import type {
  NativeRecorderModule,
  RecorderSnapshot,
  RecorderErrorCode,
  DiagnosticStatus,
  ExportResult,
  SessionExportFormat,
  LocalData,
  SystemInsets,
  NetworkStatus,
} from "../../modules/veymur-recorder";
export type * from "../../modules/veymur-recorder";

const codes = new Set([
  "UNSUPPORTED_PLATFORM",
  "INCOMPATIBLE_BINARY",
  "SERVICE_UNAVAILABLE",
  "NOT_READY",
  "NO_ACTIVITY",
  "PERMISSION_DENIED",
  "INVALID_STATE",
  "INVALID_ARGUMENT",
  "NOT_CONNECTED",
  "CONNECTION_ERROR",
  "DISCOVERY_ERROR",
  "STORAGE_ERROR",
  "NO_SESSIONS",
  "DIAGNOSTIC_ERROR",
]);
export class RecorderClientError extends Error {
  constructor(
    public readonly code: RecorderErrorCode,
    message: string,
    cause?: unknown,
  ) {
    super(message, { cause });
    this.name = "RecorderClientError";
  }
}
export function recorderError(error: unknown): RecorderClientError {
  if (error instanceof RecorderClientError) return error;
  const value =
    error !== null && typeof error === "object"
      ? (error as { code?: unknown; message?: unknown })
      : {};
  const code =
    typeof value.code === "string" && codes.has(value.code)
      ? (value.code as RecorderErrorCode)
      : "SERVICE_UNAVAILABLE";
  return new RecorderClientError(
    code,
    typeof value.message === "string"
      ? value.message
      : "Recorder unavailable; retry.",
    error,
  );
}
const nonnegative = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
const nullableString = (value: unknown) =>
  value === null || typeof value === "string";
const nullableNumber = (value: unknown) => value === null || nonnegative(value);

export function decodeDiagnosticStatus(value: unknown): DiagnosticStatus {
  const fail = () => {
    throw new RecorderClientError(
      "INCOMPATIBLE_BINARY",
      "Invalid diagnostic status; rebuild the app.",
    );
  };
  const diagnostic = value as Record<string, unknown> | null;
  if (
    !diagnostic ||
    typeof diagnostic !== "object" ||
    !nullableString(diagnostic.captureId) ||
    typeof diagnostic.state !== "string" ||
    ![
      "off",
      "starting",
      "active",
      "stopping",
      "complete",
      "incomplete",
      "interrupted",
    ].includes(diagnostic.state) ||
    ![
      "savedEvents",
      "savedProcessing",
      "knownLostEvents",
      "knownLostProcessing",
    ].every((key) => nonnegative(diagnostic[key])) ||
    typeof diagnostic.unknownTail !== "boolean" ||
    typeof diagnostic.checkpointPersisted !== "boolean" ||
    !nullableString(diagnostic.lastErrorCode)
  )
    return fail();
  return {
    captureId: diagnostic.captureId,
    state: diagnostic.state,
    savedEvents: diagnostic.savedEvents,
    savedProcessing: diagnostic.savedProcessing,
    knownLostEvents: diagnostic.knownLostEvents,
    knownLostProcessing: diagnostic.knownLostProcessing,
    unknownTail: diagnostic.unknownTail,
    checkpointPersisted: diagnostic.checkpointPersisted,
    lastErrorCode: diagnostic.lastErrorCode,
  } as DiagnosticStatus;
}

/** Validate the complete DTO, including nulls: absence is never a zero measurement. */
export function decodeSnapshot(value: unknown): RecorderSnapshot {
  const fail = () => {
    throw new RecorderClientError(
      "INCOMPATIBLE_BINARY",
      "Incompatible recorder snapshot; rebuild the native app.",
    );
  };
  if (value === null || typeof value !== "object") return fail();
  const v = value as Record<string, unknown>;
  const diagnostic = decodeDiagnosticStatus(v.diagnostic);
  if (
    !nullableString(v.selectedDeviceId) ||
    typeof v.discoveryActive !== "boolean" ||
    typeof v.alertsStatus !== "string" ||
    ![
      "disabled",
      "enabled",
      "no_selected_ranges",
      "notification_permission_denied",
      "notifications_disabled",
    ].includes(v.alertsStatus) ||
    !(v.storageWarning === null || v.storageWarning === "low_space") ||
    !nonnegative(v.revision) ||
    typeof v.ready !== "boolean" ||
    typeof v.status !== "string" ||
    !nullableString(v.sessionId) ||
    typeof v.sessionState !== "string" ||
    ![
      "preparation",
      "recording",
      "paused",
      "finishing",
      "completed",
      "interrupted",
    ].includes(v.sessionState) ||
    !nullableString(v.connectedDeviceId) ||
    !nullableNumber(v.batteryPercent) ||
    (typeof v.batteryPercent === "number" && v.batteryPercent > 100) ||
    !nullableNumber(v.hr) ||
    (typeof v.hr === "number" && v.hr === 0) ||
    !nullableNumber(v.hrAgeMs) ||
    typeof v.hrStatus !== "string" ||
    !["fresh", "stale", "missing"].includes(v.hrStatus) ||
    (v.hrStatus === "fresh"
      ? v.hr === null ||
        v.hrAgeMs === null ||
        Number(v.hrAgeMs) > 5000 ||
        v.connectedDeviceId === null
      : v.hr !== null) ||
    !nonnegative(v.hrCount) ||
    !nonnegative(v.gpsCount) ||
    !nonnegative(v.activeMs) ||
    !nullableNumber(v.lastCommitUtcMs) ||
    typeof v.gpsStatus !== "string" ||
    typeof v.routeEnabled !== "boolean" ||
    !nonnegative(v.recoveredCount) ||
    !(
      v.errorCode === null ||
      (typeof v.errorCode === "string" && codes.has(v.errorCode))
    ) ||
    !Array.isArray(v.devices) ||
    !v.devices.every(
      (d) =>
        d !== null &&
        typeof d === "object" &&
        typeof d.id === "string" &&
        typeof d.name === "string" &&
        typeof d.address === "string",
    )
  )
    return fail();
  // Copy only contract fields; never expose future native internals through this boundary.
  return {
    diagnostic,
    selectedDeviceId: v.selectedDeviceId,
    discoveryActive: v.discoveryActive,
    alertsStatus: v.alertsStatus,
    storageWarning: v.storageWarning,
    revision: v.revision,
    ready: v.ready,
    status: v.status,
    sessionId: v.sessionId,
    sessionState: v.sessionState,
    connectedDeviceId: v.connectedDeviceId,
    batteryPercent: v.batteryPercent,
    hr: v.hr,
    hrAgeMs: v.hrAgeMs,
    hrStatus: v.hrStatus,
    devices: v.devices.map((d) => ({
      id: d.id,
      name: d.name,
      address: d.address,
    })),
    hrCount: v.hrCount,
    gpsCount: v.gpsCount,
    activeMs: v.activeMs,
    lastCommitUtcMs: v.lastCommitUtcMs,
    gpsStatus: v.gpsStatus,
    routeEnabled: v.routeEnabled,
    recoveredCount: v.recoveredCount,
    errorCode: v.errorCode,
  } as RecorderSnapshot;
}

/** UI adapter only. The native writer and foreground service own recording and commit acknowledgment. */
export class RecorderClient {
  constructor(
    private readonly load: () => NativeRecorderModule = nativeRecorder,
  ) {}
  private async call<T>(
    operation: (native: NativeRecorderModule) => Promise<T>,
  ): Promise<T> {
    try {
      return await operation(this.load());
    } catch (error) {
      throw recorderError(error);
    }
  }
  async getModuleInfo() {
    try {
      return await getModuleInfo();
    } catch (error) {
      throw recorderError(error);
    }
  }
  async getSnapshot(): Promise<RecorderSnapshot> {
    return decodeSnapshot(await this.call((n) => n.getSnapshot()));
  }
  subscribe(
    listener: (snapshot: RecorderSnapshot) => void,
    onError: (error: unknown) => void,
  ): () => void {
    let active = true;
    let revision = -1;
    const deliver = (value: unknown) => {
      if (!active) return;
      let snapshot: RecorderSnapshot;
      try {
        snapshot = decodeSnapshot(value);
      } catch (error) {
        onError(recorderError(error));
        return;
      }
      if (snapshot.revision <= revision) return;
      revision = snapshot.revision;
      listener(snapshot);
    };
    const native = this.load();
    const subscription = native.addListener("onSnapshot", deliver);
    // Subscribe first, then read: a delayed initial read cannot overwrite a newer event.
    Promise.resolve()
      .then(() => native.getSnapshot())
      .then(deliver, (error) => {
        if (active) onError(recorderError(error));
      });
    return () => {
      active = false;
      subscription.remove();
    };
  }
  async ensureActivity() {
    return this.call((n) => n.ensureActivity());
  }
  async search() {
    return this.call((n) => n.search());
  }
  async cancelSearch() {
    return this.call((n) => n.cancelSearch());
  }
  async connect(id: string) {
    return this.call((n) => n.connect(id));
  }
  async start(options: { routeRequested: boolean }) {
    return this.call((n) => n.start(options.routeRequested));
  }
  async startDiagnostic() {
    return this.call((n) => n.startDiagnostic());
  }
  async stopDiagnostic() {
    return this.call((n) => n.stopDiagnostic());
  }
  async listDiagnosticCaptures(): Promise<DiagnosticStatus[]> {
    const result = await this.call((n) => n.listDiagnosticCaptures());
    if (
      !result ||
      !Array.isArray(result.captures) ||
      result.captures.length > 100
    )
      throw new RecorderClientError(
        "INCOMPATIBLE_BINARY",
        "Invalid capture list. Rebuild the app.",
      );
    // Reuse the complete snapshot decoder's strict status validation and field selection.
    return result.captures.map(decodeDiagnosticStatus);
  }
  async exportDiagnostic(id: string) {
    return this.call((n) => n.exportDiagnostic(id));
  }
  async deleteDiagnostic(id: string) {
    return this.call((n) => n.deleteDiagnostic(id));
  }
  async pause() {
    return this.call((n) => n.pause());
  }
  async resume() {
    return this.call((n) => n.resume());
  }
  async finish() {
    const result = await this.call((n) => n.finish());
    if (
      !result ||
      typeof result.sessionId !== "string" ||
      result.sessionId.length === 0 ||
      (result.state !== "completed" && result.state !== "interrupted")
    ) {
      throw new RecorderClientError(
        "INCOMPATIBLE_BINARY",
        "Invalid save acknowledgment; refresh the recorder or rebuild the app.",
      );
    }
    return { sessionId: result.sessionId, state: result.state };
  }
  async exportLatest() {
    return this.call((n) => n.exportLatest());
  }
  async exportSession(request: {
    id: string;
    format: SessionExportFormat;
    parameters: LocalData.AggregateParameters;
  }): Promise<ExportResult> {
    const result: unknown = await this.call((n) =>
      n.exportSession(JSON.stringify(request)),
    );
    const expected =
      request.format === "csv" ? "text/csv" : "application/gpx+xml";
    if (
      result === null ||
      typeof result !== "object" ||
      !("uri" in result) ||
      typeof result.uri !== "string" ||
      !result.uri.startsWith("content://") ||
      !("mimeType" in result) ||
      result.mimeType !== expected
    ) {
      throw new RecorderClientError(
        "INCOMPATIBLE_BINARY",
        "Invalid export response; rebuild the app.",
      );
    }
    return { uri: result.uri, mimeType: expected };
  }
  async shareExport(uri: string) {
    return this.call((n) => n.shareExport(uri));
  }
  async getSystemInsets(): Promise<SystemInsets> {
    return decodeSystemInsets(await this.call((n) => n.getSystemInsets()));
  }
  async getNetworkStatus(): Promise<NetworkStatus> {
    return decodeNetworkStatus(await this.call((n) => n.getNetworkStatus()));
  }
  subscribeNetwork(
    listener: (status: NetworkStatus) => void,
    onError: (error: unknown) => void,
  ): () => void {
    let active = true;
    const deliver = (value: unknown) => {
      if (!active) return;
      try {
        listener(decodeNetworkStatus(value));
      } catch (error) {
        onError(recorderError(error));
      }
    };
    const native = this.load();
    const subscription = native.addListener("onNetwork", deliver);
    Promise.resolve()
      .then(() => native.getNetworkStatus())
      .then(deliver, (error) => {
        if (active) onError(recorderError(error));
      });
    return () => {
      active = false;
      subscription.remove();
    };
  }
}
export function decodeSystemInsets(value: unknown): SystemInsets {
  const v = value as Record<string, unknown> | null;
  const side = (key: string) =>
    typeof v?.[key] === "number" &&
    Number.isFinite(v[key]) &&
    (v[key] as number) >= 0 &&
    (v[key] as number) <= 400;
  if (
    !v ||
    typeof v !== "object" ||
    !["top", "bottom", "left", "right"].every(side)
  ) {
    throw new RecorderClientError(
      "INCOMPATIBLE_BINARY",
      "Invalid screen insets; rebuild the app.",
    );
  }
  return {
    top: v.top as number,
    bottom: v.bottom as number,
    left: v.left as number,
    right: v.right as number,
  };
}
export function decodeNetworkStatus(value: unknown): NetworkStatus {
  const v = value as Record<string, unknown> | null;
  if (
    !v ||
    typeof v !== "object" ||
    typeof v.connected !== "boolean" ||
    typeof v.validated !== "boolean" ||
    (v.validated && !v.connected)
  ) {
    throw new RecorderClientError(
      "INCOMPATIBLE_BINARY",
      "Invalid network status; rebuild the app.",
    );
  }
  return { connected: v.connected, validated: v.validated };
}
export const recorderClient = new RecorderClient();
