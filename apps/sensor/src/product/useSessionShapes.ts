import { useEffect, useState } from "react";
import { localDataClient } from "../recorder/LocalDataClient";
import type { SavedSession } from "../../modules/veymur-recorder";
import {
  hrSampleFromRecord,
  provisionalAggregateParameters,
  sessionChartColumns,
  type ChartColumn,
  type HrSample,
} from "./sessionMath";

export type ShapeState = readonly ChartColumn[] | "too_long" | "unavailable";
const maxShapePages = 30;
const shapeColumns = 48;
const shapes = new Map<string, ShapeState>();

async function loadShape(item: SavedSession): Promise<ShapeState> {
  try {
    const samples: HrSample[] = [];
    let after = 0;
    for (let page = 0; page < maxShapePages; page++) {
      const result = await localDataClient.readStream({
        id: item.id,
        stream: "hr",
        after_sequence: after,
        limit: 200,
      });
      result.items.forEach((record) => {
        const sample = hrSampleFromRecord(record);
        if (sample) samples.push(sample);
      });
      if (result.next_sequence === null)
        return sessionChartColumns(
          samples,
          item.recorded_active_ms,
          provisionalAggregateParameters.hr_stale_after_ms,
          shapeColumns,
        );
      after = result.next_sequence;
    }
    return "too_long";
  } catch {
    return "unavailable";
  }
}

export function forgetSessionShape(id: string) {
  shapes.delete(id);
}

export function useSessionShapes(
  items: readonly SavedSession[] | null,
  recordingActive: boolean,
): ReadonlyMap<string, ShapeState> {
  const [, setLoaded] = useState(0);
  useEffect(() => {
    if (!items || recordingActive) return;
    let active = true;
    void (async () => {
      for (const item of items) {
        if (!active) return;
        if (shapes.has(item.id)) continue;
        if (item.state !== "completed" && item.state !== "interrupted")
          continue;
        const shape = await loadShape(item);
        shapes.set(item.id, shape);
        if (active) setLoaded((count) => count + 1);
      }
    })();
    return () => {
      active = false;
    };
  }, [items, recordingActive]);
  return shapes;
}
