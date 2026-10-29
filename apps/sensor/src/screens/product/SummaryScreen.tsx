import { useCallback, useEffect, useState, type ReactNode } from "react";
import { StyleSheet, View } from "react-native";
import {
  Action,
  Card,
  Detail,
  Dialog,
  Label,
  Mark,
  Status,
  Warning,
} from "../../ui/components";
import { LineChart } from "../../ui/LineChart";
import { Meter, Legend, type MeterTone } from "../../ui/Meter";
import { PrintShape } from "../../ui/PrintShape";
import { AppBar, IconButton, Screen } from "../../ui/Screen";
import { Caption, Eyebrow, H1 } from "../../ui/Typography";
import { useProduct } from "../../product/ProductContext";
import { forgetSessionShape } from "../../product/useSessionShapes";
import { localDataClient } from "../../recorder/LocalDataClient";
import type {
  DeletionResult,
  SessionDetails,
} from "../../../modules/veymur-recorder";
import {
  boundsFromRanges,
  formatClock,
  formatDateTime,
  formatDistance,
  formatPercent,
  formatTime,
  hrSampleFromRecord,
  provisionalAggregateParameters,
  sessionChartColumns,
  withoutErrorCode,
  type HrSample,
  type UserRange,
} from "../../product/sessionMath";
import { ExportDialog } from "./ExportDialog";
import { Muted, Subheading } from "./shared";

const maxChartPages = 150;
type Loaded = {
  details: SessionDetails;
  samples: HrSample[];
  chartLimited: boolean;
};
function useSessionSummary(id: string) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(
    async (isActive: () => boolean) => {
      setError(null);
      try {
        const details = await localDataClient.getSessionDetails({
          id,
          parameters: provisionalAggregateParameters,
        });
        const samples: HrSample[] = [];
        let after = 0;
        let pages = 0;
        let chartLimited = false;
        while (isActive()) {
          const page = await localDataClient.readStream({
            id,
            stream: "hr",
            after_sequence: after,
            limit: 200,
          });
          page.items.forEach((item) => {
            const sample = hrSampleFromRecord(item);
            if (sample) samples.push(sample);
          });
          if (page.next_sequence === null) break;
          after = page.next_sequence;
          if (++pages >= maxChartPages) {
            chartLimited = true;
            break;
          }
        }
        if (isActive()) setLoaded({ details, samples, chartLimited });
      } catch (cause) {
        if (isActive())
          setError(
            cause instanceof Error
              ? withoutErrorCode(cause.message)
              : String(cause),
          );
      }
    },
    [id],
  );
  useEffect(() => {
    let active = true;
    void load(() => active);
    return () => {
      active = false;
    };
  }, [load]);
  return { loaded, error, retry: () => load(() => true) };
}
const reasonText: Record<string, string> = {
  interactive_calculation_limit:
    "This session is too long to summarise on the phone right now. Its data is kept.",
};
const stateText = (state: string) =>
  state === "completed"
    ? "Saved on this phone"
    : state === "interrupted"
      ? "Interrupted · saved part kept on this phone"
      : "Recording is not finished yet";
const toneForRange = (range: UserRange): MeterTone =>
  range.id === "below" ? "dusk" : range.id === "above" ? "ember" : "tide";

export function SummaryScreen({
  sessionId,
  afterFinish,
  onClose,
  onDeleted,
}: {
  sessionId: string;
  afterFinish: boolean;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const { units, timeZone } = useProduct();
  const { loaded, error, retry } = useSessionSummary(sessionId);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deletion, setDeletion] = useState<DeletionResult | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [exportVisible, setExportVisible] = useState(false);
  const remove = async () => {
    setConfirmDelete(false);
    setDeleting(true);
    setDeleteError(null);
    try {
      const result = await localDataClient.deleteSession(sessionId);
      forgetSessionShape(sessionId);
      setDeletion(result);
      if (result.cleanup === "complete") onDeleted();
    } catch (cause) {
      setDeleteError(
        cause instanceof Error
          ? withoutErrorCode(cause.message)
          : String(cause),
      );
    } finally {
      setDeleting(false);
    }
  };
  const close = (
    <Action
      title={afterFinish ? "Done" : "Back to history"}
      onPress={onClose}
    />
  );
  const frame = (content: ReactNode, actions?: ReactNode) => (
    <Screen
      appBar={<AppBar title="Session summary" />}
      actions={actions ?? close}
    >
      {content}
    </Screen>
  );
  if (error)
    return frame(
      <>
        <Warning title="Couldn't open this session" detail={error} />
        <Action
          title="Try again"
          onPress={() => {
            void retry();
          }}
        />
      </>,
    );
  if (!loaded)
    return frame(<Status title="Loading the session from this phone…" />);
  const { details, samples, chartLimited } = loaded;
  const { session, summary } = details;
  const deletable =
    session.state === "completed" || session.state === "interrupted";
  const ranges = session.hr_ranges_snapshot?.ranges ?? [];
  const covered = summary?.hr_covered_duration_ms ?? 0;
  const chartColumns = summary
    ? sessionChartColumns(
        samples,
        summary.active_duration_ms,
        details.parameters.hr_stale_after_ms,
        90,
      )
    : [];
  const chartValues = chartColumns.map((column) => column.bpm);
  const chartBand = boundsFromRanges(ranges);
  const chartTickIndexes = chartColumns.length
    ? [
        ...new Set([
          0,
          Math.floor((chartColumns.length - 1) / 2),
          chartColumns.length - 1,
        ]),
      ]
    : [];
  const chartXTicks = summary
    ? chartTickIndexes.map((index) => ({
        index,
        label: formatTime(
          session.started_utc_ms +
            (index / Math.max(chartColumns.length - 1, 1)) *
              summary.active_duration_ms,
          timeZone,
        ),
      }))
    : [];
  const rangeSegments = ranges.map((range) => ({
    value:
      summary?.range_durations.find((item) => item.range_id === range.id)
        ?.duration_ms ?? 0,
    tone: toneForRange(range),
  }));
  const rangeLegend = ranges.map((range) => {
    const duration =
      summary?.range_durations.find((item) => item.range_id === range.id)
        ?.duration_ms ?? 0;
    return {
      label: range.label,
      value: covered > 0 ? formatPercent(duration / covered) : "—",
      tone: toneForRange(range),
    };
  });
  const duration = summary?.active_duration_ms ?? session.recorded_active_ms;
  const durationLabel = formatClock(duration).replace(/^00:/, "");
  const summaryAppBar = (
    <AppBar
      title="Session summary"
      right={
        deletable ? (
          <IconButton
            icon="export"
            label="Export session"
            onPress={() => setExportVisible(true)}
          />
        ) : undefined
      }
    />
  );
  return (
    <Screen appBar={summaryAppBar} actions={close}>
      <View style={styles.header}>
        <View style={styles.headerCopy}>
          <Eyebrow>
            Session · {formatDateTime(session.started_utc_ms, timeZone)}
          </Eyebrow>
          <H1 style={styles.duration}>{durationLabel}</H1>
          <Caption>active time</Caption>
        </View>
        {summary && (
          <PrintShape
            label="Shape of this session"
            size={112}
            columns={sessionChartColumns(
              samples,
              summary.active_duration_ms,
              details.parameters.hr_stale_after_ms,
              48,
            )}
          />
        )}
      </View>
      <Mark icon="on-phone" label={stateText(session.state)} />
      {summary?.interrupted_tail_unknown && (
        <Warning
          title="The end of this recording is unknown"
          detail="Recording stopped unexpectedly. Only the part saved before that is shown."
        />
      )}
      {summary === null && (
        <Warning
          title="Summary not available"
          detail={details.unavailable_reasons
            .map((reason) => reasonText[reason] ?? `Reason: ${reason}`)
            .join(" ")}
        />
      )}
      {summary && (
        <Card variant="flat" style={styles.statsCard}>
          <View style={styles.statGrid}>
            <View style={styles.statCell}>
              <Detail
                title="Distance"
                value={
                  summary.gps_distance_m === null
                    ? session.route_requested
                      ? "Not available"
                      : "Off"
                    : formatDistance(summary.gps_distance_m, units)
                }
                note={
                  summary.gps_distance_m === null && session.route_requested
                    ? "No usable route segments were recorded."
                    : summary.gps_distance_m !== null
                      ? "Usable route segments only."
                      : "Route was not requested."
                }
              />
            </View>
            <View style={styles.statCell}>
              <Detail
                title="Average pulse"
                value={
                  summary.average_hr_bpm === null
                    ? "Not available"
                    : `${Math.round(summary.average_hr_bpm)} bpm`
                }
                note={
                  summary.average_hr_bpm === null
                    ? "No usable pulse in this session."
                    : "Usable part of the session"
                }
              />
            </View>
            <View style={styles.statCell}>
              <Detail
                title="Cadence"
                value="Not available"
                note="This sensor does not report cadence."
              />
            </View>
            <View style={styles.statCell}>
              <Detail
                title="Route"
                value={session.route_requested ? "Recorded" : "Not recorded"}
                note={
                  session.route_requested
                    ? "On this phone · usable segments only"
                    : undefined
                }
              />
            </View>
          </View>
        </Card>
      )}
      {summary && (
        <Card variant="flat">
          <Subheading>Time in your ranges</Subheading>
          {ranges.length === 0 || covered <= 0 ? (
            <Muted>No ranges were set for this session.</Muted>
          ) : (
            <>
              <Meter
                segments={rangeSegments}
                label={rangeLegend
                  .map((item) => `${item.label} ${item.value}`)
                  .join(", ")}
              />
              <Legend items={rangeLegend} />
              {summary.unassigned_hr_duration_ms > 0 && (
                <Muted>
                  Outside your ranges ·{" "}
                  {formatPercent(summary.unassigned_hr_duration_ms / covered)}
                </Muted>
              )}
              <Muted>
                Ranges are the numbers you entered. Share of usable pulse time.
              </Muted>
            </>
          )}
        </Card>
      )}
      {summary && (
        <Card variant="flat">
          <Subheading>
            {summary.hr_coverage === null
              ? "Pulse over active time"
              : `Pulse recorded for ${formatPercent(summary.hr_coverage)} of active time`}
          </Subheading>
          <Muted>
            Gaps are drawn, not hidden. Coverage shows how much we recorded, not
            how accurate the sensor is.
          </Muted>
          <LineChart
            values={chartValues}
            height={168}
            yMin={80}
            yMax={150}
            yTicks={[90, 120, 150]}
            xTicks={chartXTicks}
            band={chartBand ? { min: chartBand.from, max: chartBand.to } : null}
            label="Pulse during the session"
            gapLabel="No pulse"
          />
          <Legend
            items={[
              { label: "Pulse from sensor", tone: "tide" },
              { label: "No pulse data", tone: "gap" },
            ]}
          />
          {chartLimited && (
            <Muted>
              This session is long; the chart shows its first part. The numbers
              above cover the whole session.
            </Muted>
          )}
        </Card>
      )}
      {afterFinish && (
        <Muted>
          This session is saved on this phone. Cloud sync is not available in
          this version.
        </Muted>
      )}
      {deletable && (
        <Action
          title={deleting ? "Deleting…" : "Delete from this phone"}
          variant="danger"
          disabled={deleting}
          onPress={() => setConfirmDelete(true)}
        />
      )}
      {deletion?.cleanup === "pending" && (
        <>
          <Warning
            title="Session deleted, cleanup not finished"
            detail="The session is removed from history, but a temporary file could not be removed yet. Try again."
          />
          <Action
            title="Retry cleanup"
            disabled={deleting}
            onPress={() => {
              void remove();
            }}
          />
        </>
      )}
      {deleteError && (
        <Warning title="Couldn't delete the session" detail={deleteError} />
      )}
      <ExportDialog
        visible={exportVisible}
        sessionId={sessionId}
        routeRequested={session.route_requested === true}
        onClose={() => setExportVisible(false)}
      />
      <Dialog
        visible={confirmDelete}
        title="Delete this session?"
        onClose={() => setConfirmDelete(false)}
        closeTitle="Keep session"
      >
        <Label>
          Pulse, route and summary of this session are removed from this phone.
          This can’t be undone.
        </Label>
        <Muted>
          Files you exported or shared earlier stay where you saved them.
        </Muted>
        <Action
          title="Delete session"
          variant="danger"
          onPress={() => {
            void remove();
          }}
        />
      </Dialog>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: "row",
    alignItems: "center",
    gap: 20,
    paddingVertical: 4,
  },
  headerCopy: { flex: 1, gap: 4 },
  duration: {
    fontSize: 48,
    lineHeight: 50,
  },
  statsCard: { padding: 20 },
  statGrid: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 20,
  },
  statCell: {
    flexBasis: "47%",
    flexGrow: 1,
    minWidth: 140,
  },
});
