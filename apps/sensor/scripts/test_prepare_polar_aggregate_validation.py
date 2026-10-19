import csv
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from prepare_polar_aggregate_validation import project


class ProjectionTest(unittest.TestCase):
    def run_projection(self, kinds, interrupted=False):
        events, processing = [], []
        for sequence, (kind, ms, value) in enumerate(kinds, 1):
            command = kind in ('start', 'pause', 'resume', 'finish')
            payload = ({'name': kind, 'route': False} if command else
                       {'hr': value, 'contactStatus': True, 'contactStatusSupported': False})
            events.append(dict(sequence=sequence, source='app_command' if command else 'polar_callback',
                               event_type='command_requested' if command else kind, payload=payload,
                               received_elapsed_ns=str(ms * 1_000_000), received_utc_ms=ms))
            processing.append(dict(input_sequence=sequence, outcome='committed' if command else 'applied',
                                   processed_elapsed_ns=str(ms * 1_000_000), processed_utc_ms=ms))
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / 'source.zip', Path(directory) / 'out.tsv'
            with ZipFile(source, 'w') as archive:
                archive.writestr('manifest.json', json.dumps({'initial_configuration': dict(
                    session_state='preparation', session_id=None, route_enabled=False)}))
                archive.writestr('events.jsonl', '\n'.join(map(json.dumps, events)))
                archive.writestr('processing.jsonl', '\n'.join(map(json.dumps, processing)))
            report = dict(state='interrupted' if interrupted else 'complete',
                          saved_events=len(events), saved_processing=len(processing), unknown_tail=interrupted)
            with patch('prepare_polar_aggregate_validation.validate', return_value=report):
                result = project(source, output)
            return result, list(csv.reader(output.read_text().splitlines(), delimiter='\t'))

    def test_pauses_and_invalid_hr_do_not_fill_gaps(self):
        result, _ = self.run_projection([
            ('hr', 0, 90), ('start', 1000, None), ('hr', 2000, 80), ('hr', 2500, 0),
            ('pause', 4000, None), ('hr', 5000, 100), ('resume', 9000, None),
            ('hr', 10000, 120), ('finish', 12000, None)])
        self.assertEqual(6000, result['active_ms'])
        self.assertEqual(4000, result['covered_ms'])
        self.assertEqual(100, result['average_hr_bpm'])
        self.assertEqual(1, result['rejected_hr'])
        self.assertEqual(2, result['hr_outside_recording'])
        self.assertEqual(2, result['segments'])

    def test_interruption_ends_at_last_projected_record(self):
        result, rows = self.run_projection([
            ('start', 1000, None), ('hr', 2000, 80), ('hr', 3000, 100)], interrupted=True)
        self.assertEqual(2000, result['active_ms'])
        self.assertEqual(1000, result['covered_ms'])
        self.assertEqual(80, result['average_hr_bpm'])
        self.assertTrue(result['unknown_tail'])
        self.assertEqual('record-3', rows[-1][-1])

    def test_unfinished_complete_capture_is_not_a_completed_session(self):
        with self.assertRaisesRegex(ValueError, 'no terminal'):
            self.run_projection([('start', 1000, None), ('hr', 2000, 80)])

    def test_queued_hr_is_not_silently_moved_into_segment(self):
        with self.assertRaisesRegex(ValueError, 'precedes'):
            self.run_projection([('start', 2000, None), ('hr', 1000, 80), ('finish', 3000, None)])


if __name__ == '__main__':
    unittest.main()
