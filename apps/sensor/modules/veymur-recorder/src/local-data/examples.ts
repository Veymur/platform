import type { GpsObservation, HrObservation, RecordEnvelope, Session, SessionEvent } from './types';

const identity = { id: 'session-1', schema_version: 1, local_profile_id: 'guest-1', installation_id: 'install-1' } as const;
const time = { observed_at_utc: null, recorded_at_utc: '2026-10-16T10:00:00.000Z', timezone_id: 'Europe/Warsaw', time_origin: 'receipt', monotonic_offset_ms: 1000, timebase_id: 'process-1', source_timestamp: null } as const;
const envelope = { ...identity, ...time, id: 'record-1', source_type: 'recorder', source_id: 'install-1', session_id: 'session-1', sequence: 1, quality_flags: ['valid_format'] } as const satisfies RecordEnvelope;
export const withoutGps: Session = { ...identity, source_type: 'recorder', source_id: 'install-1', created_at_utc: time.recorded_at_utc, timezone_id: time.timezone_id, state: 'recording', hr_state: 'fresh', gps_state: 'disabled', route_requested: false, started_at_utc: time.recorded_at_utc, ended_at_utc: null, active_segment_id: 'segment-1', hr_ranges_snapshot: null };
export const hr: HrObservation = { ...envelope, source_type: 'polar_hr', source_id: 'polar-1', segment_id: 'segment-1', value: 92, unit: 'bpm', contact_detected: null };
export const gps: GpsObservation = { ...envelope, id: 'record-2', sequence: 2, source_type: 'phone_gps', source_id: 'install-1', segment_id: 'segment-1', value: { latitude: 52.23, longitude: 21.01, horizontal_accuracy_m: 8, altitude_m: null, speed_m_s: null }, unit: 'degree' };
export const hrLost: SessionEvent = { ...envelope, id: 'record-3', sequence: 3, monotonic_offset_ms: 2000, event: { type: 'hr_state', state: 'lost', reason: 'bluetooth_disconnected' } };
export const gpsContinues: Session = { ...withoutGps, route_requested: true, gps_state: 'available', hr_state: 'lost' };
export const pause: SessionEvent = { ...envelope, id: 'record-4', sequence: 4, monotonic_offset_ms: 3000, time_origin: 'user_action', event: { type: 'pause', segment_id: 'segment-1' } };
export const paused: Session = { ...gpsContinues, state: 'paused', hr_state: 'inactive', gps_state: 'inactive', active_segment_id: null };
export const resume: SessionEvent = { ...envelope, id: 'record-5', sequence: 5, monotonic_offset_ms: 5000, time_origin: 'user_action', event: { type: 'resume', segment_id: 'segment-2' } };
export const interruption: SessionEvent = { ...envelope, id: 'record-6', sequence: 6, time_origin: 'recovery', timebase_id: 'process-2', monotonic_offset_ms: 10, event: { type: 'interrupted', last_persisted_record_id: 'record-5' }, quality_flags: ['clock_uncertain'] };
export const interrupted: Session = { ...paused, state: 'interrupted', ended_at_utc: null };
