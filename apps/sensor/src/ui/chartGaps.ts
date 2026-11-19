export type ChartGapRun = { from: number; to: number };

export function chartGapRuns(
  values: readonly (number | null)[],
  firstCoveredIndex = 0,
): ChartGapRun[] {
  const runs: ChartGapRun[] = [];
  let gapStart: number | null = null;
  for (
    let index = Math.max(0, firstCoveredIndex);
    index < values.length;
    index++
  ) {
    if (values[index] === null && gapStart === null) gapStart = index;
    if (values[index] !== null && gapStart !== null) {
      runs.push({ from: gapStart, to: index - 1 });
      gapStart = null;
    }
  }
  if (gapStart !== null) runs.push({ from: gapStart, to: values.length - 1 });
  return runs;
}
