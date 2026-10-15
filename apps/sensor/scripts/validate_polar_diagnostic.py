#!/usr/bin/env python3
"""Validate Polar diagnostic ZIP v1 independently of Android/Kotlin; Python standard library only.

Reads repository JSON schemas (the closed draft-07 subset they use), ZIP boundaries/CRC,
exact UTF-8 JSONL hashes, references, monotonic clocks and completeness. Never extracts files
or prints raw payloads. Temporary SQLite indexes bound reference-checking memory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import struct
import sys
import tempfile
import zipfile
import zlib

SCHEMAS = Path(__file__).resolve().parents[1] / 'diagnostics' / 'schema'
MAX_ARCHIVE = 96 * 1024 * 1024
MAX_MEMBER = 32 * 1024 * 1024
MAX_LINE = 1024 * 1024
NAMES = {'manifest.json', 'events.jsonl', 'processing.jsonl'}


class Invalid(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Invalid(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def parse_json(data):
    try:
        return json.loads(data.decode('utf-8'), object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(Invalid('non-finite JSON number')))
    except (UnicodeError, ValueError, RecursionError) as error:
        raise Invalid('invalid UTF-8/JSON') from error


# Deliberately small, fail-closed evaluator for the keywords used in the checked-in schemas.
# It shares data definitions with Kotlin's contract, not Kotlin's serializers or validation code.
def schema_check(value, schema, path='$'):
    allowed = {'$schema', 'title', 'type', 'const', 'enum', 'oneOf', 'anyOf', 'properties', 'required',
               'additionalProperties', 'minimum', 'maximum', 'pattern', 'items', 'minItems', 'maxItems', 'uniqueItems'}
    require(not set(schema) - allowed, 'unsupported schema keyword')
    for keyword in ('oneOf', 'anyOf'):
        if keyword in schema:
            valid = 0
            for branch in schema[keyword]:
                try:
                    schema_check(value, branch, path)
                    valid += 1
                except Invalid:
                    pass
            require(valid == 1 if keyword == 'oneOf' else valid > 0, path + ': ' + keyword)
    types = schema.get('type')
    if types is not None:
        types = types if isinstance(types, list) else [types]
        matches = {'object': isinstance(value, dict), 'array': isinstance(value, list),
                   'integer': type(value) is int, 'string': isinstance(value, str),
                   'boolean': type(value) is bool, 'null': value is None}
        require(any(matches.get(kind, False) for kind in types), path + ': type')
    # JSON equality distinguishes booleans from numbers.
    same = lambda a, b: json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)
    if 'const' in schema:
        require(same(value, schema['const']), path + ': const')
    if 'enum' in schema:
        require(any(same(value, choice) for choice in schema['enum']), path + ': enum')
    if isinstance(value, dict):
        properties = schema.get('properties', {})
        require(set(schema.get('required', [])) <= value.keys(), path + ': required')
        if schema.get('additionalProperties') is False:
            require(not value.keys() - properties.keys(), path + ': unexpected field')
        for key in value.keys() & properties.keys():
            schema_check(value[key], properties[key], path + '.' + key)
    if isinstance(value, list):
        require(len(value) >= schema.get('minItems', 0), path + ': minItems')
        require(len(value) <= schema.get('maxItems', MAX_ARCHIVE), path + ': maxItems')
        if schema.get('uniqueItems'):
            require(len({json.dumps(item, sort_keys=True) for item in value}) == len(value), path + ': uniqueItems')
        if 'items' in schema:
            for item in value:
                schema_check(item, schema['items'], path + '[]')
    if type(value) is int:
        require(value >= schema.get('minimum', value) and value <= schema.get('maximum', value), path + ': bounds')
    if isinstance(value, str) and 'pattern' in schema:
        require(re.search(schema['pattern'], value) is not None, path + ': pattern')


def zip_boundaries(path, archive):
    size = path.stat().st_size
    require(22 <= size <= MAX_ARCHIVE, 'archive size limit')
    with path.open('rb') as raw:
        raw.seek(-22, 2)
        end = struct.unpack('<4s4H2LH', raw.read(22))
        require(end[0] == b'PK\x05\x06' and end[-1] == 0, 'missing/truncated EOCD or trailing bytes/comment')
        require(end[1:5] == (0, 0, 3, 3), 'unsupported ZIP disks/entry count')
        central_size, central_at = end[5:7]
        require(central_at + central_size == size - 22, 'ZIP directory boundary')
        infos = archive.infolist()
        require(len(infos) == 3 and {i.filename for i in infos} == NAMES, 'ZIP must contain exactly three distinct contract files')
        require(sum(i.file_size for i in infos) <= MAX_ARCHIVE, 'uncompressed size limit')
        offset = 0
        for info in sorted(infos, key=lambda i: i.header_offset):
            require(info.header_offset == offset, 'ZIP local boundary/prefix/overlap')
            require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), 'unsupported ZIP compression')
            require(not info.flag_bits & ~0x808 and not info.is_dir(), 'unsupported ZIP flags')
            require(0 <= info.file_size <= MAX_MEMBER, 'member size limit')
            require((info.external_attr >> 16) & 0o170000 != 0o120000, 'ZIP symlink')
            raw.seek(offset)
            header = raw.read(30)
            require(len(header) == 30, 'truncated local header')
            fields = struct.unpack('<4s5H3L2H', header)
            require(fields[0] == b'PK\x03\x04' and fields[2] == info.flag_bits and fields[3] == info.compress_type, 'local/central header mismatch')
            name, extra = raw.read(fields[-2]), raw.read(fields[-1])
            require(name == info.filename.encode('utf-8'), 'local/central filename mismatch')
            while extra:
                require(len(extra) >= 4, 'invalid ZIP extra')
                tag, count = struct.unpack('<HH', extra[:4])
                require(tag != 1 and count <= len(extra) - 4, 'unsupported ZIP64/invalid extra')
                extra = extra[4 + count:]
            data_at = offset + 30 + fields[-2] + fields[-1]
            raw.seek(data_at)
            remaining, total, crc = info.compress_size, 0, 0
            decoder = zlib.decompressobj(-15) if info.compress_type == zipfile.ZIP_DEFLATED else None
            while remaining:
                block = raw.read(min(65536, remaining))
                require(bool(block), 'truncated compressed data')
                remaining -= len(block)
                while block:
                    output = decoder.decompress(block, min(1024 * 1024, info.file_size - total + 1)) if decoder else block
                    block = decoder.unconsumed_tail if decoder else b''
                    total += len(output); crc = zlib.crc32(output, crc)
                    require(total <= info.file_size, 'inflated size exceeds declared boundary')
                    require(decoder is None or not decoder.unused_data, 'hidden bytes after compressed stream')
                require(decoder is None or not decoder.eof or remaining == 0, 'compressed trailing bytes')
            require(total == info.file_size and crc == info.CRC and (decoder is None or decoder.eof), 'ZIP stream size/CRC/truncation')
            offset = data_at + info.compress_size
            if info.flag_bits & 8:
                raw.seek(offset)
                descriptor = raw.read(16)
                require(len(descriptor) == 16 and struct.unpack('<4s3L', descriptor) ==
                        (b'PK\x07\x08', info.CRC, info.compress_size, info.file_size), 'ZIP data descriptor mismatch')
                offset += 16
            else:
                require(fields[6:9] == (info.CRC, info.compress_size, info.file_size), 'local/central size or CRC mismatch')
        require(offset == central_at, 'ZIP data/directory boundary')
        # ZipFile validates CRC on complete reads; also reject hidden central records/padding.
        raw.seek(central_at)
        for info in infos:
            header = raw.read(46)
            require(len(header) == 46 and header[:4] == b'PK\x01\x02', 'central header boundary')
            lengths = struct.unpack_from('<3H', header, 28)
            require(raw.read(lengths[0]) == info.filename.encode('utf-8'), 'central filename boundary')
            raw.seek(lengths[1] + lengths[2], 1)
        require(raw.tell() == size - 22, 'ZIP central padding/hidden entries')


def error_graphs(value):
    if isinstance(value, dict):
        if 'root_id' in value and 'nodes' in value:
            nodes = value['nodes']
            ids = {node['id'] for node in nodes}
            require(len(ids) == len(nodes) and value['root_id'] in ids, 'Throwable IDs/root')
            for node in nodes:
                require(node['cause_id'] is None or node['cause_id'] in ids, 'Throwable cause reference')
                require(set(node['suppressed_ids']) <= ids, 'Throwable suppressed reference')
        for item in value.values():
            error_graphs(item)
    elif isinstance(value, list):
        for item in value:
            error_graphs(item)


def is_firmware(event):
    payload = event['payload']
    return event['event_type'] == 'device_info' and (
        payload.get('uuid', '').lower() == '00002a26-0000-1000-8000-00805f9b34fb' or
        payload.get('key', '').lower() in ('firmware', 'firmware_revision'))


def validate(path):
    path = Path(path)
    require(path.stat().st_size <= MAX_ARCHIVE, 'archive size limit')
    schemas = {name: parse_json((SCHEMAS / (name + '-v1.schema.json')).read_bytes()) for name in ('manifest', 'event', 'processing')}
    with zipfile.ZipFile(path) as archive, tempfile.TemporaryDirectory(prefix='polar-validate-') as temporary:
        zip_boundaries(path, archive)
        manifest = parse_json(archive.read('manifest.json'))
        require(isinstance(manifest, dict) and manifest.get('schema_version') == 1, 'unsupported manifest schema version')
        schema_check(manifest, schemas['manifest'])
        start, end = int(manifest['started_elapsed_ns']), manifest['ended_elapsed_ns']
        end = None if end is None else int(end)
        require(end is None or end >= start, 'capture monotonic end before start')
        require((manifest['ended_utc_ms'] is None) == (end is None), 'capture end null pairing')
        db = sqlite3.connect(str(Path(temporary) / 'index.db'))
        try:
            db.executescript('CREATE TABLE inputs(seq INTEGER PRIMARY KEY, ns TEXT, command TEXT, firmware TEXT);'
                             'CREATE TABLE processed(input INTEGER, outcome TEXT); CREATE INDEX parent ON processed(input);')
            counts, last = {}, {}
            fault_seen = False
            for filename, name, sequence_key, time_key in [('events.jsonl', 'event', 'sequence', 'received_elapsed_ns'),
                                                         ('processing.jsonl', 'processing', 'processing_sequence', 'processed_elapsed_ns')]:
                digest = hashlib.sha256(); size = count = previous = 0; previous_ns = -1
                with archive.open(filename) as stream:
                    while True:
                        line = stream.readline(MAX_LINE + 1)
                        if not line:
                            break
                        require(len(line) <= MAX_LINE and line.endswith(b'\n') and not line.endswith(b'\r\n'), 'JSONL line limit/terminator')
                        digest.update(line); size += len(line); count += 1
                        require(size <= MAX_MEMBER, 'JSONL size limit')
                        row = parse_json(line)
                        schema_check(row, schemas[name])
                        require(row['capture_id'] == manifest['capture_id'] and row['process_run_id'] == manifest['process_run_id'], 'capture/process ID mismatch')
                        seq, ns = row[sequence_key], int(row[time_key])
                        require(seq > previous and ns >= previous_ns, 'sequence/monotonic time order')
                        require(ns >= start and (name == 'processing' or end is None or ns <= end), 'time outside capture')
                        previous, previous_ns = seq, ns
                        error_graphs(row)
                        if name == 'event':
                            fault_seen = fault_seen or row['event_type'] == 'capture_fault'
                            firmware = None
                            if is_firmware(row):
                                firmware = json.dumps(dict(device_id=row['device_id'], uuid=row['payload'].get('uuid'),
                                                           key=row['payload'].get('key'), value=row['payload']['value'], input_sequence=seq), sort_keys=True)
                            command = row['payload']['command_id'] if row['source'] == 'app_command' else None
                            db.execute('INSERT INTO inputs VALUES(?,?,?,?)', (seq, str(ns), command, firmware))
                        else:
                            parent = db.execute('SELECT ns,command FROM inputs WHERE seq=?', (row['input_sequence'],)).fetchone()
                            require(parent is not None, 'orphan processing reference')
                            require(ns >= int(parent[0]) and row['command_id'] == parent[1], 'processing receipt time/command reference')
                            if parent[1] is None:
                                require(db.execute('SELECT 1 FROM processed WHERE input=?', (row['input_sequence'],)).fetchone() is None, 'duplicate callback processing')
                                require(row['outcome'] in ('applied', 'ignored', 'failed'), 'callback processing outcome')
                            else:
                                phases = [item[0] for item in db.execute('SELECT outcome FROM processed WHERE input=?', (row['input_sequence'],))]
                                require(row['outcome'] in ('accepted', 'rejected', 'committed', 'failed'), 'command processing outcome')
                                require(not phases or phases == ['accepted'] and row['outcome'] != 'accepted', 'duplicate/out-of-order command phase')
                            db.execute('INSERT INTO processed VALUES(?,?)', (row['input_sequence'], row['outcome']))
                descriptor = manifest['files'][filename]
                require((size, count, digest.hexdigest()) == (descriptor['bytes'], descriptor['records'], descriptor['sha256']), 'JSONL hash/size/record count mismatch')
                counts[filename], last[filename] = count, previous if count else None
            observed = set()
            for firmware in manifest['firmware']:
                seq = firmware['input_sequence']
                require(seq not in observed, 'duplicate firmware reference'); observed.add(seq)
                parent = db.execute('SELECT firmware FROM inputs WHERE seq=?', (seq,)).fetchone()
                require(parent is not None and parent[0] == json.dumps(firmware, sort_keys=True), 'firmware reference/payload mismatch')
            require(len(observed) == db.execute('SELECT COUNT(*) FROM inputs WHERE firmware IS NOT NULL').fetchone()[0], 'missing firmware observations')
            checkpoint = manifest['checkpoint']
            events, processing = counts['events.jsonl'], counts['processing.jsonl']
            require(checkpoint['saved_events'] == events, 'saved events checkpoint mismatch')
            require(checkpoint['last_committed_sequence'] == last['events.jsonl'] and checkpoint['last_committed_processing_sequence'] == last['processing.jsonl'], 'last committed sequence mismatch')
            unmatched = db.execute('SELECT COUNT(*) FROM inputs i WHERE NOT EXISTS (SELECT 1 FROM processed p WHERE p.input=i.seq)').fetchone()[0]
            if 'saved_processing' in checkpoint:
                require(checkpoint['saved_processing'] == processing, 'saved processing checkpoint mismatch')
            if 'unprocessed_inputs' in checkpoint:
                require(checkpoint['unprocessed_inputs'] == unmatched, 'unprocessed input checkpoint mismatch')
            for received, saved, lost in [('received_events', events, 'known_lost_events'), ('received_processing', processing, 'known_lost_processing')]:
                if checkpoint.get(received) is not None:
                    require(checkpoint[received] >= saved + checkpoint.get(lost, 0), 'received count below saved plus known losses')
            unfinished_commands = db.execute("SELECT COUNT(*) FROM inputs i WHERE i.command IS NOT NULL AND NOT EXISTS (SELECT 1 FROM processed p WHERE p.input=i.seq AND p.outcome IN ('committed','rejected','failed'))").fetchone()[0]
            state = manifest['state']
            if state == 'complete':
                require(not fault_seen and end is not None and not checkpoint['unknown_tail'] and checkpoint['known_lost_events'] == 0 and
                        checkpoint.get('known_lost_processing', 0) == 0 and checkpoint.get('checkpoint_persisted', True) and
                        checkpoint.get('last_error_code') is None and checkpoint['received_events'] == events and unmatched == 0 and unfinished_commands == 0,
                        'complete capture has losses/missing processing/unfinished checkpoint')
                require(checkpoint.get('received_processing', processing) == processing, 'complete processing count mismatch')
            if state == 'interrupted':
                require(end is None and checkpoint['unknown_tail'] and checkpoint['received_events'] is None, 'interrupted capture invents end/received count or hides unknown tail')
            return dict(valid=True, schema_version=1, capture_id=manifest['capture_id'], state=state,
                        saved_events=events, saved_processing=processing, unprocessed_inputs=unmatched,
                        known_lost_events=checkpoint['known_lost_events'], known_lost_processing=checkpoint.get('known_lost_processing'),
                        unknown_tail=checkpoint['unknown_tail'], checkpoint_persisted=checkpoint.get('checkpoint_persisted'),
                        replay_ready=False)
        finally:
            db.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.archive), ensure_ascii=False))
    except (Invalid, OSError, zipfile.BadZipFile, RuntimeError, KeyError, TypeError, struct.error, sqlite3.Error, RecursionError, zlib.error) as error:
        # Raw data must not appear in diagnostics, including exceptions from ZIP/JSON parsers.
        reason = str(error) if isinstance(error, Invalid) else type(error).__name__
        print(json.dumps(dict(valid=False, error=reason)), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
