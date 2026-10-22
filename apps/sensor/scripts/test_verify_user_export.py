import os
import tempfile
import unittest

import verify_user_export as verifier

HEADER = ",".join(verifier.PULSE_HEADER)
GPX_HEAD = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1" '
    'xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"><trk>'
)


class VerifyUserExportTest(unittest.TestCase):
    def write(self, name, text):
        directory = tempfile.mkdtemp()
        path = os.path.join(directory, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path

    def test_pulse_summary_counts_segments_coverage_and_gaps(self):
        path = self.write(
            "a-pulse.csv",
            HEADER
            + "\ns,1,r1,2026-10-19T10:00:00Z,0.000,80,true,1.000,"
            + "\ns,1,r2,2026-10-19T10:00:01Z,1.000,0,false,,invalid_hr_format"
            + "\ns,2,r3,2026-10-19T10:00:09Z,3.500,90,true,8.500,\n",
        )
        summary = verifier.verify_pulse_csv(path)
        self.assertEqual(
            summary,
            {"format": "csv", "rows": 3, "usable_rows": 2, "segments": 2, "covered_s": 6.0, "gaps_between_usable_rows": 1},
        )

    def test_pulse_rejects_overlap_missing_reason_and_wrong_header(self):
        overlap = HEADER + "\ns,1,r1,2026-10-19T10:00:00Z,0,80,true,5,\ns,1,r2,2026-10-19T10:00:01Z,1,81,true,2,\n"
        no_reason = HEADER + "\ns,1,r1,2026-10-19T10:00:00Z,0,80,false,,\n"
        local_time = HEADER + "\ns,1,r1,2026-10-19T10:00:00,0,80,true,1,\n"
        for text in (overlap, no_reason, local_time, "wrong\n"):
            with self.assertRaises(verifier.ExportError):
                verifier.verify_pulse_csv(self.write("b-pulse.csv", text))

    def test_route_counts_segments_and_matched_pulse(self):
        path = self.write(
            "a-route.gpx",
            GPX_HEAD
            + '<trkseg><trkpt lat="1" lon="2"><time>2026-10-19T10:00:00Z</time><extensions><gpxtpx:TrackPointExtension>'
            + "<gpxtpx:hr>80</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>"
            + '<trkpt lat="1" lon="2.1"><time>2026-10-19T10:00:01Z</time></trkpt></trkseg>'
            + '<trkseg><trkpt lat="1" lon="3"><time>2026-10-19T10:00:09Z</time></trkpt></trkseg></trk></gpx>',
        )
        self.assertEqual(
            verifier.verify_route_gpx(path),
            {"format": "gpx", "segments": 2, "points": 3, "points_with_pulse": 1},
        )

    def test_route_rejects_backwards_time_and_bad_coordinates(self):
        backwards = (
            GPX_HEAD
            + '<trkseg><trkpt lat="1" lon="2"><time>2026-10-19T10:00:05Z</time></trkpt>'
            + '<trkpt lat="1" lon="2"><time>2026-10-19T10:00:01Z</time></trkpt></trkseg></trk></gpx>'
        )
        outside = GPX_HEAD + '<trkseg><trkpt lat="91" lon="2"><time>2026-10-19T10:00:05Z</time></trkpt></trkseg></trk></gpx>'
        for text in (backwards, outside):
            with self.assertRaises(verifier.ExportError):
                verifier.verify_route_gpx(self.write("b-route.gpx", text))

    def test_cli_reports_expectation_mismatch(self):
        path = self.write("c-pulse.csv", HEADER + "\ns,1,r1,2026-10-19T10:00:00Z,0,80,true,1,\n")
        self.assertEqual(verifier.main([path, "--expect", '{"c-pulse.csv": {"rows": 1}}']), 0)
        self.assertEqual(verifier.main([path, "--expect", '{"c-pulse.csv": {"rows": 2}}']), 1)


if __name__ == "__main__":
    unittest.main()
