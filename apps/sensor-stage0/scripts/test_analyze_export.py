import csv
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from analyze_export import analyze


class ExportAnalysisTest(unittest.TestCase):
    def analyze_rows(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "session.zip"
            contents = io.StringIO()
            writer = csv.writer(contents)
            writer.writerow(["sequence", "received_utc_ms", "elapsed_realtime_ms", "kind", "payload_json"])
            for sequence, elapsed, kind, payload in rows:
                writer.writerow([sequence, 1800000000000 + elapsed, elapsed, kind, json.dumps(payload)])
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("metadata.json", json.dumps({"schema_version": 1}))
                archive.writestr("records.csv", contents.getvalue())
            return analyze(path)

    def test_gap_detected_but_pause_is_excluded(self):
        result = self.analyze_rows([
            (1, 0, "start", {}), (2, 1000, "hr", {"bpm": 82}),
            (3, 8000, "hr", {"bpm": 84}), (4, 9000, "pause", {}),
            (5, 20000, "resume", {}), (6, 22000, "hr", {"bpm": 86}),
            (7, 23000, "finish", {}),
        ])
        self.assertEqual([{"after_elapsed_ms": 1000, "gap_ms": 7000}], result["hr_gaps_over_5_seconds"])

    def test_sample_during_pause_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "during a pause"):
            self.analyze_rows([(1, 0, "start", {}), (2, 1000, "pause", {}), (3, 2000, "hr", {"bpm": 82})])

    def test_duplicate_sequence_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "sequence"):
            self.analyze_rows([(1, 0, "start", {}), (1, 1000, "hr", {"bpm": 82})])

    def test_recovery_after_phone_reboot_allows_new_monotonic_origin(self):
        result = self.analyze_rows([(1, 90000, "start", {}), (2, 91000, "hr", {"bpm": 82}), (3, 1000, "interrupted", {})])
        self.assertEqual(1, result["record_counts"]["interrupted"])


if __name__ == "__main__":
    unittest.main()
