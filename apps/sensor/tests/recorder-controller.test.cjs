const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { setImmediate } = require('node:timers');
const flush = () => new Promise(resolve => setImmediate(resolve));
class RecorderClientError extends Error { constructor(code, message) { super(message); this.code = code; } }
const snapshot = (revision = 1, patch = {}) => ({ revision, ready: true, sessionId: null, sessionState: 'preparation', routeEnabled: false, diagnostic: { captureId: null, state: 'off' }, ...patch });
async function fixture() {
  const calls = []; let emit; let view; let removed = 0;
  const permissions = { bluetoothGranted: async () => true, requestBluetooth: async () => true, requestRoute: async () => true, notifications: async () => {}, confirmDeleteDiagnostic: async () => true, confirmFinish: async () => true };
  const client = { getModuleInfo: async () => ({}), ensureActivity: async () => { calls.push('Activity'); },
    subscribe: listener => { emit = listener; return () => { removed++; }; }, getSnapshot: async () => snapshot(), listDiagnosticCaptures: async () => [],
    search: async () => { calls.push('search'); }, cancelSearch: async () => { calls.push('cancelSearch'); }, connect: async id => { calls.push(['connect', id]); },
    start: async options => { calls.push(['start', options.routeRequested]); }, pause: async () => { calls.push('pause'); }, resume: async () => { calls.push('resume'); },
    startDiagnostic: async () => { calls.push('startDiagnostic'); }, stopDiagnostic: async () => { calls.push('stopDiagnostic'); },
    finish: async () => { calls.push('finish'); return { sessionId: 'saved', state: 'completed' }; }, exportLatest: async () => { calls.push('export'); return { uri: 'content://test/exports/file.zip' }; },
    shareExport: async uri => { calls.push(['share', uri]); } };
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/recorder/RecorderController.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: () => ({ recorderClient: client, RecorderClientError }) });
  const controller = new exports.RecorderController(permissions, client);
  let off = controller.mount(value => { view = value; }); await flush(); emit(snapshot());
  return { controller, client, permissions, calls, emit: value => emit(value), view: () => view, off: () => off(), removed: () => removed,
    remount() { off = controller.mount(value => { view = value; }); return flush(); } };
}
test('mount/remount only subscribe; active native snapshot restores pause/route without start or stop', async () => {
  const f = await fixture(); assert.deepEqual(f.calls, []);
  f.emit(snapshot(5, { sessionId: 'active', sessionState: 'paused', activeMs: 1500, routeEnabled: true }));
  f.off(); assert.equal(f.removed(), 1); await f.remount();
  f.emit(snapshot(6, { sessionId: 'active', sessionState: 'paused', activeMs: 1500, routeEnabled: true }));
  assert.equal(f.view().snapshot.activeMs, 1500); assert.equal(f.view().routeRequested, true); assert.deepEqual(f.calls, []);
});
test('Bluetooth denial stops discovery, retry connects explicitly; acceptance does not fabricate connection', async () => {
  const f = await fixture(); f.permissions.requestBluetooth = async () => false;
  await f.controller.search(); assert.deepEqual(f.calls, ['Activity']); assert.match(f.view().message, /denied/);
  f.permissions.requestBluetooth = async () => true; await f.controller.search(); await f.controller.connect('ABC123');
  assert.equal(f.calls.includes('search'), true); assert.deepEqual(f.calls.at(-1), ['connect', 'ABC123']);
  assert.equal(f.view().snapshot.sessionState, 'preparation'); assert.equal(f.view().snapshot.sessionId, null);
});
test('location denial preserves HR-only start, notifications denial does not block start', async () => {
  const f = await fixture(); f.permissions.requestRoute = async () => false;
  await f.controller.setRoute(true); assert.equal(f.view().routeRequested, false); assert.match(f.view().message, /without a route/);
  let notificationRequested = false; f.permissions.notifications = async () => { notificationRequested = true; };
  await f.controller.start(); assert.equal(notificationRequested, true); assert.deepEqual(f.calls.at(-1), ['start', false]);
});
test('pending commands reject double taps and never claim successful saving before commit', async () => {
  const f = await fixture(); let reject;
  f.client.finish = () => new Promise((_, failure) => { reject = failure; });
  const result = f.controller.finish(); await flush(); assert.equal(f.view().pending, 'Finish'); assert.equal(f.view().message, null);
  await f.controller.start(); assert.equal(f.calls.some(c => Array.isArray(c) && c[0] === 'start'), false);
  reject(new RecorderClientError('STORAGE_ERROR', 'commit failed')); await result;
  assert.match(f.view().error, /STORAGE_ERROR/); assert.equal(f.view().message, null); assert.equal(f.view().pending, null);
});
test('finish requires confirmation; chooser acknowledgment never claims recipient saved the file', async () => {
  const f = await fixture(); f.permissions.confirmFinish = async () => false; await f.controller.finish(); assert.equal(f.calls.includes('finish'), false);
  f.permissions.confirmFinish = async () => true; await f.controller.finish(); assert.match(f.view().message, /saved on this phone/);
  await f.controller.exportLatest(); assert.deepEqual(f.calls.at(-1), ['share', 'content://test/exports/file.zip']); assert.match(f.view().message, /dialog opened/);
});
test('unmount during permission dialog does not start a new recording or issue cleanup commands', async () => {
  const f = await fixture(); let approve;
  f.permissions.notifications = () => new Promise(resolve => { approve = resolve; });
  const result = f.controller.start(); await flush(); f.off(); approve(); await result;
  assert.deepEqual(f.calls, ['Activity']);
});
test('native permission/Activity errors are visible and do not become successful state', async () => {
  const f = await fixture(); f.client.ensureActivity = async () => { throw new RecorderClientError('NO_ACTIVITY', 'Reopen app'); };
  await f.controller.setRoute(true); assert.match(f.view().error, /NO_ACTIVITY/); assert.equal(f.view().routeRequested, false);
  f.permissions.bluetoothGranted = async () => false; await f.controller.connect('ABC'); assert.match(f.view().error, /PERMISSION_DENIED/);
});

test('diagnostics require explicit permission, survive unmount, and stop waits for native commit', async () => {
  const f = await fixture();
  f.permissions.requestBluetooth = async () => false;
  await f.controller.startDiagnostic(); assert.deepEqual(f.calls, ['Activity']); assert.match(f.view().message, /remain off/);
  f.permissions.requestBluetooth = async () => true;
  await f.controller.startDiagnostic(); assert.deepEqual(f.calls, ['Activity', 'Activity', 'startDiagnostic']);
  f.off(); await f.remount(); assert.equal(f.calls.includes('stopDiagnostic'), false);
  let complete;
  f.client.stopDiagnostic = () => new Promise(resolve => { complete = resolve; });
  const stopping = f.controller.stopDiagnostic(); await flush();
  assert.equal(f.view().pending, 'Stop diagnostics'); assert.equal(f.view().message, null);
  complete({ state: 'complete' }); await stopping; assert.match(f.view().message, /saved and closed/);
  f.client.stopDiagnostic = async () => { throw new RecorderClientError('DIAGNOSTIC_ERROR', 'incomplete'); };
  await f.controller.stopDiagnostic(); assert.match(f.view().error, /DIAGNOSTIC_ERROR/); assert.equal(f.view().message, null);
  assert.equal(f.calls.includes('finish'), false); assert.equal(f.calls.includes('search'), false);
});
test('unmount during diagnostic permission request cannot start capture', async () => {
  const f = await fixture(); let grant;
  f.permissions.requestBluetooth = () => new Promise(resolve => { grant = resolve; });
  const starting = f.controller.startDiagnostic(); await flush(); f.off(); grant(true); await starting;
  assert.equal(f.calls.includes('startDiagnostic'), false);
});
test('capture export uses explicit selection, waits for ZIP and never stops capture implicitly', async () => {
  const f = await fixture(); let ready;
  f.client.exportDiagnostic = id => { f.calls.push(['exportDiagnostic', id]); return new Promise(resolve => { ready = resolve; }); };
  f.controller.selectCapture('chosen');
  const exporting = f.controller.exportDiagnostic(); await flush();
  assert.equal(f.view().pending, 'Export diagnostics'); assert.equal(f.calls.some(c => Array.isArray(c) && c[0] === 'share'), false);
  ready({ uri: 'content://capture' }); await exporting;
  assert.deepEqual(f.calls.at(-2), ['exportDiagnostic', 'chosen']); assert.deepEqual(f.calls.at(-1), ['share', 'content://capture']);
  assert.match(f.view().message, /unencrypted/); assert.equal(f.calls.includes('stopDiagnostic'), false);
});
test('capture deletion confirms identity, refreshes list, preserves errors and cancels after unmount', async () => {
  const f = await fixture(); f.controller.selectCapture('chosen');
  f.permissions.confirmDeleteDiagnostic = async id => { assert.equal(id, 'chosen'); return false; };
  f.client.deleteDiagnostic = async id => { f.calls.push(['deleteDiagnostic', id]); };
  await f.controller.deleteDiagnostic(); assert.equal(f.calls.some(c => Array.isArray(c)), false);
  f.permissions.confirmDeleteDiagnostic = async () => true;
  await f.controller.deleteDiagnostic(); assert.deepEqual(f.calls.at(-1), ['deleteDiagnostic', 'chosen']); assert.equal(f.view().selectedCaptureId, null);
  assert.match(f.view().message, /other apps remain/);
  f.controller.selectCapture('chosen');
  let confirm; f.permissions.confirmDeleteDiagnostic = () => new Promise(resolve => { confirm = resolve; });
  const deleting = f.controller.deleteDiagnostic(); await flush(); const count = f.calls.length;
  f.off(); confirm(true); await deleting; assert.equal(f.calls.length, count);
});

test('saving an interrupted recording reports the available portion only after acknowledgment', async () => {
  const f = await fixture(); let acknowledge;
  f.client.finish = () => new Promise(resolve => { acknowledge = resolve; });
  const pending = f.controller.finish(); await flush();
  assert.equal(f.view().message, null);
  acknowledge({ sessionId: 'saved', state: 'interrupted' }); await pending;
  assert.match(f.view().message, /Available portion saved/);
  assert.match(f.view().message, /uncommitted tail is unknown/);
  assert.equal(f.view().error, null);
});
