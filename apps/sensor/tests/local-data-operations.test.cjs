const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const ts = require('typescript');
function load(file, requireFn = () => { throw new Error('Unexpected dependency'); }) {
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '..', file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: requireFn });
  return exports;
}
const codec = load('modules/veymur-recorder/src/local-data/operations.ts');
const settings = { units: 'metric', timezone_id: 'Europe/Warsaw', theme: 'system', route_enabled: false, hr_ranges_id: null,
  alerts: { enabled: false, sound: false, vibration: false, debounce_ms: 0, hysteresis_bpm: 0, repeat_ms: null } };
const session = { id: 'session', local_profile_id: 'guest', state: 'interrupted', hr_state: null, gps_state: null,
  started_utc_ms: 1000, ended_utc_ms: null, timezone_id: null, device_id: 'sensor', route_requested: null,
  storage_origin: 'legacy_v1', recorded_active_ms: 100, hr_ranges_snapshot: null,
  hr_coverage: null, average_hr_bpm: null, gps_distance_m: null, metrics_unavailable_reason: 'details_calculation_required' };
const parameters = { hr_stale_after_ms: 5000, gps_max_accuracy_m: 30, gps_max_gap_ms: 10000, gps_max_speed_m_s: 12, map_hr_window_ms: 5000 };
test('request bounds, missing cursor, fractional revisions, unknown stream and invalid settings fail before native dispatch', () => {
  for (const request of [{ limit: 0, before: null }, { limit: 101, before: null }, { limit: 1.5, before: null }, { limit: 10 }]) {
    assert.throws(() => codec.encodeLocalRequest('list', request));
  }
  assert.throws(() => codec.encodeLocalRequest('stream', { id: 's', stream: 'raw', after_sequence: 0, limit: 1 }));
  for (const value of [{ ...settings, timezone_id: 'Invalid/Timezone' }, { ...settings, route_enabled: 0 },
    { ...settings, alerts: { ...settings.alerts, repeat_ms: 0 } }]) {
    assert.throws(() => codec.encodeLocalRequest('saveSettings', { expected_revision: 0, value }));
  }
  assert.throws(() => codec.encodeLocalRequest('saveSettings', { expected_revision: 0.5, value: settings }));
  assert.equal(JSON.parse(codec.encodeLocalRequest('list', { limit: 2, before: { id: 'last', started_utc_ms: -1 } })).before.started_utc_ms, -1);
});
test('ranges reject overlap, duplicate IDs and inverted bounds without prescribing zones', () => {
  const range = (id, lower, upper) => ({ id, label: id, lower_bpm_inclusive: lower, upper_bpm_exclusive: upper });
  for (const ranges of [[range('a', null, 100), range('b', 90, null)], [range('a', 0, 100), range('a', 100, null)], [range('a', 100, 100)]]) {
    assert.throws(() => codec.encodeLocalRequest('saveRanges', { expected_revision: 0, value: { ranges } }));
  }
  const encoded = codec.encodeLocalRequest('saveRanges', { expected_revision: 0, value: { ranges: [range('a', null, 100), range('b', 100, null)] } });
  assert.equal(JSON.parse(encoded).value.ranges[0].lower_bpm_inclusive, null);
});
test('legacy unknown fields remain null; outputs omit database internals and reject missing metrics', () => {
  const decoded = codec.decodeLocalResult('list', { items: [{ ...session, key: 'secret', rawRecords: [] }], next: null });
  assert.equal(decoded.items[0].hr_coverage, null); assert.equal(decoded.items[0].route_requested, null);
  assert.equal('key' in decoded.items[0], false); assert.equal('rawRecords' in decoded.items[0], false);
  assert.throws(() => codec.decodeLocalResult('list', { items: [{ ...session, hr_coverage: undefined }], next: null }));
  const details = codec.decodeLocalResult('details', { session, parameters, method_version: 'local-session-aggregates-v1', projection_version: 'journal-v2-logical-v1', summary: null, unavailable_reasons: ['legacy_envelope_not_known'] });
  assert.equal(details.summary, null); assert.equal(details.unavailable_reasons[0], 'legacy_envelope_not_known');
});
test('stream returns original payload, exact nullable segment and bounded sequence cursor', () => {
  const record = { session_id: 's', local_profile_id: 'guest', id: 'r', sequence: 7, kind: 'hr', received_utc_ms: 1000, elapsed_realtime_ms: 12,
    payload_json: 'not JSON: original legacy bytes', storage_origin: 'legacy_v1', segment_id: null };
  const decoded = codec.decodeLocalResult('stream', { items: [record], next_sequence: 7 });
  assert.equal(decoded.items[0].payload_json, record.payload_json); assert.equal(decoded.items[0].segment_id, null);
  assert.throws(() => codec.decodeLocalResult('stream', { items: Array(201).fill(record), next_sequence: null }));
  assert.throws(() => codec.decodeLocalResult('stream', { items: [{ ...record, sequence: Number.MAX_SAFE_INTEGER + 1 }], next_sequence: null }));
});
test('cleanup failure is explicit, discoverable and incompatible completion cannot masquerade as success', () => {
  const result = codec.decodeLocalResult('delete', { id: 's', data_deleted: true, cleanup: 'pending', cleanup_error: 'MANAGED_FILE_DELETE_FAILED' });
  assert.equal(result.cleanup, 'pending');
  assert.throws(() => codec.decodeLocalResult('delete', { ...result, cleanup: 'complete' }));
  assert.equal(codec.decodeLocalResult('pendingDeletions', { ids: ['s'], next_id: null }).ids[0], 's');
});
test('client maps validation and native failures separately; no invalid input reaches the bridge', async () => {
  class RecorderClientError extends Error { constructor(code, message) { super(message); this.code = code; } }
  let calls = 0;
  const { LocalDataClient } = load('src/recorder/LocalDataClient.ts', name => name.includes('operations') ? codec :
    name === './RecorderClient' ? { RecorderClientError, recorderError: e => e } : {});
  const client = new LocalDataClient(() => ({ localData: async encoded => { calls++; assert.equal(JSON.parse(encoded).operation, 'preferences'); return { settings: null, hr_ranges: null }; } }));
  assert.equal((await client.execute('preferences', {})).settings, null);
  await assert.rejects(client.execute('list', { limit: 0, before: null }), { code: 'INVALID_ARGUMENT' }); assert.equal(calls, 1);
  const bad = new LocalDataClient(() => ({ localData: async () => ({ settings: null }) }));
  await assert.rejects(bad.execute('preferences', {}), { code: 'INCOMPATIBLE_BINARY' });
  const failed = new LocalDataClient(() => ({ localData: async () => { throw new RecorderClientError('STORAGE_ERROR', 'failed'); } }));
  await assert.rejects(failed.execute('preferences', {}), { code: 'STORAGE_ERROR' });
});
