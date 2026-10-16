"""Private test projection of legacy Journal ZIPs; never a database migration.

Outputs TSV inputs for the native aggregate verifier and independent HR expectations.
Legacy append milliseconds are explicitly a receipt proxy; no hardware time is invented.
Raw HR/routes stay under the caller's ignored artifacts directory. Prints counts only.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
from zipfile import ZipFile


def project(source, destination):
    with ZipFile(source) as archive:
        metadata = json.loads(archive.read('metadata.json'))
        rows = list(csv.DictReader(io.StringIO(archive.read('records.csv').decode())))
    if metadata.get('state') not in ('completed', 'interrupted'):
        raise ValueError('nonterminal session')
    output, segments = [], []
    current = None
    active = False
    counter = 0
    previous_ns = -1
    hr_count = gps_count = 0
    last_id = '-'
    for row in rows:
        kind = row['kind']
        payload = json.loads(row['payload_json'])
        ns = int(row['elapsed_realtime_ms']) * 1_000_000
        seq = int(row['sequence']) * 2
        if kind != 'interrupted' and ns < previous_ns:
            raise ValueError('legacy monotonic reset: explicit base unavailable')
        previous_ns = ns
        if payload.get('observation_metadata') is not None:
            raise ValueError('not a legacy export; use the logical-record reader when available')
        values = []
        if kind in ('start', 'resume'):
            counter += 1
            current = {'id': f'segment-{counter}', 'start': ns, 'end': None, 'hr': [], 'breaks': []}
            segments.append(current)
            active = True
        elif kind in ('pause', 'finish'):
            if active:
                current['end'] = ns
            active = False
        elif kind == 'interrupted':
            if active:
                # Only a referenced committed observation proves a known boundary.
                current['end'] = last_mapped_ns
            active = False
            values = [last_id]
        elif kind == 'hr' and active:
            bpm = payload.get('bpm')
            if type(bpm) is not int:
                raise ValueError('invalid legacy HR representation')
            suspect = payload.get('contact_supported') is True and payload.get('contact') is False
            current['hr'].append((ns, bpm, suspect))
            values = [bpm, 'suspect' if suspect else 'valid']
            hr_count += 1
        elif kind == 'gps' and active:
            values = [payload['latitude'], payload['longitude'], payload.get('accuracy_m', '-'),
                      payload.get('source_elapsed_nanos', '-'), 'suspect' if payload.get('mock') else 'valid']
            gps_count += 1
        elif active and (kind == 'disconnected' or (kind == 'bluetooth' and payload.get('powered') is False)):
            current['breaks'].append(ns)
            kind = 'hr_lost'
        elif kind in ('gps_unavailable', 'gps_error') and active:
            kind = 'gps_lost'
        else:
            continue
        segment_id = current['id'] if current else '-'
        output.append([kind, seq, ns, segment_id, row['received_utc_ms'], *values])
        if kind == 'finish':
            output.append(['completed', seq + 1, ns, segment_id, row['received_utc_ms']])
        if kind != 'interrupted':
            last_id = f'record-{seq}'
            last_mapped_ns = ns
    if any(s['end'] is None for s in segments):
        raise ValueError('unfinished legacy timeline')
    active_ms = sum((s['end'] - s['start']) / 1_000_000 for s in segments)
    covered_ns = weighted_ns = 0
    for segment in segments:
        observations = segment['hr']
        for index, (start, bpm, suspect) in enumerate(observations):
            if bpm <= 0 or suspect or not segment['start'] <= start < segment['end']:
                continue
            end = min(segment['end'], start + 5_000_000_000)
            if index + 1 < len(observations):
                end = min(end, observations[index + 1][0])
            end = min([end] + [b for b in segment['breaks'] if b >= start])
            duration = max(0, end - start)
            covered_ns += duration
            weighted_ns += duration * bpm
    expected = [metadata['state'], str(bool(metadata.get('gps_requested'))).lower(), active_ms,
                covered_ns / 1_000_000, weighted_ns / covered_ns if covered_ns else '-']
    with destination.open('w', newline='') as handle:
        writer = csv.writer(handle, delimiter='\t', lineterminator='\n')
        writer.writerow(['session', *expected])
        writer.writerows(output)
    return hr_count, gps_count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = {'format': 'legacy-append-time-proxy-v1', 'sessions': [], 'skipped': []}
    for source in sorted(args.source.rglob('veymur-*.zip')):
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        try:
            target = args.output / f'{digest[:16]}.tsv'
            hr, gps = project(source, target)
            report['sessions'].append({'sha256': digest, 'fixture': target.name, 'hr_count': hr, 'gps_count': gps})
        except (ValueError, KeyError) as error:
            report['skipped'].append({'sha256': digest, 'reason': str(error)})
    (args.output / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'projected_sessions': len(report['sessions']), 'skipped_sessions': len(report['skipped']),
                      'hr_observations': sum(s['hr_count'] for s in report['sessions']),
                      'gps_observations': sum(s['gps_count'] for s in report['sessions'])}))


if __name__ == '__main__':
    main()
