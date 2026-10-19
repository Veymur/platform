export type RecorderModuleInfo = {
  moduleName: 'VeymurRecorder'; platform: 'android'; contractVersion: 6; recorderImplemented: true;
};
export type RecorderErrorCode = 'UNSUPPORTED_PLATFORM' | 'INCOMPATIBLE_BINARY' | 'SERVICE_UNAVAILABLE' |
  'NOT_READY' | 'NO_ACTIVITY' | 'PERMISSION_DENIED' | 'INVALID_STATE' | 'INVALID_ARGUMENT' |
  'NOT_CONNECTED' | 'CONNECTION_ERROR' | 'DISCOVERY_ERROR' | 'STORAGE_ERROR' | 'NO_SESSIONS' | 'DIAGNOSTIC_ERROR';
export type SessionState = 'preparation' | 'recording' | 'paused' | 'finishing' | 'completed' | 'interrupted';
export type DiagnosticStatus = {
  captureId: string | null; state: 'off' | 'starting' | 'active' | 'stopping' | 'complete' | 'incomplete' | 'interrupted';
  savedEvents: number; savedProcessing: number; knownLostEvents: number; knownLostProcessing: number;
  unknownTail: boolean; checkpointPersisted: boolean; lastErrorCode: string | null;
};
export type RecorderSnapshot = {
  diagnostic: DiagnosticStatus;
  revision: number; ready: boolean; status: string;
  sessionId: string | null; sessionState: SessionState;
  connectedDeviceId: string | null; batteryPercent: number | null;
  hr: number | null; hrAgeMs: number | null; hrStatus: 'fresh' | 'stale' | 'missing';
  devices: { id: string; name: string; address: string }[];
  hrCount: number; gpsCount: number; activeMs: number; lastCommitUtcMs: number | null;
  gpsStatus: string; routeEnabled: boolean; recoveredCount: number; errorCode: RecorderErrorCode | null;
};
export type SessionResult = { sessionId: string; state: 'recording' | 'paused' | 'completed' | 'interrupted' };
export type ExportResult = { uri: string; mimeType: 'application/zip' };
export interface NativeRecorderModule {
  localData(request: string): Promise<unknown>;
  getModuleInfo(): Promise<unknown>;
  ensureActivity(): Promise<{ available: true }>;
  getSnapshot(): Promise<unknown>;
  addListener(event: 'onSnapshot', listener: (value: unknown) => void): { remove(): void };
  search(): Promise<{ accepted: true }>;
  cancelSearch(): Promise<{ accepted: true }>;
  connect(id: string): Promise<{ accepted: true }>;
  start(routeRequested: boolean): Promise<{ sessionId: string }>;
  startDiagnostic(): Promise<DiagnosticStatus>;
  stopDiagnostic(): Promise<DiagnosticStatus>;
  listDiagnosticCaptures(): Promise<{ captures: DiagnosticStatus[] }>;
  exportDiagnostic(id: string): Promise<ExportResult>;
  deleteDiagnostic(id: string): Promise<{ deleted: boolean }>;
  pause(): Promise<SessionResult>;
  resume(): Promise<SessionResult>;
  finish(): Promise<SessionResult>;
  exportLatest(): Promise<ExportResult>;
  shareExport(uri: string): Promise<{ chooserOpened: true }>;
}
