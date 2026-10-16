/** Local data v1. Not the diagnostic bridge or SQL user_version. See docs/local-data-contract.md. */
export const LOCAL_DATA_SCHEMA_VERSION = 1 as const;
export type SessionState = 'preparation' | 'recording' | 'paused' | 'finishing' | 'completed' | 'interrupted';
export type HrState = 'inactive' | 'waiting' | 'fresh' | 'stale' | 'lost';
export type GpsState = 'disabled' | 'inactive' | 'waiting' | 'available' | 'degraded' | 'lost' | 'permission_denied';
export type QualityFlag = 'valid_format' | 'suspect_signal' | 'missing' | 'stale' | 'clock_uncertain' | 'estimated';
export interface Identity {
  id: string;
  schema_version: 1;
  local_profile_id: string;
  installation_id: string;
}
export interface Source {
  source_type: 'polar_hr' | 'phone_gps' | 'recorder' | 'user';
  source_id: string;
}
export interface Time {
  observed_at_utc: string | null;
  recorded_at_utc: string;
  timezone_id: string;
  time_origin: 'measurement' | 'receipt' | 'user_action' | 'recovery';
  monotonic_offset_ms: number;
  timebase_id: string;
  source_timestamp: { value: string; unit: string; utc_conversion: string | null } | null;
}
export interface RecordEnvelope extends Identity, Source, Time {
  session_id: string;
  sequence: number;
  quality_flags: readonly QualityFlag[];
}
export interface HrObservation extends RecordEnvelope {
  source_type: 'polar_hr';
  segment_id: string;
  value: number;
  unit: 'bpm';
  contact_detected: boolean | null;
}
export interface GpsObservation extends RecordEnvelope {
  source_type: 'phone_gps';
  segment_id: string;
  value: { latitude: number; longitude: number; horizontal_accuracy_m: number | null; altitude_m: number | null; speed_m_s: number | null };
  unit: 'degree';
}
export type SessionEventPayload =
  | { type: 'start' | 'resume'; segment_id: string }
  | { type: 'pause' | 'finish_requested'; segment_id: string | null }
  | { type: 'completed' }
  | { type: 'interrupted'; last_persisted_record_id: string | null }
  | { type: 'hr_state'; state: HrState; reason: string }
  | { type: 'gps_state'; state: GpsState; reason: string };
export interface SessionEvent extends RecordEnvelope {
  source_type: 'recorder';
  event: SessionEventPayload;
}
export interface Session extends Identity {
  source_type: 'recorder';
  source_id: string;
  created_at_utc: string;
  timezone_id: string;
  state: SessionState;
  hr_state: HrState;
  gps_state: GpsState;
  route_requested: boolean;
  started_at_utc: string | null;
  ended_at_utc: string | null;
  active_segment_id: string | null;
  hr_ranges_snapshot: HrRanges | null;
}
export interface LocalProfile extends Identity {
  created_at_utc: string;
}
export interface HrRanges extends Identity {
  revision: number;
  ranges: readonly { id: string; label: string; lower_bpm_inclusive: number | null; upper_bpm_exclusive: number | null }[];
}
export interface Settings extends Identity {
  revision: number;
  units: 'metric' | 'imperial';
  timezone_id: string;
  theme: 'system' | 'light' | 'dark';
  route_enabled: boolean;
  hr_ranges_id: string | null;
  alerts: { enabled: boolean; sound: boolean; vibration: boolean; debounce_ms: number; hysteresis_bpm: number; repeat_ms: number | null };
}
