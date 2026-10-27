import { useCallback, useEffect, useState, type ReactNode } from "react";
import { View } from "react-native";
import {
  Action,
  Card,
  Detail,
  Dialog,
  Label,
  Status,
  Warning,
} from "../../ui/components";
import { AppBar, Screen } from "../../ui/Screen";
import { HrColumns } from "../../ui/HrColumns";
import { SessionShape } from "../../ui/SessionShape";
import { useProduct } from "../../product/ProductContext";
import { forgetSessionShape } from "../../product/useSessionShapes";
import { localDataClient } from "../../recorder/LocalDataClient";
import type {
  DeletionResult,
  SessionDetails,
} from "../../../modules/veymur-recorder";
import {
  formatClock,
  formatDateTime,
  formatDistance,
  formatPercent,
  hrSampleFromRecord,
  provisionalAggregateParameters,
  sessionChartColumns,
  withoutErrorCode,
  type HrSample,
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
      secondary={!afterFinish}
      onPress={onClose}
    />
  );
  const frame = (content: ReactNode) => (
    <Screen appBar={<AppBar title="Session summary" />}>{content}</Screen>
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
        {close}
      </>,
    );
  if (!loaded)
    return frame(
      <>
        <Status title="Loading the session from this phone…" />
        {close}
      </>,
    );
  const { details, samples, chartLimited } = loaded;
  const { session, summary } = details;
  const deletable =
    session.state === "completed" || session.state === "interrupted";
  const ranges = session.hr_ranges_snapshot?.ranges ?? [];
  const covered = summary?.hr_covered_duration_ms ?? 0;
  return (
    <Screen appBar={<AppBar title="Session summary" />}>
      <Label>{formatDateTime(session.started_utc_ms, timeZone)}</Label>
      <Status title={stateText(session.state)} />
      {summary && (
        <View style={{ alignItems: "center" }}>
          <SessionShape
            label="Shape of this session"
            size={168}
            columns={sessionChartColumns(
              samples,
              summary.active_duration_ms,
              details.parameters.hr_stale_after_ms,
              48,
            )}
          />
        </View>
      )}
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
      <Card>
        <Detail
          title="Active time"
          value={formatClock(
            summary?.active_duration_ms ?? session.recorded_active_ms,
          )}
          note={
            summary ? undefined : "Recorder counter; pauses are not counted."
          }
        />
        {summary && (
          <>
            <Detail
              title="Distance"
              value={
                summary.gps_distance_m === null
                  ? session.route_requested
                    ? "Not available"
                    : "Route off"
                  : formatDistance(summary.gps_distance_m, units)
              }
              note={
                summary.gps_distance_m === null && session.route_requested
                  ? "No usable route segments were recorded."
                  : summary.gps_distance_m !== null
                    ? "Usable route segments only."
                    : undefined
              }
            />
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
            <Detail
              title="Pulse coverage"
              value={
                summary.hr_coverage === null
                  ? "Unknown"
                  : `Pulse recorded for ${formatPercent(summary.hr_coverage)} of active time`
              }
              note="Coverage shows how much we recorded, not how accurate the sensor is."
            />
          </>
        )}
      </Card>
      {summary && (
        <Card>
          <HrColumns
            title="Pulse over active time"
            columns={sessionChartColumns(
              samples,
              summary.active_duration_ms,
              details.parameters.hr_stale_after_ms,
            )}
          />
          {chartLimited && (
            <Muted>
              This session is long; the chart shows its first part. The numbers
              above cover the whole session.
            </Muted>
          )}
          <Muted>
            Gaps are shown, not filled. Pauses are marked with a line.
          </Muted>
        </Card>
      )}
      {summary && (
        <Card>
          <Subheading>Time in your ranges</Subheading>
          {ranges.length === 0 ? (
            <Muted>No ranges were set for this session.</Muted>
          ) : (
            <View style={{ gap: 8 }}>
              {ranges.map((range) => {
                const duration =
                  summary.range_durations.find(
                    (item) => item.range_id === range.id,
                  )?.duration_ms ?? 0;
                return (
                  <Label key={range.id}>
                    {range.label} ·{" "}
                    {covered > 0 ? formatPercent(duration / covered) : "—"}
                  </Label>
                );
              })}
              {summary.unassigned_hr_duration_ms > 0 && (
                <Label>
                  Outside your ranges ·{" "}
                  {covered > 0
                    ? formatPercent(summary.unassigned_hr_duration_ms / covered)
                    : "—"}
                </Label>
              )}
              <Muted>
                Ranges are the numbers you entered. Share of usable pulse time.
              </Muted>
            </View>
          )}
        </Card>
      )}
      {afterFinish && (
        <Muted>
          This session is saved on this phone. Cloud sync is not available in
          this version.
        </Muted>
      )}
      {deletable && summary && (
        <Action
          title="Export this session"
          secondary
          onPress={() => setExportVisible(true)}
        />
      )}
      {close}
      {deletable && (
        <Action
          title={deleting ? "Deleting…" : "Delete from this phone"}
          secondary
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
          onPress={() => {
            void remove();
          }}
        />
      </Dialog>
    </Screen>
  );
}
