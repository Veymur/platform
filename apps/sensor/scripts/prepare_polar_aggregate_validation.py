"""Private Polar capture projection for the existing Kotlin aggregate verifier.

Not a recorder replay, database import, or proof of original session commits.
Command terminal processing time is a boundary proxy; HR retains callback receipt time.
Recorded outcomes select available executed inputs, never supply expected aggregates.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

from validate_polar_diagnostic import validate


def project(source, destination):
    validation = validate(source)
    with ZipFile(source) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        inputs = {e['sequence']: e for e in map(json.loads, archive.read('events.jsonl').splitlines())}
        processing = list(map(json.loads, archive.read('processing.jsonl').splitlines()))
    initial = manifest['initial_configuration']
    if initial['session_state'] != 'preparation' or initial['session_id'] is not None or initial['route_enabled']:
        raise ValueError('projection requires preparation with no session or route')
    rows, segments = [], []
    active = False
    state = 'preparation'
    current = None
    rejected_hr = outside_hr = 0

    def emit(kind, ns, utc, *values):
        # Local projection IDs only. No claim of original Journal sequence/identity.
        sequence = len(rows) + 1
        rows.append([kind, sequence, ns, current['id'] if current else '-', utc, *values])

    for execution in processing:
        outcome = execution['outcome']
        if outcome == 'accepted':
            continue
        event = inputs[execution['input_sequence']]
        payload = event['payload']
        if outcome in ('failed', 'rejected'):
            raise ValueError('external failure/rejection needs an explicit model')
        if event['source'] == 'app_command':
            if outcome != 'committed' or payload['route']:
                raise ValueError('unsupported command outcome/route')
            kind = payload['name']
            ns = int(execution['processed_elapsed_ns'])
            utc = execution['processed_utc_ms']
            if kind in ('start', 'resume'):
                if state != ('preparation' if kind == 'start' else 'paused'):
                    raise ValueError('invalid start/resume')
                current = dict(id=f'segment-{len(segments)+1}', start=ns, end=None, hr=[], breaks=[])
                segments.append(current)
                active, state = True, 'recording'
            elif kind in ('pause', 'finish'):
                if state not in (('recording',) if kind == 'pause' else ('recording', 'paused')):
                    raise ValueError('invalid pause/finish')
                if active:
                    current['end'] = ns
                active, state = False, 'paused' if kind == 'pause' else 'completed'
            else:
                raise ValueError('unsupported command')
            emit(kind, ns, utc)
            if kind == 'finish':
                emit('completed', ns, utc)
        elif event['event_type'] == 'hr' and outcome == 'applied':
            bpm = payload['hr']
            if type(bpm) is not int or bpm <= 0:
                rejected_hr += 1
                continue
            if not active:
                outside_hr += 1
                continue
            ns = int(event['received_elapsed_ns'])
            if ns < current['start']:
                raise ValueError('queued HR precedes projected segment boundary')
            suspect = payload['contactStatusSupported'] and not payload['contactStatus']
            current['hr'].append((ns, bpm, suspect))
            emit('hr', ns, event['received_utc_ms'], bpm, 'suspect' if suspect else 'valid')
        elif active and outcome == 'applied' and (event['event_type'] == 'disconnected' or
                event['event_type'] == 'bluetooth' and payload['powered'] is False):
            ns = int(execution['processed_elapsed_ns'])
            current['breaks'].append(ns)
            emit('hr_lost', ns, execution['processed_utc_ms'])
    if validation['state'] == 'interrupted' and state in ('recording', 'paused'):
        last = rows[-1]
        if active:
            current['end'] = last[2]
        emit('interrupted', last[2], last[4], f'record-{last[1]}')
        state = 'interrupted'
    if state not in ('completed', 'interrupted') or any(s['end'] is None for s in segments):
        raise ValueError('no terminal projected session')
    covered = weighted = 0
    for segment in segments:
        observations = segment['hr']
        if observations != sorted(observations):
            raise ValueError('HR receipt reordering requires an explicit model')
        for index, (start, bpm, suspect) in enumerate(observations):
            if suspect or not segment['start'] <= start < segment['end']:
                continue
            stop = min(segment['end'], start + 5_000_000_000,
                       observations[index + 1][0] if index + 1 < len(observations) else segment['end'])
            stop = min([stop] + [b for b in segment['breaks'] if b >= start])
            duration = max(0, stop - start)
            covered += duration
            weighted += bpm * duration
    active_ms = sum(s['end'] - s['start'] for s in segments) / 1_000_000
    with destination.open('w', newline='') as handle:
        writer = csv.writer(handle, delimiter='\t', lineterminator='\n')
        writer.writerow(['session', state, 'false', active_ms, covered / 1_000_000,
                         weighted / covered if covered else '-', 'polar-processing-boundary-proxy-v1', len(segments)])
        writer.writerows(rows)
    return dict(state=state, input_events=validation['saved_events'], processing=validation['saved_processing'],
                hr_callbacks=sum(e['event_type'] == 'hr' for e in inputs.values()),
                projected_hr=sum(len(s['hr']) for s in segments), rejected_hr=rejected_hr,
                hr_outside_recording=outside_hr, segments=len(segments), active_ms=active_ms,
                covered_ms=covered / 1_000_000, average_hr_bpm=weighted / covered if covered else None,
                unknown_tail=validation['unknown_tail'], valid=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    for source in sorted(args.source.rglob('polar-diagnostic*.zip')):
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        target = args.output / f'{digest[:16]}.tsv'
        result = dict(archive=str(source), sha256=digest, fixture=target.name)
        try:
            result.update(project(source, target))
        except ValueError as error:
            result.update(valid=False, error=str(error))
        results.append(result)
    report = dict(format='polar-processing-boundary-proxy-v1', sessions=results)
    (args.output / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    return 0 if results and all(r['valid'] for r in results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
