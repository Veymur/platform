import type { AggregateParameters, Settings, HrRanges, SessionState, HrState, GpsState } from './types';

export type SettingsInput = Omit<Settings, 'id' | 'schema_version' | 'local_profile_id' | 'installation_id' | 'revision'>;
export type RangesInput = Pick<HrRanges, 'ranges'>;
export type HistoryCursor = { started_utc_ms: number; id: string };
export interface SavedSession {
  id: string; local_profile_id: string; state: SessionState; hr_state: HrState | null; gps_state: GpsState | null;
  started_utc_ms: number; ended_utc_ms: number | null; timezone_id: string | null; device_id: string;
  route_requested: boolean | null; storage_origin: 'legacy_v1' | 'native_v2'; recorded_active_ms: number;
  hr_ranges_snapshot: HrRanges | null; hr_coverage: null; average_hr_bpm: null; gps_distance_m: null;
  metrics_unavailable_reason: 'details_calculation_required';
}
export type HistoryPage = { items: SavedSession[]; next: HistoryCursor | null };
export type Preferences = { settings: Settings | null; hr_ranges: HrRanges | null };
export type StreamRecord = { session_id: string; local_profile_id: string; id: string; sequence: number; kind: string; received_utc_ms: number; elapsed_realtime_ms: number;
  payload_json: string; storage_origin: 'legacy_v1' | 'native_v2'; segment_id: string | null };
export type StreamPage = { items: StreamRecord[]; next_sequence: number | null };
export type SessionDetails = { session: SavedSession; parameters: AggregateParameters; method_version: 'local-session-aggregates-v1'; projection_version: 'journal-v2-logical-v1';
  summary: { distance_method_version: 'haversine-sphere-v1'; sphere_radius_m: number; active_duration_ms: number; hr_covered_duration_ms: number; hr_coverage: number | null; average_hr_bpm: number | null;
    gps_distance_m: number | null; interrupted_tail_unknown: boolean; unassigned_hr_duration_ms: number;
    range_durations: { range_id: string; duration_ms: number }[] } | null; unavailable_reasons: string[] };
export type DeletionResult = { id: string; data_deleted: boolean; cleanup: 'complete' | 'pending'; cleanup_error: 'MANAGED_FILE_DELETE_FAILED' | null };
export interface LocalOperations {
  list: { request: { limit: number; before: HistoryCursor | null }; result: HistoryPage };
  details: { request: { id: string; parameters: AggregateParameters }; result: SessionDetails };
  stream: { request: { id: string; stream: 'hr' | 'gps' | 'events'; after_sequence: number; limit: number }; result: StreamPage };
  preferences: { request: Record<string, never>; result: Preferences };
  saveSettings: { request: { expected_revision: number; value: SettingsInput }; result: Preferences };
  saveRanges: { request: { expected_revision: number; value: RangesInput }; result: Preferences };
  pendingDeletions: { request: { after_id: string | null; limit: number }; result: { ids: string[]; next_id: string | null } };
  delete: { request: { id: string }; result: DeletionResult };
}

type Decoder = (value: unknown) => unknown;
function invalid(): never { throw new Error('Invalid local data contract'); }
const str: Decoder = v => typeof v === 'string' && v.length > 0 && v.length <= 256 ? v : invalid();
const bool: Decoder = v => typeof v === 'boolean' ? v : invalid();
const nil: Decoder = v => v === null ? null : invalid();
const numeric = (min = 0, max = Number.MAX_SAFE_INTEGER, integer = false): Decoder => v =>
  typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max && (!integer || Number.isSafeInteger(v)) ? v : invalid();
const int = numeric(0, Number.MAX_SAFE_INTEGER, true);
const positive = numeric(Number.MIN_VALUE);
const nullable = (d: Decoder): Decoder => v => v === null ? null : d(v);
const choice = (...values: string[]): Decoder => v => typeof v === 'string' && values.includes(v) ? v : invalid();
const array = (d: Decoder, max: number): Decoder => v => Array.isArray(v) && v.length <= max ? v.map(d) : invalid();
const object = (fields: Record<string, Decoder>): Decoder => v => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return invalid();
  const input = v as Record<string, unknown>;
  return Object.fromEntries(Object.entries(fields).map(([k, d]) => [k, d(input[k])]));
};
const timezone: Decoder = v => { const s = str(v) as string; try { new Intl.DateTimeFormat('en', { timeZone: s }); } catch { return invalid(); } return s; };
const rangesFields = { ranges: array(object({ id: str, label: str, lower_bpm_inclusive: nullable(numeric(0, 2147483647, true)), upper_bpm_exclusive: nullable(numeric(1, 2147483647, true)) }), 32) };
const rangesInput: Decoder = v => {
  const r = object(rangesFields)(v) as RangesInput;
  const sorted = [...r.ranges].sort((a,b) => (a.lower_bpm_inclusive ?? 0) - (b.lower_bpm_inclusive ?? 0));
  if (new Set(sorted.map(a => a.id)).size !== sorted.length || sorted.some((a,i) => (a.lower_bpm_inclusive ?? 0) >= (a.upper_bpm_exclusive ?? Infinity) ||
      i > 0 && (sorted[i-1].upper_bpm_exclusive ?? Infinity) > (a.lower_bpm_inclusive ?? 0))) return invalid();
  return r;
};
const settingsFields = { units: choice('metric','imperial'), timezone_id: timezone, theme: choice('system','light','dark'), route_enabled: bool, hr_ranges_id: nullable(str),
  alerts: object({ enabled: bool, sound: bool, vibration: bool, debounce_ms: int, hysteresis_bpm: numeric(0,2147483647,true), repeat_ms: nullable(numeric(1,Number.MAX_SAFE_INTEGER,true)) }) };
const identity = { id: str, schema_version: (v: unknown) => v === 1 ? v : invalid(), local_profile_id: str, installation_id: str, revision: numeric(1,2147483647,true) };
const ranges: Decoder = v => ({ ...(object(identity)(v) as object), ...(rangesInput(v) as object) });
const settings = object({ ...identity, ...settingsFields });
const preferences = object({ settings: nullable(settings), hr_ranges: nullable(ranges) });
const cursor = object({ started_utc_ms: numeric(-Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER,true), id: str });
const origin = choice('legacy_v1','native_v2');
const saved = object({ id: str, local_profile_id: str, state: choice('preparation','recording','paused','finishing','completed','interrupted'),
  hr_state: nullable(choice('inactive','waiting','fresh','stale','lost')), gps_state: nullable(choice('disabled','inactive','waiting','available','degraded','lost','permission_denied')),
  started_utc_ms: numeric(-Number.MAX_SAFE_INTEGER,Number.MAX_SAFE_INTEGER,true), ended_utc_ms: nullable(numeric(-Number.MAX_SAFE_INTEGER,Number.MAX_SAFE_INTEGER,true)),
  timezone_id: nullable(timezone), device_id: str, route_requested: nullable(bool), storage_origin: origin, recorded_active_ms: int, hr_ranges_snapshot: nullable(ranges),
  hr_coverage: nil, average_hr_bpm: nil, gps_distance_m: nil, metrics_unavailable_reason: choice('details_calculation_required') });
const parameters = object({ hr_stale_after_ms: numeric(1,9007199254,true), gps_max_accuracy_m: positive, gps_max_gap_ms: numeric(1,9007199254,true), gps_max_speed_m_s: positive, map_hr_window_ms: numeric(0,9007199254,true) });
const requests: Record<keyof LocalOperations, Decoder> = {
  pendingDeletions: object({ after_id: nullable(str), limit: numeric(1,100,true) }),
  list: object({ limit: numeric(1,100,true), before: nullable(cursor) }), details: object({ id: str, parameters }),
  stream: object({ id: str, stream: choice('hr','gps','events'), after_sequence: int, limit: numeric(1,200,true) }), preferences: object({}),
  saveSettings: object({ expected_revision: numeric(0,2147483646,true), value: object(settingsFields) }),
  saveRanges: object({ expected_revision: numeric(0,2147483646,true), value: rangesInput }), delete: object({ id: str }),
};
const responses: Record<keyof LocalOperations, Decoder> = {
  pendingDeletions: object({ ids: array(str,100), next_id: nullable(str) }),
  list: object({ items: array(saved,100), next: nullable(cursor) }), preferences, saveSettings: preferences, saveRanges: preferences,
  details: object({ session: saved, parameters, method_version: choice('local-session-aggregates-v1'), projection_version: choice('journal-v2-logical-v1'), unavailable_reasons: array(str,32),
    summary: nullable(object({ distance_method_version: choice('haversine-sphere-v1'), sphere_radius_m: positive, active_duration_ms: numeric(), hr_covered_duration_ms: numeric(), hr_coverage: nullable(numeric(0,1)), average_hr_bpm: nullable(positive),
      gps_distance_m: nullable(numeric()), interrupted_tail_unknown: bool, unassigned_hr_duration_ms: numeric(), range_durations: array(object({ range_id: str, duration_ms: numeric() }),32) })) }),
  stream: object({ items: array(object({ session_id: str, local_profile_id: str, id: str, sequence: numeric(1,Number.MAX_SAFE_INTEGER,true), kind: str, received_utc_ms: numeric(-Number.MAX_SAFE_INTEGER,Number.MAX_SAFE_INTEGER,true),
    elapsed_realtime_ms: int, payload_json: v => typeof v === 'string' && v.length <= 262144 ? v : invalid(), storage_origin: origin, segment_id: nullable(str) }),200), next_sequence: nullable(int) }),
  delete: object({ id: str, data_deleted: bool, cleanup: choice('complete','pending'), cleanup_error: nullable(choice('MANAGED_FILE_DELETE_FAILED')) }),
};
export function encodeLocalRequest<K extends keyof LocalOperations>(operation: K, request: LocalOperations[K]['request']): string {
  const value = requests[operation](request) as object;
  const result = JSON.stringify({ operation, ...value }); if(result.length>65536) return invalid(); return result;
}
export function decodeLocalResult<K extends keyof LocalOperations>(operation: K, value: unknown): LocalOperations[K]['result'] {
  const result = responses[operation](value) as LocalOperations[K]['result'];
  if (operation === 'details') {
    const d = result as SessionDetails;
    if ((d.summary === null) !== (d.unavailable_reasons.length > 0)) return invalid();
    if (d.summary && d.summary.hr_covered_duration_ms > d.summary.active_duration_ms + 0.001) return invalid();
  }
  if (operation === 'stream') {
    const page = result as StreamPage;
    if (page.items.some((item, i) => i > 0 && item.sequence <= page.items[i-1].sequence) ||
        page.next_sequence !== null && (page.items.length === 0 || page.next_sequence !== page.items[page.items.length-1].sequence)) return invalid();
  }
  if (operation === 'delete') {
    const d = result as DeletionResult;
    if ((d.cleanup === 'complete') !== (d.cleanup_error === null)) return invalid();
  }
  return result;
}
