const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
function examples() {
  const exports = {};
  const source = fs.readFileSync(path.join(__dirname, '../modules/veymur-recorder/src/local-data/examples.ts'), 'utf8');
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports });
  return exports;
}
test('independent streams preserve unknown measurements', () => {
  const f = examples();
  assert.equal(f.withoutGps.gps_state, 'disabled');
  assert.equal(f.withoutGps.route_requested, false);
  assert.equal(f.hr.observed_at_utc, null);
  assert.equal(f.hr.time_origin, 'receipt');
  assert.equal(f.hr.contact_detected, null);
  assert.equal(f.gps.value.altitude_m, null);
  assert.equal(f.hrLost.event.type, 'hr_state');
  assert.equal(f.gpsContinues.state, 'recording');
  assert.equal(f.gpsContinues.hr_state, 'lost');
  assert.equal(f.gpsContinues.gps_state, 'available');
  assert.equal('value' in f.hrLost, false);
});
test('pause and recovery preserve segment boundaries and unknown tail', () => {
  const f = examples();
  assert.equal(f.pause.event.segment_id, f.hr.segment_id);
  assert.equal(f.paused.active_segment_id, null);
  assert.notEqual(f.resume.event.segment_id, f.pause.event.segment_id);
  assert.equal(f.interrupted.state, 'interrupted');
  assert.equal(f.interrupted.ended_at_utc, null);
  assert.equal(f.interruption.observed_at_utc, null);
  assert.notEqual(f.interruption.timebase_id, f.resume.timebase_id);
  assert.equal(f.interruption.event.last_persisted_record_id, f.resume.id);
  const records = [f.hr, f.gps, f.hrLost, f.pause, f.resume, f.interruption];
  assert.equal(new Set(records.map(r => r.id)).size, records.length);
  assert.ok(records.every((r, i) => i === 0 || r.sequence > records[i - 1].sequence));
});
