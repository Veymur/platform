import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from validate_polar_diagnostic import validate, schema_check, Invalid, SCHEMAS

EXAMPLES = json.loads((SCHEMAS.parent / 'fixtures/synthetic/contract-examples.json').read_text())


class ValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'capture.zip'

    def make(self, events=None, processing=None, state='complete', mutate=None, blobs=None, compression=zipfile.ZIP_DEFLATED):
        events = copy.deepcopy(events or [])
        processing = copy.deepcopy(processing or [])
        manifest = copy.deepcopy(EXAMPLES['manifest'])
        manifest['capture_id'] = 'synthetic-capture'
        manifest['ended_elapsed_ns'] = '2000000000'
        manifest['state'] = state
        if state == 'interrupted':
            manifest['ended_elapsed_ns'] = manifest['ended_utc_ms'] = None
        cp = manifest['checkpoint']
        cp.update(saved_events=len(events), received_events=None if state == 'interrupted' else len(events),
                  last_committed_sequence=events[-1]['sequence'] if events else None,
                  last_committed_processing_sequence=processing[-1]['processing_sequence'] if processing else None,
                  unknown_tail=state == 'interrupted')
        manifest['firmware'] = [dict(device_id=e['device_id'], uuid=e['payload'].get('uuid'), key=e['payload'].get('key'),
                                     value=e['payload']['value'], input_sequence=e['sequence']) for e in events
                                if e['event_type'] == 'device_info' and (e['payload'].get('key') == 'firmware' or e['payload'].get('uuid') == '00002a26-0000-1000-8000-00805f9b34fb')]
        data = {'events.jsonl': b''.join((json.dumps(e, ensure_ascii=False) + '\n').encode() for e in events),
                'processing.jsonl': b''.join((json.dumps(e) + '\n').encode() for e in processing)}
        if blobs:
            data.update(blobs)
        for name, content in data.items():
            manifest['files'][name] = dict(bytes=len(content), records=content.count(b'\n'), sha256=hashlib.sha256(content).hexdigest())
        if mutate:
            mutate(manifest)
        data['manifest.json'] = json.dumps(manifest).encode()
        with zipfile.ZipFile(self.path, 'w', compression=compression) as archive:
            for name, content in data.items():
                archive.writestr(name, content)
        return data

    def pair(self, index=5):
        event = copy.deepcopy(EXAMPLES['events'][index])
        processed = copy.deepcopy(EXAMPLES['processing'][0])
        processed.update(input_sequence=event['sequence'], command_id=None, outcome='applied')
        return event, processed

    def test_empty_and_complete_raw_values_and_clock_jumps(self):
        self.make(); self.assertEqual(0, validate(self.path)['saved_events'])
        event, processed = self.pair()
        event['received_utc_ms'] = 1700000010000
        processed['processed_utc_ms'] = 1600000000000
        self.make([event], [processed], compression=zipfile.ZIP_STORED)
        self.assertEqual('complete', validate(self.path)['state'])
        self.assertFalse(validate(self.path)['replay_ready'])

    def test_interrupted_and_processing_loss_report_not_false_complete(self):
        event, _ = self.pair()
        self.make([event], state='interrupted')
        self.assertTrue(validate(self.path)['unknown_tail'])
        self.make([event], state='incomplete', mutate=lambda m: m['checkpoint'].update(known_lost_processing=1, saved_processing=0, unprocessed_inputs=1))
        self.assertEqual(1, validate(self.path)['known_lost_processing'])
        self.make([event])
        with self.assertRaises(Invalid): validate(self.path)

    def test_all_payload_variants_and_strict_schema_required_fields(self):
        schemas = {name: json.loads((SCHEMAS / (name + '-v1.schema.json')).read_text()) for name in ('event', 'processing', 'manifest')}
        for name in schemas:
            examples = EXAMPLES[name + 's'] if name == 'event' else EXAMPLES['processing'] if name == 'processing' else [EXAMPLES['manifest']]
            for example in examples:
                schema_check(example, schemas[name])
                for field in example:
                    wrong = copy.deepcopy(example); del wrong[field]
                    with self.assertRaises(Invalid): schema_check(wrong, schemas[name])
        events = copy.deepcopy(EXAMPLES['events'])
        processing = []
        for i, event in enumerate(events):
            row = copy.deepcopy(EXAMPLES['processing'][0])
            row.update(processing_sequence=i + 1, input_sequence=event['sequence'], command_id=event['payload'].get('command_id'),
                       outcome='committed' if event['source'] == 'app_command' else 'ignored')
            processing.append(row)
        self.make(events, processing, state='incomplete')
        self.assertEqual(24, validate(self.path)['saved_events'])

    def test_command_phases_are_ordered_and_complete_requires_terminal_result(self):
        event = copy.deepcopy(next(e for e in EXAMPLES['events'] if e['source'] == 'app_command'))
        accepted = copy.deepcopy(EXAMPLES['processing'][0])
        accepted.update(input_sequence=event['sequence'], command_id=event['payload']['command_id'], outcome='accepted')
        committed = dict(accepted, processing_sequence=accepted['processing_sequence'] + 1, outcome='committed')
        self.make([event], [accepted, committed])
        self.assertTrue(validate(self.path)['valid'])
        for phases in ([accepted], [committed, dict(accepted, processing_sequence=committed['processing_sequence'] + 1)],
                       [accepted, dict(committed, outcome='accepted')], [accepted, dict(committed, outcome='applied')]):
            self.make([event], phases)
            with self.assertRaises(Invalid): validate(self.path)
        self.make([event], [accepted], state='interrupted')
        self.assertTrue(validate(self.path)['unknown_tail'])

    def test_bad_hash_counts_version_fields_and_firmware(self):
        event, processed = self.pair(10)
        mutations = [lambda m: m.update(schema_version=2), lambda m: m.update(unexpected=True),
                     lambda m: m['files']['events.jsonl'].update(sha256='0' * 64),
                     lambda m: m['files']['events.jsonl'].update(records=2),
                     lambda m: m['checkpoint'].update(saved_events=3),
                     lambda m: m['checkpoint'].update(last_committed_sequence=999),
                     lambda m: m['firmware'][0].update(value='wrong'), lambda m: m.update(firmware=[]),
                     lambda m: m.update(ended_elapsed_ns='1')]
        for mutate in mutations:
            self.make([event], [processed], mutate=mutate)
            with self.assertRaises(Invalid): validate(self.path)

    def test_bad_sequence_process_parent_time_and_throwable_references(self):
        event, processed = self.pair()
        for key, value in [('process_run_id', 'other'), ('capture_id', 'other'), ('processed_elapsed_ns', '1'), ('input_sequence', 999), ('command_id', 'wrong')]:
            wrong = dict(processed, **{key: value}); self.make([event], [wrong])
            with self.assertRaises(Invalid): validate(self.path)
        duplicate = dict(processed, processing_sequence=2)
        self.make([event], [processed, duplicate])
        with self.assertRaises(Invalid): validate(self.path)
        error_event = copy.deepcopy(EXAMPLES['events'][18])
        error_event['payload']['error']['nodes'][0]['cause_id'] = 999
        self.make([error_event], state='interrupted')
        with self.assertRaises(Invalid): validate(self.path)
        self.make([event, event], state='interrupted')
        with self.assertRaises(Invalid): validate(self.path)

    def test_utf8_duplicate_keys_line_ending_and_line_limit(self):
        for blob in [b'\xff\n', b'{}', b'{}\r\n', b'{"a":1,"a":2}\n', b'\xef\xbb\xbf{}\n', b'x' * (1024 * 1024 + 1) + b'\n']:
            self.make(state='interrupted', blobs={'events.jsonl': blob})
            with self.assertRaises(Invalid): validate(self.path)

    def test_truncation_prefix_suffix_crc_extra_entries_and_unknown_compression(self):
        self.make(); good = self.path.read_bytes()
        for wrong in [good[:-1], b'prefix' + good, good + b'tail', good[:45] + b'broken' + good[51:]]:
            self.path.write_bytes(wrong)
            with self.assertRaises((Invalid, zipfile.BadZipFile)): validate(self.path)
        for extra in ['../outside', 'manifest.json', 'extra.json']:
            self.make()
            with zipfile.ZipFile(self.path, 'a') as archive: archive.writestr(extra, '{}')
            with self.assertRaises(Invalid): validate(self.path)
        self.make(compression=zipfile.ZIP_BZIP2)
        with self.assertRaises(Invalid): validate(self.path)

    def test_hidden_bytes_inside_deflate_stream_are_rejected(self):
        self.make()
        data = bytearray(self.path.read_bytes())
        with zipfile.ZipFile(self.path) as archive:
            infos, central = archive.infolist(), archive.start_dir
        first = infos[0]
        local_name, local_extra = struct.unpack_from('<2H', data, 26)
        insertion = 30 + local_name + local_extra + first.compress_size
        struct.pack_into('<L', data, 18, first.compress_size + 1)
        cursor = central
        for info in infos:
            if info is first:
                struct.pack_into('<L', data, cursor + 20, info.compress_size + 1)
            elif info.header_offset >= insertion:
                struct.pack_into('<L', data, cursor + 42, info.header_offset + 1)
            name, extra, comment = struct.unpack_from('<3H', data, cursor + 28)
            cursor += 46 + name + extra + comment
        struct.pack_into('<L', data, len(data) - 6, central + 1)
        data[insertion:insertion] = b'X'
        self.path.write_bytes(data)
        with self.assertRaisesRegex(Invalid, 'compressed'):
            validate(self.path)

    def test_large_streaming_reference_index(self):
        event, processed = self.pair()
        events, processing = [], []
        for i in range(1, 5001):
            events.append(dict(event, sequence=i))
            processing.append(dict(processed, processing_sequence=i, input_sequence=i))
        self.make(events, processing)
        self.assertEqual(5000, validate(self.path)['saved_events'])


if __name__ == '__main__': unittest.main()
