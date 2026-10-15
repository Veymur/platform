const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync, readdirSync } = require('node:fs');
const { join } = require('node:path');
const { createRequire } = require('node:module');
const Ajv = createRequire(require.resolve('eslint/package.json'))('ajv');
const root = join(__dirname, '../diagnostics');
const read = file => JSON.parse(readFileSync(file, 'utf8'));
const ajv = new Ajv({ allErrors: true });
const validators = Object.fromEntries(['fixture', 'event', 'processing'].map(name =>
  [name, ajv.compile(read(join(root, `schema/${name}-v1.schema.json`)))]));

for (const kind of ['synthetic', 'hardware']) {
  const directory = join(root, 'fixtures', kind);
  for (const name of readdirSync(directory).filter(name => name.endsWith('.fixture.json'))) {
    test(`D6 ${kind}: ${name} satisfies independent schemas and separately authored data expectations`, () => {
      const fixture = read(join(directory, name));
      assert.equal(fixture.kind, kind);
      assert.ok(validators.fixture(fixture), JSON.stringify(validators.fixture.errors));
      assert.equal(fixture.replay_ready, false);
      for (const event of fixture.events) assert.ok(validators.event(event), JSON.stringify(validators.event.errors));
      for (const processing of fixture.processing) assert.ok(validators.processing(processing), JSON.stringify(validators.processing.errors));
      const expected = read(join(directory, name.replace('.fixture.json', '.expected.json')));
      assert.equal(expected.fixture, name); assert.equal(expected.expectations_version, 1);
      assert.equal(expected.observed_processing_is_not_oracle, true); assert.equal(expected.replay_assertions_executed, true);
      assert.equal(expected.replay_runner, 'modules/veymur-recorder/tests/android/com/veymur/recorder/core/PolarReplayTest.kt');
      assert.equal(expected.native_replay_assertions.session_state, 'preparation');
      assert.equal(expected.native_replay_assertions.hr_count, 0);
      const basis = new Set(expected.basis.map(item => item.id));
      assert.equal(basis.size, expected.basis.length);
      for (const expectation of expected.data_assertions) {
        assert.ok(basis.has(expectation.basis));
        const value = expectation.path.split('.').reduce((node, field) => node[field], fixture);
        assert.ok(['length', 'equals'].includes(expectation.operation));
        assert.deepEqual(expectation.operation === 'length' ? value.length : value, expectation.value, expectation.path);
      }
      assert.ok(expected.replay_expectations.length > 0);
      for (const expectation of expected.replay_expectations) assert.ok(basis.has(expectation.basis));
    });
  }
  test(`D6 ${kind}: original ZIP archives are not included in the catalogue`, () => {
    assert.equal(readdirSync(directory, { recursive: true }).some(name => String(name).endsWith('.zip')), false);
  });
}
