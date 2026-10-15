#!/usr/bin/env python3
"""Prepare a bounded, pseudonymized Polar regression fixture from a validated capture ZIP.

Standard library only. Originals are never changed, output never overwrites a file,
no raw identifiers/map/file paths are included, and no file is staged in Git.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import sqlite3
import struct
import zlib
import tempfile
import zipfile

from validate_polar_diagnostic import Invalid, MAX_ARCHIVE, SCHEMAS, parse_json, require, schema_check, validate, error_graphs

VERSION = '1.0.0'
MAX_EVENTS = 256
MAX_PROCESSING = 512
MAX_FIXTURE_BYTES = 1024 * 1024
EPOCH_MS = 1700000000000
ID_KEYS = {'capture_id', 'process_run_id', 'device_id', 'deviceId', 'address', 'session_id',
           'connected_device_id', 'command_id', 'subscription_id', 'argument'}
UUID = re.compile(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
SIG_UUID = re.compile(r'^0000[0-9a-f]{4}-0000-1000-8000-00805f9b34fb$')
MAC = re.compile(r'^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$')
SAFE_KEYS = {'firmware', 'FIRMWARE_REVISION', 'model', 'MODEL_NUMBER', 'serial', 'SERIAL_NUMBER'}
SAFE_FEATURES = {'FEATURE_HR', 'FEATURE_BATTERY_INFO', 'FEATURE_DEVICE_INFO'}
SAFE_ERRORS = {'unclean_shutdown', 'queue_overflow', 'serialization_failed', 'write_failed', 'size_limit',
               'duration_limit', 'processing_timeout', 'writer_failed', 'low_storage'}
ENUM_KEYS = {'code', 'source', 'event_type', 'callback_variant', 'outcome', 'reason', 'recoveryAction', 'deviceCommand',
             'chargingStatus', 'batteryPresent', 'wiredExternalPowerConnected', 'wirelessExternalPowerConnected',
             'session_state', 'state'}
LIMITATIONS = ['initial_snapshot_activation_unverified', 'commands_and_external_effects_not_proven_complete',
               'pseudonymization_is_not_anonymization']


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + '\n').encode('utf-8')


def digest_file(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()


def schema_hashes():
    return {name: digest_file(SCHEMAS / (name + '-v1.schema.json')) for name in ('event', 'processing', 'manifest')}


class Pseudonyms:
    def __init__(self, values):
        self.originals = set()
        self.strings = set()
        self.ids = {}
        self.texts = {}
        self.features = {}
        self.custom_uuids = {}
        def collect(value):
            if isinstance(value, dict):
                for key in sorted(value):
                    item = value[key]
                    if key in ID_KEYS and isinstance(item, str) and item:
                        self.originals.add(item)
                    collect(item)
            elif isinstance(value, list):
                for item in value:
                    collect(item)
            elif isinstance(value, str):
                self.strings.add(value)
        collect(values)
        for value in sorted(self.originals):
            index = len(self.ids) + 1
            while True:
                if MAC.fullmatch(value):
                    alias = '02:00:' + ':'.join(f'{(index >> shift) & 255:02x}' for shift in (24, 16, 8, 0))
                elif UUID.fullmatch(value):
                    alias = f'00000000-0000-4000-8000-{index:012x}'
                elif re.fullmatch('[0-9a-fA-F]{6,8}', value):
                    alias = 'F' + f'{index:0{len(value) - 1}X}'
                else:
                    alias = f'fixture-id-{index:04d}'
                if alias not in self.strings and alias not in self.ids.values():
                    break
                index += 1
            self.ids[value] = alias

    def text(self, value):
        if not value:
            return value
        if value in self.ids:
            return self.ids[value]
        if value not in self.texts:
            # Full text replacement removes identifiers that were never present in a typed ID field.
            index = len(self.texts) + 1
            while f'fixture-text-{index:04d}' in self.strings or f'fixture-text-{index:04d}' in self.texts.values():
                index += 1
            self.texts[value] = f'fixture-text-{index:04d}'
        return self.texts[value]

    def transform(self, value, delta, key='', parent=None):
        if isinstance(value, dict):
            return {k: self.transform(value[k], delta, k, value) for k in sorted(value)}
        if isinstance(value, list):
            return [self.transform(item, delta, key, parent) for item in value]
        if type(value) is int and key.endswith('_utc_ms'):
            return value + delta
        if not isinstance(value, str):
            return value
        if key in ID_KEYS:
            return self.ids[value] if value else value
        if key.endswith('_elapsed_ns') or key == 'ieee754_hex':
            return value
        if key in ENUM_KEYS:
            return value
        if key == 'name':
            if parent is not None and 'isConnectable' in parent:
                return 'Fixture Sense' if 'sense' in value.lower() else 'Fixture device'
            return value  # Schema-validated command name enum.
        if key == 'uuid':
            if SIG_UUID.fullmatch(value):
                return value  # Bluetooth characteristic type, not a device identity.
            if value not in self.custom_uuids:
                index = len(self.custom_uuids) + 1
                while f'00000000-0000-4001-8000-{index:012x}' in self.strings or f'00000000-0000-4001-8000-{index:012x}' in self.custom_uuids.values():
                    index += 1
                self.custom_uuids[value] = f'00000000-0000-4001-8000-{index:012x}'
            return self.custom_uuids[value]
        if key == 'last_error_code' and value in SAFE_ERRORS:
            return value
        if key == 'key' and value in SAFE_KEYS:
            return value
        if key in ('feature', 'ready', 'unavailable'):
            if value in SAFE_FEATURES:
                return value
            if value not in self.features:
                index = len(self.features) + 1
                while f'FEATURE_FIXTURE_{index:04d}' in self.strings or f'FEATURE_FIXTURE_{index:04d}' in self.features.values():
                    index += 1
                self.features[value] = f'FEATURE_FIXTURE_{index:04d}'
            return self.features[value]
        return self.text(value)


def check_fixture(fixture):
    schema_check(fixture, parse_json((SCHEMAS / 'fixture-v1.schema.json').read_bytes()))
    provenance = fixture['provenance']
    require(provenance['schema_sha256'] == schema_hashes(), 'fixture source schemas changed; review/migrate explicitly')
    event_schema = parse_json((SCHEMAS / 'event-v1.schema.json').read_bytes())
    processing_schema = parse_json((SCHEMAS / 'processing-v1.schema.json').read_bytes())
    manifest_schema = parse_json((SCHEMAS / 'manifest-v1.schema.json').read_bytes())
    initial = fixture['initial_state']; bounds = fixture['capture_bounds']
    schema_check(initial['configuration'], manifest_schema['properties']['initial_configuration'])
    checkpoint = provenance['source_completeness']['checkpoint']
    schema_check(checkpoint, manifest_schema['properties']['checkpoint'])
    require(initial['as_of_elapsed_ns'] == bounds['started_elapsed_ns'], 'initial state anchor mismatch')
    require((bounds['ended_utc_ms'] is None) == (bounds['ended_elapsed_ns'] is None), 'fixture source end null pairing')
    require(bounds['ended_elapsed_ns'] is None or int(bounds['ended_elapsed_ns']) >= int(bounds['started_elapsed_ns']), 'fixture source time bounds')
    last_hr = initial['configuration']['last_hr_received_elapsed_ns']
    age = None if last_hr is None else int(bounds['started_elapsed_ns']) - int(last_hr)
    require(age is None or age >= 0, 'initial HR is after capture start')
    require(initial['last_hr_age_ns'] == (None if age is None else str(age)), 'initial HR age mismatch')
    inputs, matched = {}, set()
    capture = process = None
    previous = previous_ns = -1
    for event in fixture['events']:
        schema_check(event, event_schema); error_graphs(event)
        seq, ns = event['sequence'], int(event['received_elapsed_ns'])
        require(seq > previous and ns >= previous_ns and ns >= int(bounds['started_elapsed_ns']), 'fixture input order/time')
        if bounds['ended_elapsed_ns'] is not None:
            require(ns <= int(bounds['ended_elapsed_ns']), 'fixture input past source end')
        previous, previous_ns = seq, ns
        capture = capture or event['capture_id']; process = process or event['process_run_id']
        require(event['capture_id'] == capture and event['process_run_id'] == process, 'fixture IDs differ')
        inputs[seq] = event
    previous = previous_ns = -1
    for row in fixture['processing']:
        schema_check(row, processing_schema); error_graphs(row)
        parent = inputs.get(row['input_sequence'])
        require(parent is not None, 'fixture orphan processing')
        require(row['capture_id'] == capture and row['process_run_id'] == process, 'fixture processing IDs differ')
        require(row['command_id'] == parent['payload'].get('command_id'), 'fixture command reference')
        seq, ns = row['processing_sequence'], int(row['processed_elapsed_ns'])
        require(seq > previous and ns >= previous_ns and ns >= int(parent['received_elapsed_ns']), 'fixture processing order/time')
        previous, previous_ns = seq, ns
        if parent['source'] != 'app_command':
            require(row['input_sequence'] not in matched and row['outcome'] in ('applied', 'ignored', 'failed'), 'fixture callback processing duplicated/invalid')
        matched.add(row['input_sequence'])
    events = fixture['events']; processing = fixture['processing']
    counts = provenance['retained_counts']
    require(counts == dict(events=len(events), processing=len(processing)), 'fixture retained counts')
    retained = dict(first=events[0]['sequence'] if events else None, last=events[-1]['sequence'] if events else None)
    require(provenance['retained_input_range'] == retained, 'fixture retained sequence bounds')
    requested = provenance['requested_input_range']
    if events:
        require(requested['first'] in inputs and requested['last'] == retained['last'], 'fixture selected boundaries')
        prehistory = sum(e['sequence'] < requested['first'] for e in events)
        require(provenance['prehistory_events'] == prehistory, 'fixture prehistory count')
        require(initial['focus_at_elapsed_ns'] == inputs[requested['first']]['received_elapsed_ns'], 'fixture focus time')
    else:
        require(requested == dict(first=None, last=None) and initial['focus_at_elapsed_ns'] is None, 'empty fixture selection')
        require(provenance['prehistory_events'] == 0, 'empty fixture prehistory')
    source = provenance['source_counts']; completeness = fixture['completeness']
    require(source['events'] - len(events) == provenance['omitted_after_events'] >= 0, 'fixture omitted input count')
    require(source['processing'] - len(processing) == provenance['omitted_processing'] >= 0, 'fixture omitted processing count')
    require(provenance['original_input_range']['first'] == retained['first'], 'fixture discarded required prefix')
    require(source['events'] == checkpoint['saved_events'], 'fixture source count/checkpoint')
    original = provenance['original_input_range']
    require(original['last'] == checkpoint['last_committed_sequence'], 'fixture original last sequence')
    if source['events']:
        require(bool(events) and original['first'] is not None and original['first'] <= original['last'], 'fixture missing original prefix')
        require(retained['last'] == original['last'] if len(events) == source['events'] else retained['last'] < original['last'], 'fixture original/retained end')
    else:
        require(original == dict(first=None, last=None), 'fixture empty original bounds')
    require(checkpoint.get('saved_processing', source['processing']) == source['processing'], 'fixture source processing checkpoint')
    if len(processing) == source['processing']:
        require((processing[-1]['processing_sequence'] if processing else None) == checkpoint['last_committed_processing_sequence'], 'fixture final processing sequence')
    if provenance['source_completeness']['state'] == 'complete':
        require(not checkpoint['unknown_tail'] and checkpoint['known_lost_events'] == 0 and checkpoint.get('known_lost_processing', 0) == 0 and
                checkpoint.get('checkpoint_persisted', True) and checkpoint.get('last_error_code') is None and bounds['ended_elapsed_ns'] is not None,
                'fixture falsely declares complete source')
    if provenance['source_completeness']['state'] == 'interrupted':
        require(checkpoint['unknown_tail'] and checkpoint['received_events'] is None and bounds['ended_elapsed_ns'] is None, 'fixture interrupted source knowledge')
    require(completeness['all_source_inputs_retained'] == (len(events) == source['events']), 'fixture input completeness')
    require(completeness['all_source_processing_retained'] == (len(processing) == source['processing']), 'fixture processing completeness')
    require(completeness['retained_unprocessed_inputs'] == len(inputs.keys() - matched), 'fixture unmatched count')
    require(completeness['source_complete'] == (provenance['source_completeness']['state'] == 'complete'), 'fixture source state')
    expected_limits = list(LIMITATIONS)
    if not completeness['source_complete']: expected_limits.append('source_capture_incomplete')
    if not completeness['all_source_inputs_retained']: expected_limits.append('input_suffix_omitted')
    if not completeness['all_source_processing_retained']: expected_limits.append('processing_omitted')
    if completeness['retained_unprocessed_inputs']: expected_limits.append('retained_inputs_without_processing')
    if events and provenance['omitted_after_events'] and any(int(p['processed_elapsed_ns']) > int(events[-1]['received_elapsed_ns']) for p in processing):
        expected_limits.append('retained_processing_after_last_retained_receipt')
    require(fixture['limitations'] == expected_limits, 'fixture hides completeness limitations')
    policy = provenance['utc_policy']
    require((policy['mode'] == 'preserve' and policy['target_start_utc_ms'] is None) or
            (policy['mode'] == 'shift_to_epoch' and policy['target_start_utc_ms'] == bounds['started_utc_ms']), 'fixture UTC policy')
    require(provenance['transformations'] == ['available_prefix_retained', 'identifiers_pseudonymized', 'free_text_replaced',
            'sense_name_filter_preserved', 'utc_preserved' if policy['mode'] == 'preserve' else 'utc_shifted_uniformly',
            'sequence_and_monotonic_time_unchanged', 'source_metadata_minimized'], 'fixture transformation list')
    require(fixture['replay_ready'] is False, 'D6 does not certify replay readiness')
    return dict(valid=True, fixture_version=1, scenario=fixture['scenario'], kind=fixture['kind'],
                events=len(events), processing=len(processing), source_state=provenance['source_completeness']['state'], replay_ready=False)


def prepare(source, *, kind, scenario, first=None, last=None, utc_start_ms=EPOCH_MS):
    require(kind in ('synthetic', 'hardware'), 'source kind must be explicit')
    require(re.fullmatch(r'[a-z][a-z0-9-]{0,63}', scenario) is not None, 'scenario must be a short non-identifying slug')
    with tempfile.TemporaryDirectory(prefix='polar-fixture-') as directory:
        snapshot = Path(directory) / 'source.zip'
        digest = hashlib.sha256(); total = 0
        with Path(source).open('rb') as original, snapshot.open('wb') as target:
            before = os.fstat(original.fileno())
            for block in iter(lambda: original.read(65536), b''):
                total += len(block); require(total <= MAX_ARCHIVE, 'source archive size limit')
                digest.update(block); target.write(block)
            after = os.fstat(original.fileno())
            require((before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns), 'source changed during read')
        report = validate(snapshot)  # Selection never bypasses validation of the entire original archive.
        with zipfile.ZipFile(snapshot) as archive:
            manifest = parse_json(archive.read('manifest.json'))
            maximum = manifest['checkpoint']['last_committed_sequence']
            end = maximum if last is None else last
            events = []
            with archive.open('events.jsonl') as stream:
                for line in stream:
                    event = parse_json(line)
                    if end is not None and event['sequence'] > end:
                        break
                    events.append(event)
                    require(len(events) <= MAX_EVENTS, 'required prefix exceeds 256 events; choose a smaller scenario, do not invent state')
            start = (events[0]['sequence'] if events else None) if first is None else first
            ids = {e['sequence'] for e in events}
            require((not events and first is None and last is None and maximum is None) or
                    (start in ids and end in ids and start <= end), 'selected boundaries must name saved inputs')
            processing = []
            with archive.open('processing.jsonl') as stream:
                for line in stream:
                    row = parse_json(line)
                    if row['input_sequence'] in ids:
                        processing.append(row)
                        require(len(processing) <= MAX_PROCESSING, 'fixture processing limit')
        configuration = manifest['initial_configuration']
        bounds = {k: manifest[k] for k in ('started_utc_ms', 'started_elapsed_ns', 'ended_utc_ms', 'ended_elapsed_ns')}
        checkpoint = manifest['checkpoint']
        pseudonyms = Pseudonyms([{k: manifest[k] for k in ('capture_id', 'process_run_id')}, configuration, checkpoint, events, processing])
        require(not any(len(raw) >= 4 and raw.lower() in scenario for raw in pseudonyms.originals), 'scenario contains a source identifier')
        delta = 0 if utc_start_ms is None else utc_start_ms - bounds['started_utc_ms']
        source_state = manifest['state']
        matched = {p['input_sequence'] for p in processing}
        completeness = dict(source_complete=source_state == 'complete', all_source_inputs_retained=len(events) == report['saved_events'],
                            all_source_processing_retained=len(processing) == report['saved_processing'], retained_unprocessed_inputs=len(ids - matched))
        limitations = list(LIMITATIONS)
        if not completeness['source_complete']: limitations.append('source_capture_incomplete')
        if not completeness['all_source_inputs_retained']: limitations.append('input_suffix_omitted')
        if not completeness['all_source_processing_retained']: limitations.append('processing_omitted')
        if completeness['retained_unprocessed_inputs']: limitations.append('retained_inputs_without_processing')
        if events and report['saved_events'] > len(events) and any(int(p['processed_elapsed_ns']) > int(events[-1]['received_elapsed_ns']) for p in processing):
            limitations.append('retained_processing_after_last_retained_receipt')
        last_hr = configuration['last_hr_received_elapsed_ns']
        age = None if last_hr is None else int(bounds['started_elapsed_ns']) - int(last_hr)
        fixture = dict(fixture_version=1, kind=kind, scenario=scenario, replay_ready=False,
                       provenance=dict(source_archive_sha256=digest.hexdigest(), source_schema_version=1, converter_version=VERSION,
                                       schema_sha256=schema_hashes(), origin_evidence='operator_declared_kind',
                                       original_input_range=dict(first=events[0]['sequence'] if events else None, last=maximum),
                                       requested_input_range=dict(first=start, last=end),
                                       retained_input_range=dict(first=events[0]['sequence'] if events else None, last=events[-1]['sequence'] if events else None),
                                       source_counts=dict(events=report['saved_events'], processing=report['saved_processing']),
                                       retained_counts=dict(events=len(events), processing=len(processing)),
                                       prehistory_events=sum(e['sequence'] < start for e in events),
                                       omitted_after_events=report['saved_events'] - len(events), omitted_processing=report['saved_processing'] - len(processing),
                                       source_completeness=dict(state=source_state, checkpoint=pseudonyms.transform(checkpoint, 0)),
                                       utc_policy=dict(mode='preserve' if utc_start_ms is None else 'shift_to_epoch', target_start_utc_ms=utc_start_ms),
                                       transformations=['available_prefix_retained', 'identifiers_pseudonymized', 'free_text_replaced', 'sense_name_filter_preserved',
                                                        'utc_preserved' if utc_start_ms is None else 'utc_shifted_uniformly', 'sequence_and_monotonic_time_unchanged', 'source_metadata_minimized']),
                       initial_state=dict(configuration=pseudonyms.transform(configuration, delta), as_of_elapsed_ns=bounds['started_elapsed_ns'],
                                          last_hr_age_ns=None if age is None else str(age), focus_at_elapsed_ns=next((e['received_elapsed_ns'] for e in events if e['sequence'] == start), None),
                                          confidence='source_metadata_unverified', context_policy='full_available_prefix'),
                       capture_bounds=pseudonyms.transform(bounds, delta), events=pseudonyms.transform(events, delta),
                       processing=pseudonyms.transform(processing, delta), completeness=completeness, limitations=limitations)
        check_fixture(fixture)
        require(len(canonical(fixture)) <= MAX_FIXTURE_BYTES, 'fixture exceeds 1 MiB; select a smaller scenario')
        return fixture


def write_new(path, data):
    path = Path(path)
    # Hard-link publication gives atomic no-clobber behavior, including a destination created concurrently.
    require(path.suffix == '.json', 'fixture output must be JSON')
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.polar-fixture-', delete=False) as stream:
        temp = Path(stream.name)
        try:
            stream.write(data); stream.flush(); os.fsync(stream.fileno()); stream.close()
            os.link(temp, path)
        finally:
            temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='operation', required=True)
    convert = commands.add_parser('prepare')
    convert.add_argument('archive', type=Path)
    convert.add_argument('--output', required=True, type=Path)
    convert.add_argument('--kind', required=True, choices=('synthetic', 'hardware'))
    convert.add_argument('--scenario', required=True)
    convert.add_argument('--from-sequence', type=int)
    convert.add_argument('--to-sequence', type=int)
    clock = convert.add_mutually_exclusive_group()
    clock.add_argument('--utc-start-ms', type=int, default=EPOCH_MS)
    clock.add_argument('--keep-utc', action='store_true')
    check = commands.add_parser('check'); check.add_argument('fixture', type=Path)
    check.add_argument('--source', type=Path, help='Reproduce transformations against the private original without publishing its identifiers')
    args = parser.parse_args()
    try:
        if args.operation == 'check':
            require(args.fixture.stat().st_size <= MAX_FIXTURE_BYTES, 'fixture size limit')
            fixture = parse_json(args.fixture.read_bytes())
            result = check_fixture(fixture)
            result['source_reproduced'] = False
            if args.source is not None:
                selection = fixture['provenance']['requested_input_range']
                clock = fixture['provenance']['utc_policy']
                expected = prepare(args.source, kind=fixture['kind'], scenario=fixture['scenario'], first=selection['first'], last=selection['last'],
                                   utc_start_ms=clock['target_start_utc_ms'])
                require(canonical(expected) == canonical(fixture), 'fixture does not reproduce from this source')
                result['source_reproduced'] = True
        else:
            require(not args.output.exists(), 'output already exists')
            fixture = prepare(args.archive, kind=args.kind, scenario=args.scenario, first=args.from_sequence, last=args.to_sequence,
                              utc_start_ms=None if args.keep_utc else args.utc_start_ms)
            write_new(args.output, canonical(fixture)); result = check_fixture(fixture)
        print(json.dumps(result))
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, RecursionError, zipfile.BadZipFile, sqlite3.Error, struct.error, zlib.error) as error:
        reason = str(error) if isinstance(error, Invalid) else type(error).__name__
        print(json.dumps(dict(valid=False, error=reason)), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
