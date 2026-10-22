import argparse
import csv
import json
import sys
import xml.etree.ElementTree as ElementTree
from datetime import datetime

PULSE_HEADER = [
    "session_id",
    "segment_number",
    "observation_id",
    "received_at_utc",
    "active_time_s",
    "heart_rate_bpm",
    "usable",
    "usable_until_active_time_s",
    "exclusion_reasons",
]
GPX_NAMESPACE = "{http://www.topografix.com/GPX/1/1}"
TRACK_POINT_EXTENSION = "{http://www.garmin.com/xmlschemas/TrackPointExtension/v1}"
TIME_TOLERANCE_S = 0.0005


class ExportError(Exception):
    pass


def parse_utc(value):
    if not value.endswith("Z"):
        raise ExportError(f"time is not UTC: {value}")
    return datetime.fromisoformat(value[:-1] + "+00:00")


def optional_seconds(value, field, line):
    if value == "":
        return None
    try:
        number = float(value)
    except ValueError as error:
        raise ExportError(f"line {line}: {field} is not a number") from error
    if number < 0:
        raise ExportError(f"line {line}: {field} is negative")
    return number


def verify_pulse_csv(path):
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.reader(handle)
        header = next(reader, None)
        if header != PULSE_HEADER:
            raise ExportError(f"unexpected header: {header}")
        rows = list(reader)
    sessions = set()
    observation_ids = set()
    segments = set()
    usable = 0
    covered_s = 0.0
    gaps = 0
    previous_active = None
    previous_until = None
    for index, row in enumerate(rows, start=2):
        if len(row) != len(PULSE_HEADER):
            raise ExportError(f"line {index}: expected {len(PULSE_HEADER)} fields")
        record = dict(zip(PULSE_HEADER, row))
        sessions.add(record["session_id"])
        if record["observation_id"] in observation_ids:
            raise ExportError(f"line {index}: duplicate observation")
        observation_ids.add(record["observation_id"])
        parse_utc(record["received_at_utc"])
        if record["segment_number"]:
            segment = int(record["segment_number"])
            if segment < 1:
                raise ExportError(f"line {index}: segment_number below 1")
            segments.add(segment)
        bpm = int(record["heart_rate_bpm"])
        active = optional_seconds(record["active_time_s"], "active_time_s", index)
        until = optional_seconds(
            record["usable_until_active_time_s"], "usable_until_active_time_s", index
        )
        if record["usable"] not in ("true", "false"):
            raise ExportError(f"line {index}: usable must be true or false")
        if record["usable"] == "true":
            if active is None or until is None or until <= active or bpm <= 0:
                raise ExportError(f"line {index}: usable row needs a positive interval")
            if record["exclusion_reasons"]:
                raise ExportError(f"line {index}: usable row has exclusion reasons")
            if previous_until is not None and active + TIME_TOLERANCE_S < previous_until:
                raise ExportError(f"line {index}: intervals overlap")
            if previous_until is not None and active - previous_until > TIME_TOLERANCE_S:
                gaps += 1
            usable += 1
            covered_s += until - active
            previous_until = until
        else:
            if until is not None:
                raise ExportError(f"line {index}: unusable row has an interval")
            if not record["exclusion_reasons"]:
                raise ExportError(f"line {index}: unusable row has no reason")
        if active is not None:
            if previous_active is not None and active + TIME_TOLERANCE_S < previous_active:
                raise ExportError(f"line {index}: active time goes backwards")
            previous_active = active
    if len(sessions) > 1:
        raise ExportError("rows belong to several sessions")
    return {
        "format": "csv",
        "rows": len(rows),
        "usable_rows": usable,
        "segments": len(segments),
        "covered_s": round(covered_s, 3),
        "gaps_between_usable_rows": gaps,
    }


def verify_route_gpx(path):
    root = ElementTree.parse(path).getroot()
    if root.tag != f"{GPX_NAMESPACE}gpx" or root.get("version") != "1.1":
        raise ExportError("not a GPX 1.1 document")
    tracks = root.findall(f"{GPX_NAMESPACE}trk")
    if len(tracks) != 1:
        raise ExportError("expected exactly one track")
    segments = tracks[0].findall(f"{GPX_NAMESPACE}trkseg")
    if not segments:
        raise ExportError("track has no segments")
    points = 0
    with_pulse = 0
    for segment_index, segment in enumerate(segments, start=1):
        previous_time = None
        track_points = segment.findall(f"{GPX_NAMESPACE}trkpt")
        if not track_points:
            raise ExportError(f"segment {segment_index} is empty")
        for point in track_points:
            latitude = float(point.get("lat"))
            longitude = float(point.get("lon"))
            if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
                raise ExportError("coordinate out of range")
            time_element = point.find(f"{GPX_NAMESPACE}time")
            if time_element is None:
                raise ExportError("track point without time")
            time = parse_utc(time_element.text)
            if previous_time is not None and time < previous_time:
                raise ExportError(f"segment {segment_index}: time goes backwards")
            previous_time = time
            pulse = point.find(
                f"{GPX_NAMESPACE}extensions/{TRACK_POINT_EXTENSION}TrackPointExtension/{TRACK_POINT_EXTENSION}hr"
            )
            if pulse is not None:
                if int(pulse.text) <= 0:
                    raise ExportError("non-positive heart rate")
                with_pulse += 1
            points += 1
    return {
        "format": "gpx",
        "segments": len(segments),
        "points": points,
        "points_with_pulse": with_pulse,
    }


def main(arguments):
    parser = argparse.ArgumentParser(
        description="Independent check of veymur user CSV/GPX exports."
    )
    parser.add_argument("files", nargs="+")
    parser.add_argument("--expect", help="JSON object with expected summary values per file name")
    options = parser.parse_args(arguments)
    expected = json.loads(options.expect) if options.expect else {}
    failed = False
    for path in options.files:
        try:
            summary = verify_pulse_csv(path) if path.endswith(".csv") else verify_route_gpx(path)
            mismatches = {
                key: {"expected": value, "actual": summary.get(key)}
                for key, value in expected.get(path.rsplit("/", 1)[-1], {}).items()
                if summary.get(key) != value
            }
            print(json.dumps({"file": path, "ok": not mismatches, "summary": summary, "mismatches": mismatches}))
            failed = failed or bool(mismatches)
        except (ExportError, ValueError, ElementTree.ParseError, OSError) as error:
            print(json.dumps({"file": path, "ok": False, "error": str(error)}))
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
