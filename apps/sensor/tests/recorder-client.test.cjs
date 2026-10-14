const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { setImmediate } = require('node:timers');
function load() {
  const exports = {};
  const source = fs.readFileSync(path.join(__dirname, '../src/recorder/RecorderClient.ts'), 'utf8');
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText,
    { exports, require: () => ({ getModuleInfo: async () => ({}), nativeRecorder: () => { throw new Error('Native fixture missing'); } }) });
  return exports;
}
const dto = (revision = 1) => ({ revision, ready: true, status: 'Ready', sessionId: null, sessionState: 'preparation',
  connectedDeviceId: null, batteryPercent: null, hr: null, hrAgeMs: null, hrStatus: 'missing', devices: [],
  hrCount: 0, gpsCount: 0, activeMs: 0, lastCommitUtcMs: null, gpsStatus: 'Route disabled', routeEnabled: false, recoveredCount: 0, errorCode: null });
const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture() {
  let resolveRead;
  let emit;
  let removed = 0;
  const native = { getSnapshot: () => new Promise(resolve => { resolveRead = resolve; }),
    addListener(event, callback) { assert.equal(event, 'onSnapshot'); emit = callback; return { remove: () => { removed++; } }; } };
  return { native, emit: value => emit(value), read: value => resolveRead(value), removed: () => removed };
}
test('late initial read and duplicate revisions cannot overwrite a newer subscription event', async () => {
  const { RecorderClient } = load(); const f = fixture(); const received = [];
  const unsubscribe = new RecorderClient(() => f.native).subscribe(s => received.push(s.revision), assert.fail);
  await flush(); f.emit(dto(4)); f.read(dto(2)); await flush(); f.emit(dto(4)); f.emit(dto(5));
  assert.deepEqual(received, [4, 5]); unsubscribe(); assert.equal(f.removed(), 1);
});
test('initial snapshot delivered, unsubscribe ignores pending read/events, remount receives complete state', async () => {
  const { RecorderClient } = load(); const f = fixture(); const client = new RecorderClient(() => f.native); const received = [];
  const off = client.subscribe(s => received.push(s.revision), assert.fail);
  await flush(); off(); f.read(dto(1)); f.emit(dto(2)); await flush(); assert.deepEqual(received, []);
  const off2 = client.subscribe(s => received.push(s.revision), assert.fail);
  await flush(); f.read(dto(3)); await flush(); assert.deepEqual(received, [3]); off2();
});
test('stale HR stays null; malformed, missing and zero values are rejected; native internals are omitted', () => {
  const { decodeSnapshot } = load();
  const stale = { ...dto(), connectedDeviceId: 'TEST', hrStatus: 'stale', hrAgeMs: 6000, rawRecords: [1], key: 'test' };
  const decoded = decodeSnapshot(stale); assert.equal(decoded.hr, null); assert.equal('key' in decoded, false); assert.equal('rawRecords' in decoded, false);
  for (const bad of [{ ...stale, hr: 80 }, { ...dto(), hr: 0 }, { ...dto(), activeMs: undefined }, { ...dto(), revision: -1 }, { ...dto(), sessionState: { toString: () => 'paused' } },
    { ...dto(), connectedDeviceId: 'TEST', hrStatus: 'fresh', hr: 80, hrAgeMs: 6000 }]) {
    assert.throws(() => decodeSnapshot(bad), e => e.code === 'INCOMPATIBLE_BINARY');
  }
});
test('commands wait for native acknowledgment and preserve storage/Activity/service error codes', async () => {
  const { RecorderClient } = load(); let acknowledge; let complete = false;
  const client = new RecorderClient(() => ({ start: route => { assert.equal(route, false); return new Promise(resolve => { acknowledge = resolve; }); },
    finish: async () => { throw Object.assign(new Error('commit failed'), { code: 'STORAGE_ERROR' }); },
    shareExport: async () => { throw Object.assign(new Error('Open app'), { code: 'NO_ACTIVITY' }); },
    pause: async () => { throw Object.assign(new Error('retry binding'), { code: 'SERVICE_UNAVAILABLE' }); } }));
  const started = client.start({ routeRequested: false }).then(result => { complete = true; return result; });
  await flush(); assert.equal(complete, false); acknowledge({ sessionId: 'committed' }); assert.equal((await started).sessionId, 'committed');
  await assert.rejects(client.finish(), e => e.code === 'STORAGE_ERROR');
  await assert.rejects(client.shareExport('content://test'), e => e.code === 'NO_ACTIVITY');
  await assert.rejects(client.pause(), e => e.code === 'SERVICE_UNAVAILABLE');
});
test('initial service read failure reaches error callback and stops after unsubscribe', async () => {
  const { RecorderClient } = load(); const errors = []; let rejectRead;
  const client = new RecorderClient(() => ({ addListener: () => ({ remove() {} }), getSnapshot: () => new Promise((_, reject) => { rejectRead = reject; }) }));
  const off = client.subscribe(assert.fail, e => errors.push(e.code)); await flush();
  rejectRead({ code: 'NOT_READY' }); await flush(); assert.deepEqual(errors, ['NOT_READY']); off();
});
