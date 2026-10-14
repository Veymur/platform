const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
function load(results, choice = 1) {
  const requested = []; const exports = {};
  const P = { BLUETOOTH_SCAN: 'scan', BLUETOOTH_CONNECT: 'connect', ACCESS_FINE_LOCATION: 'fine', ACCESS_COARSE_LOCATION: 'coarse', POST_NOTIFICATIONS: 'notify' };
  const native = { Alert: { alert: (_, __, buttons) => buttons[choice].onPress() }, PermissionsAndroid: { PERMISSIONS: P, RESULTS: { GRANTED: 'granted' },
    check: async p => results[p] === 'granted', requestMultiple: async ps => { requested.push(ps); return results; },
    request: async p => { requested.push(p); return results[p]; } } };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/recorder/permissions.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: () => native });
  return { permissions: exports.recorderPermissions, requested };
}
test('both Bluetooth permissions required; cancel explanation does not prompt OS', async () => {
  const f = load({ scan: 'granted', connect: 'never_ask_again' }); assert.equal(await f.permissions.requestBluetooth(), false);
  const cancel = load({}, 0); assert.equal(await cancel.permissions.requestBluetooth(), false); assert.deepEqual(cancel.requested, []);
});
test('approximate location is insufficient; fine and coarse requested together', async () => {
  const f = load({ fine: 'denied', coarse: 'granted' }); assert.equal(await f.permissions.requestRoute(), false);
  assert.deepEqual(JSON.parse(JSON.stringify(f.requested)), [['fine', 'coarse']]);
});
test('notification denial and continue-without choice resolve without blocking recording', async () => {
  const deny = load({ notify: 'denied' }); await deny.permissions.notifications(); assert.deepEqual(deny.requested, ['notify']);
  const skip = load({}, 0); await skip.permissions.notifications(); assert.deepEqual(skip.requested, []);
});
