const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { createRequire } = require('node:module');
// Reuse the draft-07 validator already locked by ESLint; no runtime dependency.
const Ajv = createRequire(require.resolve('eslint/package.json'))('ajv');
const root = join(__dirname, '../diagnostics');
const read = path => JSON.parse(readFileSync(join(root, path), 'utf8'));
const examples = read('fixtures/synthetic/contract-examples.json');
const ajv = new Ajv({ allErrors: true });
const schemas = Object.fromEntries(['event', 'processing', 'manifest'].map(name =>
  [name, read(`schema/${name}-v1.schema.json`)]));
const validate = Object.fromEntries(Object.entries(schemas).map(([name, schema]) =>
  [name, ajv.compile(schema)]));
const clone = value => JSON.parse(JSON.stringify(value));
const event = variant => clone(examples.events.find(e => e.callback_variant === variant));

test('D1: every declared input variant has a valid synthetic example', () => {
  assert.equal(examples.events.length, schemas.event.oneOf.length);
  assert.equal(new Set(examples.events.map(e => e.callback_variant)).size, examples.events.length);
  for (const sample of examples.events) assert.ok(validate.event(sample), JSON.stringify(validate.event.errors));
  for (const sample of examples.processing) assert.ok(validate.processing(sample), JSON.stringify(validate.processing.errors));
  assert.ok(validate.manifest(examples.manifest), JSON.stringify(validate.manifest.errors));
});

test('D1: SDK 8.4.0 callback inventory includes all 16 signatures', () => {
  const callbacks = examples.events.filter(e => e.source === 'polar_sdk' && !e.callback_variant.startsWith('searchForDevice.'));
  assert.deepEqual(callbacks.map(e => e.callback_variant).sort(), [
    'blePowerStateChanged', 'deviceConnecting', 'deviceConnected', 'deviceDisconnected.legacy',
    'deviceDisconnected.diagnostics', 'hrNotificationReceived', 'batteryLevelReceived',
    'batteryChargingStatusReceived', 'powerSourcesStateReceived', 'disInformationReceived.uuid',
    'disInformationReceived.key', 'bleSdkFeatureReady', 'bleSdkFeaturesReadiness',
    'htsNotificationReceived', 'polarCompanionDeviceAppeared', 'polarCompanionDeviceDisappeared',
  ].sort());
});

test('D1: missing SDK fields and unexpected payload fields are rejected for every variant', () => {
  for (const original of examples.events) {
    for (const key of Object.keys(original.payload)) {
      const sample = clone(original);
      delete sample.payload[key];
      assert.equal(validate.event(sample), false, `${original.callback_variant}: missing ${key}`);
    }
    const sample = clone(original);
    sample.payload.unmapped_sdk_field = 1;
    assert.equal(validate.event(sample), false, original.callback_variant);
  }
});

test('D1: raw HR preserves unusual integers, repeats, false, and empty arrays', () => {
  const sample = event('hrNotificationReceived');
  assert.ok(validate.event(sample));
  sample.payload.hr = -1;
  sample.payload.rrsMs = [];
  assert.ok(validate.event(sample));
  assert.deepEqual(JSON.parse(JSON.stringify(sample)).payload.rrs, [1024, 0, 1024]);
  sample.payload.hr = null;
  assert.equal(validate.event(sample), false);
});

test('D1: typed null diagnostics differ from absent legacy diagnostics and zero', () => {
  const typed = event('deviceDisconnected.diagnostics');
  assert.ok(validate.event(typed));
  typed.payload.diagnostics.gattStatus = 0;
  assert.ok(validate.event(typed));
  delete typed.payload.diagnostics.gattStatus;
  assert.equal(validate.event(typed), false);
  const legacy = event('deviceDisconnected.legacy');
  assert.ok(validate.event(legacy));
  legacy.payload.diagnostics = null;
  assert.equal(validate.event(legacy), false);
});

test('D1: exact nanoseconds and thermometer float bits survive JSON', () => {
  const sample = event('htsNotificationReceived');
  sample.received_elapsed_ns = '9007199254740993';
  assert.ok(validate.event(sample));
  assert.equal(JSON.parse(JSON.stringify(sample)).received_elapsed_ns, '9007199254740993');
  sample.received_elapsed_ns = 9007199254740992;
  assert.equal(validate.event(sample), false);
  sample.received_elapsed_ns = '1';
  sample.payload.celsius.ieee754_hex = 'NaN';
  assert.equal(validate.event(sample), false);
});

test('D1: unknown versions, wrong discriminators, and unsafe sequence are rejected', () => {
  for (const [name, original] of [['event', examples.events[0]], ['processing', examples.processing[0]], ['manifest', examples.manifest]]) {
    const sample = clone(original);
    sample.schema_version = 2;
    assert.equal(validate[name](sample), false);
  }
  const sample = event('hrNotificationReceived');
  sample.event_type = 'battery_level';
  assert.equal(validate.event(sample), false);
  sample.event_type = 'hr';
  sample.source = 'app_command';
  assert.equal(validate.event(sample), false);
  sample.source = 'polar_sdk';
  sample.sequence = 9007199254740992;
  assert.equal(validate.event(sample), false);
});

test('D1: interrupted manifest can explicitly retain an unknown lost tail', () => {
  const sample = clone(examples.manifest);
  sample.state = 'interrupted';
  sample.ended_utc_ms = null;
  sample.ended_elapsed_ns = null;
  sample.checkpoint.received_events = null;
  sample.checkpoint.unknown_tail = true;
  assert.ok(validate.manifest(sample));
  sample.files['events.jsonl'].sha256 = 'truncated';
  assert.equal(validate.manifest(sample), false);
});

// Optional cross-language check of output generated by native instrumentation.
// The ordinary suite remains reproducible without Android or an exported file.
if (process.env.POLAR_DIAGNOSTIC_NATIVE_EXAMPLES) {
  test('D2: actual native serializer output conforms to the independent JSON Schemas', () => {
    const native = JSON.parse(readFileSync(process.env.POLAR_DIAGNOSTIC_NATIVE_EXAMPLES, 'utf8'));
    const variants = new Set();
    const sequences = new Set();
    for (const sample of native.events) {
      assert.ok(validate.event(sample), JSON.stringify(validate.event.errors));
      assert.equal(sequences.has(sample.sequence), false);
      sequences.add(sample.sequence);
      variants.add(sample.callback_variant);
    }
    const expected = examples.events.filter(e => e.source === 'polar_sdk' || e.event_type === 'search_ended');
    assert.deepEqual([...variants].sort(), expected.map(e => e.callback_variant).sort());
    assert.equal(native.events.length, native.processing.length);
    for (const sample of native.processing) {
      assert.ok(validate.processing(sample), JSON.stringify(validate.processing.errors));
      assert.ok(sequences.has(sample.input_sequence));
      const input = native.events.find(e => e.sequence === sample.input_sequence);
      assert.equal(sample.capture_id, input.capture_id);
      assert.equal(sample.process_run_id, input.process_run_id);
      assert.ok(BigInt(sample.processed_elapsed_ns) >= BigInt(input.received_elapsed_ns));
    }
  });
}
