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
  timezone_id: string | null;
  time_origin: 'measurement' | 'receipt' | 'user_action' | 'recovery';
  monotonic_offset_ms: number;
  timebase_id: string;
  receipt_elapsed_realtime_ns: string;
  source_elapsed_realtime_ns: string | null;
  observed_monotonic_offset_ms: number | null;
  source_timestamp: { value: string; unit: string; utc_conversion: string | null } | null;
}
export interface ObservationMetadata extends Time {
  normalization_version: 'native-observation-v1';
  quality_flags: readonly QualityFlag[];
  quality_reasons: readonly string[];
}
/** Derived, never written back into raw quality_flags. */
export interface HrUsability {
  method_version: 'hr-usability-v1';
  stale_after_ms: number;
  usable: boolean;
  reasons: readonly string[];
}
export interface RecordEnvelope extends Identity, Source, ObservationMetadata {
  session_id: string;
  sequence: number;
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

/** Native aggregate projection. Exact nanoseconds cross the future bridge as decimal strings. */
export interface AggregateParameters {
  hr_stale_after_ms: number;
  gps_max_accuracy_m: number;
  gps_max_gap_ms: number;
  gps_max_speed_m_s: number;
  map_hr_window_ms: number;
}
export interface ActiveSegment {
  segment_id: string;
  timebase_id: string;
  start_elapsed_ns: string;
  end_elapsed_ns: string;
  start_sequence: number;
  end_sequence: number;
}
export interface HrInterval {
  observation_id: string;
  sequence: number;
  segment_id: string;
  timebase_id: string;
  start_elapsed_ns: string;
  end_elapsed_ns: string;
  value_bpm: number;
}
export interface RoutePoint {
  observation_id: string;
  sequence: number;
  elapsed_ns: string;
  latitude: number;
  longitude: number;
  hr_observation_id: string | null;
  hr_bpm: number | null;
  hr_delta_ms: number | null;
}
export interface SessionAggregates {
  method_version: 'local-session-aggregates-v1';
  distance_method_version: 'haversine-sphere-v1';
  sphere_radius_m: number;
  parameters: AggregateParameters;
  hr_ranges_snapshot: HrRanges | null;
  active_segments: readonly ActiveSegment[];
  interrupted_tail_unknown: boolean;
  active_duration_ms: number;
  hr_covered_duration_ms: number;
  hr_coverage: number | null;
  average_hr_bpm: number | null;
  range_durations: readonly { range_id: string; duration_ms: number }[];
  unassigned_hr_duration_ms: number;
  hr_intervals: readonly HrInterval[];
  route_segments: readonly { segment_id: string; timebase_id: string; points: readonly RoutePoint[] }[];
  gps_distance_m: number | null;
  exclusions: readonly { observation_id: string; stream: 'hr' | 'gps'; reasons: readonly string[] }[];
}
