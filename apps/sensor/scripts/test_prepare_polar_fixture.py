import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import test_validate_polar_diagnostic as archive_tests
from prepare_polar_fixture import prepare, canonical, check_fixture, write_new, Invalid, digest_file, Pseudonyms


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.builder = archive_tests.ValidationTest()
        self.builder.setUp()
        self.addCleanup(self.builder.doCleanups)
        self.path = self.builder.path

    def records(self, count=7):
        events = copy.deepcopy(archive_tests.EXAMPLES['events'][:count])
        processing = []
        for i, event in enumerate(events):
            event['received_elapsed_ns'] = str(int(event['received_elapsed_ns']) + 10_000_000_000)
            row = copy.deepcopy(archive_tests.EXAMPLES['processing'][0])
            row.update(processing_sequence=i + 1, input_sequence=event['sequence'], processed_elapsed_ns=str(12_000_000_000 + i),
                       command_id=event['payload'].get('command_id'), outcome='committed' if event['source'] == 'app_command' else 'applied')
            processing.append(row)
        return events, processing

    def make(self, events=None, processing=None, state='complete', mutate=None):
        def metadata(m):
            m['started_elapsed_ns'] = '10000000000'; m['ended_elapsed_ns'] = None if state == 'interrupted' else '20000000000'
            if mutate: mutate(m)
        return self.builder.make(events, processing, state=state, mutate=metadata)

    def convert(self, **kwargs):
        return prepare(self.path, kind='synthetic', scenario='sample', **kwargs)

    def test_deterministic_conversion_and_atomic_no_overwrite_preserve_original(self):
        events, processing = self.records(); self.make(events, processing)
        before = self.path.read_bytes()
        one, two = self.convert(), self.convert()
        self.assertEqual(canonical(one), canonical(two)); self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(digest_file(self.path), one['provenance']['source_archive_sha256'])
        self.assertEqual('1.0.0', one['provenance']['converter_version'])
        self.assertNotIn('source.zip', canonical(one).decode())
        output = self.path.with_name('fixture.json'); write_new(output, canonical(one))
        with self.assertRaises(FileExistsError): write_new(output, b'overwrite')
        self.assertEqual(output.read_bytes(), canonical(one))
        self.assertEqual([], list(output.parent.glob('.polar-fixture-*')))

    def test_focus_keeps_entire_prefix_and_paused_session_settings_and_hr_age(self):
        events, processing = self.records()
        def initial(m):
            m['initial_configuration'].update(session_state='paused', session_id='PRIVATESESSION99', connected_device_id='PRIVATEDEVICE99',
                                              route_enabled=True, automatic_reconnection=False, hr_stale_after_ms=4200, active_ms=17000,
                                              last_hr=81, last_hr_received_elapsed_ns='4000000000')
        self.make(events, processing, mutate=initial)
        result = self.convert(first=6, last=6)
        provenance, initial = result['provenance'], result['initial_state']
        self.assertEqual([1, 2, 3, 4, 5, 6], [e['sequence'] for e in result['events']])
        self.assertEqual(dict(first=6, last=6), provenance['requested_input_range'])
        self.assertEqual(5, provenance['prehistory_events']); self.assertEqual(1, provenance['omitted_after_events'])
        self.assertEqual('6000000000', initial['last_hr_age_ns'])
        for key, value in dict(session_state='paused', route_enabled=True, automatic_reconnection=False,
                               hr_stale_after_ms=4200, active_ms=17000, last_hr=81).items():
            self.assertEqual(value, initial['configuration'][key])
        self.assertNotEqual('PRIVATESESSION99', initial['configuration']['session_id'])
        self.assertIn('retained_processing_after_last_retained_receipt', result['limitations'])
        self.assertFalse(result['replay_ready'])

    def test_identifiers_and_unknown_embedded_free_text_are_removed_consistently(self):
        events, processing = self.records(21)
        secret = 'PRIVATEDEVICE99'
        for event in events:
            if event['device_id'] is not None: event['device_id'] = secret
            payload = event['payload']
            if 'info' in payload:
                payload['info'].update(deviceId=secret, address='AB:CD:EF:12:34:56', name='UserName987 Sense ' + secret)
            if event['event_type'] == 'device_info': payload['value'] = 'HiddenSerial987 ' + secret
            if 'error' in payload:
                node = payload['error']['nodes'][0]
                node['message'] = 'email@example.test and EmbeddedOnlySECRET543 ' + secret
                node['localized_message'] = ''
                node['class_name'] = 'private.UserName987Error'
                node['stack_trace'] = [dict(declaring_class='private.' + secret, method_name='methodPrivateName987', file_name='/home/PrivateName987/Source.java', line_number=42, native_method=False)]
            if event['source'] == 'app_command':
                payload['name'] = 'connect'; payload['argument'] = secret
        self.make(events, processing, state='incomplete')
        result = self.convert(); text = canonical(result).decode()
        for value in (secret, 'UserName987', 'HiddenSerial987', 'AB:CD:EF:12:34:56', 'email@example.test', 'EmbeddedOnlySECRET543', 'PrivateName987', 'synthetic-capture', 'synthetic-process'):
            self.assertNotIn(value, text)
        connected = result['events'][2]
        self.assertEqual(connected['device_id'], connected['payload']['info']['deviceId'])
        self.assertEqual(connected['device_id'], result['events'][20]['payload']['argument'])
        self.assertEqual(result['events'][20]['payload']['command_id'], result['processing'][20]['command_id'])
        self.assertEqual('Fixture Sense', connected['payload']['info']['name'])
        self.assertEqual('', result['events'][18]['payload']['error']['nodes'][0]['localized_message'])
        self.assertEqual(events[5]['payload'], result['events'][5]['payload'])
        self.assertEqual(events[13]['payload'], result['events'][13]['payload'])
        check_fixture(result)

    def test_shift_and_preserve_utc_keep_exact_jumps_monotonic_clocks_and_sequence(self):
        events, processing = self.records()
        events[2]['received_utc_ms'] -= 15000
        self.make(events, processing)
        result = self.convert(utc_start_ms=2000000000000)
        self.assertEqual(2000000000000, result['capture_bounds']['started_utc_ms'])
        for before, after in zip(events, result['events']):
            self.assertEqual(before['sequence'], after['sequence']); self.assertEqual(before['received_elapsed_ns'], after['received_elapsed_ns'])
        for before, after in zip(processing, result['processing']):
            self.assertEqual(before['processed_elapsed_ns'], after['processed_elapsed_ns'])
            self.assertEqual(before['processing_sequence'], after['processing_sequence'])
        for index in range(1, len(events)):
            self.assertEqual(events[index]['received_utc_ms'] - events[index - 1]['received_utc_ms'],
                             result['events'][index]['received_utc_ms'] - result['events'][index - 1]['received_utc_ms'])
        kept = self.convert(utc_start_ms=None)
        self.assertEqual([e['received_utc_ms'] for e in events], [e['received_utc_ms'] for e in kept['events']])

    def test_interrupted_and_incomplete_source_knowledge_is_not_hidden_by_selection(self):
        events, _ = self.records(1)
        for state in ('interrupted', 'incomplete'):
            def loss(m):
                m['checkpoint'].update(known_lost_processing=1, unprocessed_inputs=1, saved_processing=0, last_error_code='private-error-secret')
            self.make(events, state=state, mutate=loss)
            result = self.convert()
            self.assertEqual(state, result['provenance']['source_completeness']['state'])
            cp = result['provenance']['source_completeness']['checkpoint']
            self.assertEqual(1, cp['known_lost_processing']); self.assertEqual(1, cp['unprocessed_inputs'])
            self.assertEqual(1, result['completeness']['retained_unprocessed_inputs'])
            self.assertIn('source_capture_incomplete', result['limitations'])
            self.assertNotIn('private-error-secret', canonical(result).decode())
            if state == 'interrupted':
                self.assertIsNone(cp['received_events']); self.assertIsNone(result['capture_bounds']['ended_elapsed_ns'])
                self.assertTrue(cp['unknown_tail'])

    def test_empty_source_and_bad_boundaries_or_too_large_context_fail_explicitly(self):
        self.make(); self.assertEqual([], self.convert()['events'])
        with self.assertRaises(Invalid): self.convert(first=1)
        events, processing = self.records()
        self.make(events, processing)
        for kwargs in (dict(first=9), dict(first=7, last=6), dict(last=8), dict(first=0)):
            with self.assertRaises(Invalid): self.convert(**kwargs)
        event, processed = events[0], processing[0]
        events = [dict(event, sequence=i) for i in range(1, 258)]
        processing = [dict(processed, processing_sequence=i, input_sequence=i) for i in range(1, 258)]
        self.make(events, processing)
        with self.assertRaisesRegex(Invalid, 'prefix'): self.convert(first=257)
        small = self.convert(first=2, last=2)
        self.assertEqual(2, len(small['events'])); self.assertEqual(255, small['provenance']['omitted_after_events'])
        self.make(events, processing, mutate=lambda m: m['files']['events.jsonl'].update(sha256='0' * 64))
        with self.assertRaises(Invalid): self.convert(last=2)

    def test_fixture_tampering_is_rejected(self):
        events, processing = self.records(); self.make(events, processing)
        fixture = self.convert()
        changes = [lambda f: f.update(fixture_version=2), lambda f: f.update(replay_ready=True),
                   lambda f: f['processing'][0].update(input_sequence=999),
                   lambda f: f['events'][1].update(sequence=1),
                   lambda f: f['provenance'].update(prehistory_events=3),
                   lambda f: f['completeness'].update(retained_unprocessed_inputs=1),
                   lambda f: f.update(limitations=[]),
                   lambda f: f['provenance']['schema_sha256'].update(event='0' * 64)]
        for change in changes:
            wrong = copy.deepcopy(fixture); change(wrong)
            with self.assertRaises(Invalid): check_fixture(wrong)

    def test_pseudonyms_do_not_collide_with_source_placeholder_names_and_empty_is_preserved(self):
        p = Pseudonyms([dict(device_id='fixture-id-0001', session_id='00000000-0000-4000-8000-000000000001'),
                        dict(value='fixture-text-0001', feature='FEATURE_FIXTURE_0001', uuid='00000000-0000-4001-8000-000000000001')])
        for value, key in [('fixture-id-0001', 'device_id'), ('fixture-text-0001', 'value'), ('FEATURE_FIXTURE_0001', 'feature'), ('00000000-0000-4001-8000-000000000001', 'uuid')]:
            self.assertNotEqual(value, p.transform(value, 0, key))
        self.assertEqual('', p.transform('', 0, 'argument')); self.assertIsNone(p.transform(None, 0, 'message'))

    def test_all_contract_variants_include_lifecycle_fault_and_command_identifiers(self):
        events, processing = self.records(24)
        self.make(events, processing, state='incomplete', mutate=lambda m: m['checkpoint'].update(last_error_code='write_failed'))
        fixture = self.convert()
        self.assertEqual(24, len(fixture['events']))
        self.assertEqual(events[23]['payload']['code'], fixture['events'][23]['payload']['code'])
        self.assertEqual('write_failed', fixture['provenance']['source_completeness']['checkpoint']['last_error_code'])

    def test_recording_initial_state_keeps_nanosecond_hr_age_and_unknown_age_is_not_zero(self):
        events, processing = self.records()
        for timestamp, age in [('9999999999', '1'), (None, None)]:
            self.make(events, processing, mutate=lambda m: m['initial_configuration'].update(
                session_state='recording', session_id='PRIVATESESSION99', connected_device_id='PRIVATEDEVICE99',
                active_ms=8000, last_hr=87, last_hr_received_elapsed_ns=timestamp))
            initial = self.convert()['initial_state']
            self.assertEqual('recording', initial['configuration']['session_state'])
            self.assertEqual(8000, initial['configuration']['active_ms'])
            self.assertEqual(age, initial['last_hr_age_ns'])

    def test_valid_large_numeric_payload_is_rejected_instead_of_truncated_and_utc_cannot_overflow(self):
        events, processing = self.records()
        self.make(events, processing)
        with self.assertRaises(Invalid): self.convert(utc_start_ms=9007199254740991)
        hr, template = events[5], processing[5]
        hr['payload']['rrs'] = [1024] * 5000; hr['payload']['rrsMs'] = [1000] * 5000
        events = [dict(hr, sequence=i) for i in range(1, 11)]
        processing = [dict(template, input_sequence=i, processing_sequence=i) for i in range(1, 11)]
        self.make(events, processing)
        with self.assertRaisesRegex(Invalid, '1 MiB'): self.convert()

    def test_command_accepted_and_committed_phases_keep_one_pseudonym_and_reference(self):
        events, processing = self.records(21)
        command, row = events[-1], processing[-1]
        accepted = dict(row, processing_sequence=1, outcome='accepted')
        committed = dict(row, processing_sequence=2, outcome='committed', processed_elapsed_ns=str(int(row['processed_elapsed_ns']) + 1))
        self.make([command], [accepted, committed])
        result = self.convert()
        self.assertEqual(['accepted', 'committed'], [p['outcome'] for p in result['processing']])
        command_id = result['events'][0]['payload']['command_id']
        self.assertTrue(all(p['command_id'] == command_id and p['input_sequence'] == command['sequence'] for p in result['processing']))
        self.assertEqual(0, result['completeness']['retained_unprocessed_inputs'])

    def test_cli_reproduction_and_no_overwrite(self):
        events, processing = self.records(); self.make(events, processing)
        tool = str(Path(__file__).with_name('prepare_polar_fixture.py'))
        output = self.path.with_name('result.json')
        command = [sys.executable, tool, 'prepare', str(self.path), '--output', str(output), '--kind', 'synthetic', '--scenario', 'test-cli']
        self.assertEqual(0, subprocess.run(command, capture_output=True).returncode)
        check = subprocess.run([sys.executable, tool, 'check', str(output), '--source', str(self.path)], capture_output=True, text=True)
        self.assertEqual(0, check.returncode, check.stderr); self.assertTrue(json.loads(check.stdout)['source_reproduced'])
        self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)
        fixture = json.loads(output.read_text()); fixture['events'][0]['payload']['powered'] = True
        output.write_text(json.dumps(fixture))
        check = subprocess.run([sys.executable, tool, 'check', str(output), '--source', str(self.path)], capture_output=True)
        self.assertNotEqual(0, check.returncode)


if __name__ == '__main__': unittest.main()
