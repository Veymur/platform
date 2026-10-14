import { nativeRecorder, getModuleInfo } from '../../modules/veymur-recorder';
import type { NativeRecorderModule, RecorderSnapshot, RecorderErrorCode } from '../../modules/veymur-recorder';
export type * from '../../modules/veymur-recorder';

const codes = new Set(['UNSUPPORTED_PLATFORM', 'INCOMPATIBLE_BINARY', 'SERVICE_UNAVAILABLE', 'NOT_READY', 'NO_ACTIVITY', 'PERMISSION_DENIED', 'INVALID_STATE', 'INVALID_ARGUMENT', 'NOT_CONNECTED', 'CONNECTION_ERROR', 'DISCOVERY_ERROR', 'STORAGE_ERROR', 'NO_SESSIONS']);
export class RecorderClientError extends Error {
  constructor(public readonly code: RecorderErrorCode, message: string, cause?: unknown) {
    super(message, { cause }); this.name = 'RecorderClientError';
  }
}
function recorderError(error: unknown): RecorderClientError {
  if (error instanceof RecorderClientError) return error;
  const value = error !== null && typeof error === 'object' ? error as { code?: unknown; message?: unknown } : {};
  const code = typeof value.code === 'string' && codes.has(value.code) ? value.code as RecorderErrorCode : 'SERVICE_UNAVAILABLE';
  return new RecorderClientError(code, typeof value.message === 'string' ? value.message : 'Recorder unavailable; retry.', error);
}
const nonnegative = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const nullableString = (value: unknown) => value === null || typeof value === 'string';
const nullableNumber = (value: unknown) => value === null || nonnegative(value);

/** Validate the complete DTO, including nulls: absence is never a zero measurement. */
export function decodeSnapshot(value: unknown): RecorderSnapshot {
  const fail = () => { throw new RecorderClientError('INCOMPATIBLE_BINARY', 'Incompatible recorder snapshot; rebuild the native app.'); };
  if (value === null || typeof value !== 'object') return fail();
  const v = value as Record<string, unknown>;
  if (!nonnegative(v.revision) || typeof v.ready !== 'boolean' || typeof v.status !== 'string' ||
      !nullableString(v.sessionId) || (typeof v.sessionState !== 'string' || !['preparation', 'recording', 'paused', 'finishing', 'completed', 'interrupted'].includes(v.sessionState)) ||
      !nullableString(v.connectedDeviceId) || !nullableNumber(v.batteryPercent) || (typeof v.batteryPercent === 'number' && v.batteryPercent > 100) ||
      !nullableNumber(v.hr) || (typeof v.hr === 'number' && v.hr === 0) || !nullableNumber(v.hrAgeMs) ||
      (typeof v.hrStatus !== 'string' || !['fresh', 'stale', 'missing'].includes(v.hrStatus)) ||
      (v.hrStatus === 'fresh' ? v.hr === null || v.hrAgeMs === null || Number(v.hrAgeMs) > 5000 || v.connectedDeviceId === null : v.hr !== null) ||
      !nonnegative(v.hrCount) || !nonnegative(v.gpsCount) || !nonnegative(v.activeMs) || !nullableNumber(v.lastCommitUtcMs) ||
      typeof v.gpsStatus !== 'string' || typeof v.routeEnabled !== 'boolean' || !nonnegative(v.recoveredCount) ||
      !(v.errorCode === null || (typeof v.errorCode === 'string' && codes.has(v.errorCode))) || !Array.isArray(v.devices) ||
      !v.devices.every(d => d !== null && typeof d === 'object' && typeof d.id === 'string' && typeof d.name === 'string' && typeof d.address === 'string')) return fail();
  // Copy only contract fields; never expose future native internals through this boundary.
  return { revision: v.revision, ready: v.ready, status: v.status, sessionId: v.sessionId, sessionState: v.sessionState,
    connectedDeviceId: v.connectedDeviceId, batteryPercent: v.batteryPercent, hr: v.hr, hrAgeMs: v.hrAgeMs, hrStatus: v.hrStatus,
    devices: v.devices.map(d => ({ id: d.id, name: d.name, address: d.address })), hrCount: v.hrCount, gpsCount: v.gpsCount,
    activeMs: v.activeMs, lastCommitUtcMs: v.lastCommitUtcMs, gpsStatus: v.gpsStatus, routeEnabled: v.routeEnabled,
    recoveredCount: v.recoveredCount, errorCode: v.errorCode } as RecorderSnapshot;
}

/** UI adapter only. The native writer and foreground service own recording and commit acknowledgment. */
export class RecorderClient {
  constructor(private readonly load: () => NativeRecorderModule = nativeRecorder) {}
  private async call<T>(operation: (native: NativeRecorderModule) => Promise<T>): Promise<T> {
    try { return await operation(this.load()); } catch (error) { throw recorderError(error); }
  }
  async getModuleInfo() { try { return await getModuleInfo(); } catch (error) { throw recorderError(error); } }
  async getSnapshot(): Promise<RecorderSnapshot> { return decodeSnapshot(await this.call(n => n.getSnapshot())); }
  subscribe(listener: (snapshot: RecorderSnapshot) => void, onError: (error: unknown) => void): () => void {
    let active = true;
    let revision = -1;
    const deliver = (value: unknown) => {
      if (!active) return;
      let snapshot: RecorderSnapshot;
      try { snapshot = decodeSnapshot(value); } catch (error) { onError(recorderError(error)); return; }
      if (snapshot.revision <= revision) return;
      revision = snapshot.revision;
      listener(snapshot);
    };
    const native = this.load();
    const subscription = native.addListener('onSnapshot', deliver);
    // Subscribe first, then read: a delayed initial read cannot overwrite a newer event.
    Promise.resolve().then(() => native.getSnapshot()).then(deliver, error => { if (active) onError(recorderError(error)); });
    return () => { active = false; subscription.remove(); };
  }
  async search() { return this.call(n => n.search()); }
  async cancelSearch() { return this.call(n => n.cancelSearch()); }
  async connect(id: string) { return this.call(n => n.connect(id)); }
  async start(options: { routeRequested: boolean }) { return this.call(n => n.start(options.routeRequested)); }
  async pause() { return this.call(n => n.pause()); }
  async resume() { return this.call(n => n.resume()); }
  async finish() { return this.call(n => n.finish()); }
  async exportLatest() { return this.call(n => n.exportLatest()); }
  async shareExport(uri: string) { return this.call(n => n.shareExport(uri)); }
}
export const recorderClient = new RecorderClient();
