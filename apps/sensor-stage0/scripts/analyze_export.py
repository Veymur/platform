#!/usr/bin/env python3
"""Validate a Stage 0 diagnostic ZIP using independent stdlib CSV/ZIP parsers."""
import argparse
import csv
import io
import json
import zipfile
from collections import Counter


def analyze(path):
    with zipfile.ZipFile(path) as archive:
        metadata = json.loads(archive.read("metadata.json"))
        if metadata.get("schema_version") != 1:
            raise ValueError("Unsupported schema_version")
        rows = list(csv.DictReader(io.StringIO(archive.read("records.csv").decode("utf-8"))))
    counts = Counter()
    previous_sequence = 0
    previous_elapsed = None
    previous_hr = None
    paused = False
    gaps = []
    for row in rows:
        sequence = int(row["sequence"])
        elapsed = int(row["elapsed_realtime_ms"])
        if sequence <= previous_sequence:
            raise ValueError("Records are not strictly ordered by sequence")
        # A restarted phone can reset elapsedRealtime. An interrupted recovery event is
        # not a measurement and must not be connected to the previous boot's timeline.
        if previous_elapsed is not None and elapsed < previous_elapsed and row["kind"] != "interrupted":
            raise ValueError("Monotonic time moved backwards inside a recording")
        previous_sequence, previous_elapsed = sequence, elapsed
        payload = json.loads(row["payload_json"])
        kind = row["kind"]
        counts[kind] += 1
        if kind == "pause":
            paused, previous_hr = True, None
        elif kind == "resume":
            paused, previous_hr = False, None
        elif kind == "hr":
            if paused:
                raise ValueError("HR measurement persisted during a pause")
            if not isinstance(payload["bpm"], int):
                raise ValueError("Invalid HR payload")
            if previous_hr is not None and elapsed - previous_hr > 5000:
                gaps.append({"after_elapsed_ms": previous_hr, "gap_ms": elapsed - previous_hr})
            previous_hr = elapsed
        elif kind == "gps":
            if paused:
                raise ValueError("GPS measurement persisted during a pause")
            if not (-90 <= payload["latitude"] <= 90 and -180 <= payload["longitude"] <= 180):
                raise ValueError("Invalid GPS coordinate")
    if not rows or rows[0]["kind"] != "start":
        raise ValueError("Session start record is missing")
    return {"metadata": metadata, "record_counts": dict(counts), "hr_gaps_over_5_seconds": gaps,
            "note": "Gaps are diagnostic intervals between received HR samples, not sensor accuracy. Leading/trailing missing data needs comparison with test actions."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export_zip")
    args = parser.parse_args()
    try:
        print(json.dumps(analyze(args.export_zip), indent=2, ensure_ascii=False))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Invalid export: {error}\n")
