const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { setImmediate } = require('node:timers');
const flush = () => new Promise(resolve => setImmediate(resolve));
class RecorderClientError extends Error { constructor(code, message) { super(message); this.code = code; } }
const snapshot = (revision = 1, patch = {}) => ({ revision, ready: true, sessionId: null, sessionState: 'preparation', routeEnabled: false, ...patch });
async function fixture() {
  const calls = []; let emit; let view; let removed = 0;
  const permissions = { bluetoothGranted: async () => true, requestBluetooth: async () => true, requestRoute: async () => true, notifications: async () => {}, confirmFinish: async () => true };
  const client = { getModuleInfo: async () => ({}), ensureActivity: async () => { calls.push('Activity'); },
    subscribe: listener => { emit = listener; return () => { removed++; }; }, getSnapshot: async () => snapshot(),
    search: async () => { calls.push('search'); }, cancelSearch: async () => { calls.push('cancelSearch'); }, connect: async id => { calls.push(['connect', id]); },
    start: async options => { calls.push(['start', options.routeRequested]); }, pause: async () => { calls.push('pause'); }, resume: async () => { calls.push('resume'); },
    finish: async () => { calls.push('finish'); }, exportLatest: async () => { calls.push('export'); return { uri: 'content://test/exports/file.zip' }; },
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
