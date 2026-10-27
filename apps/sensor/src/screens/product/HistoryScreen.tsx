import { useCallback, useEffect, useState } from "react";
import { Pressable, StyleSheet, View } from "react-native";
import { Action, Label, Mark, Status, Warning } from "../../ui/components";
import { ContourArt } from "../../ui/Contours";
import { Icon } from "../../ui/Icon";
import { PrintShape } from "../../ui/PrintShape";
import { AppBar, Screen } from "../../ui/Screen";
import { useTheme } from "../../ui/Theme";
import { H2, Unit } from "../../ui/Typography";
import { fonts } from "../../ui/tokens";
import { useProduct } from "../../product/ProductContext";
import {
  useSessionShapes,
  type ShapeState,
} from "../../product/useSessionShapes";
import { localDataClient } from "../../recorder/LocalDataClient";
import type {
  HistoryCursor,
  SavedSession,
} from "../../../modules/veymur-recorder";
import {
  formatDateTime,
  formatDuration,
  withoutErrorCode,
} from "../../product/sessionMath";
import { Muted } from "./shared";

const pageSize = 20;
const errorText = (cause: unknown) =>
  cause instanceof Error ? withoutErrorCode(cause.message) : String(cause);

function ShapeThumbnail({ shape }: { shape: ShapeState | undefined }) {
  if (!shape || typeof shape === "string")
    return <View style={styles.thumbnailPlaceholder} />;
  return <PrintShape label="Session shape" size={60} columns={shape} />;
}

function averageBpm(shape: ShapeState | undefined): number | null {
  if (!shape || typeof shape === "string") return null;
  const values = shape.flatMap((column) =>
    column.bpm === null ? [] : [column.bpm],
  );
  return values.length
    ? Math.round(values.reduce((sum, value) => sum + value, 0) / values.length)
    : null;
}

export function HistoryScreen({
  onOpen,
  onRecord,
}: {
  onOpen: (id: string) => void;
  onRecord: () => void;
}) {
  const { colors } = useTheme();
  const { timeZone, recorder } = useProduct();
  const [items, setItems] = useState<SavedSession[] | null>(null);
  const [next, setNext] = useState<HistoryCursor | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const shapes = useSessionShapes(items, recorder.snapshot?.sessionId != null);
  const [pendingCleanup, setPendingCleanup] = useState<string[]>([]);
  const [cleanupMessage, setCleanupMessage] = useState<string | null>(null);
  const loadFirst = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await localDataClient.listSessions({
        limit: pageSize,
        before: null,
      });
      setItems(page.items);
      setNext(page.next);
      setPendingCleanup(
        (await localDataClient.pendingDeletions({ after_id: null, limit: 100 }))
          .ids,
      );
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void loadFirst();
  }, [loadFirst]);
  const loadMore = async () => {
    if (!next) return;
    setLoading(true);
    setError(null);
    try {
      const page = await localDataClient.listSessions({
        limit: pageSize,
        before: next,
      });
      setItems((current) => [
        ...(current ?? []),
        ...page.items.filter(
          (item) => !current?.some((existing) => existing.id === item.id),
        ),
      ]);
      setNext(page.next);
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setLoading(false);
    }
  };
  const retryCleanup = async () => {
    setLoading(true);
    setCleanupMessage(null);
    try {
      const results = await Promise.all(
        pendingCleanup.map((id) => localDataClient.deleteSession(id)),
      );
      setCleanupMessage(
        results.every((result) => result.cleanup === "complete")
          ? "Cleanup finished."
          : "Some files still could not be removed. Try again later.",
      );
      setPendingCleanup(
        (await localDataClient.pendingDeletions({ after_id: null, limit: 100 }))
          .ids,
      );
    } catch (cause) {
      setCleanupMessage(errorText(cause));
    } finally {
      setLoading(false);
    }
  };
  return (
    <Screen
      appBar={
        <AppBar
          title="History"
          right={<Mark label="Works offline" icon="on-phone" />}
        />
      }
    >
      {pendingCleanup.length > 0 && (
        <>
          <Warning
            title={`${pendingCleanup.length} deleted session${pendingCleanup.length === 1 ? "" : "s"} still need cleanup`}
            detail="Their data is deleted, but temporary files remain on this phone."
          />
          <Action
            title="Retry cleanup"
            disabled={loading}
            onPress={() => {
              void retryCleanup();
            }}
          />
        </>
      )}
      {cleanupMessage && <Status title={cleanupMessage} />}
      {error && (
        <>
          <Warning title="Couldn't load history" detail={error} />
          <Action
            title="Try again"
            disabled={loading}
            onPress={() => {
              void loadFirst();
            }}
          />
        </>
      )}
      {items === null && !error && <Status title="Loading sessions…" />}
      {items?.length === 0 && (
        <View style={styles.empty}>
          <View style={styles.emptyArt}>
            <ContourArt
              rings={5}
              baseR={14}
              gap={12}
              size={150}
              seed={12}
              wobble={0.14}
            />
          </View>
          <H2 style={{ textAlign: "center" }}>
            Your sessions will appear here
          </H2>
          <Muted>
            Start a recording from the Record tab. Every session leaves its own
            shape.
          </Muted>
          <Action title="Go to Record" onPress={onRecord} />
        </View>
      )}
      {items && items.length > 0 && (
        <View style={styles.list}>
          {items.map((item) => {
            const average = averageBpm(shapes.get(item.id));
            return (
              <Pressable
                key={item.id}
                accessibilityRole="button"
                accessibilityLabel={`${formatDateTime(item.started_utc_ms, timeZone)}, ${formatDuration(item.recorded_active_ms)}, on this phone${average === null ? "" : `, average ${average} bpm`}`}
                onPress={() => onOpen(item.id)}
                style={({ pressed }) => [
                  styles.row,
                  { backgroundColor: pressed ? colors.sunk : "transparent" },
                ]}
              >
                <ShapeThumbnail shape={shapes.get(item.id)} />
                <View style={styles.rowBody}>
                  <Label style={{ fontFamily: fonts.uiMedium }}>
                    Session · {formatDuration(item.recorded_active_ms)}
                  </Label>
                  <Label
                    style={{
                      color: colors["ink-3"],
                      fontSize: 14,
                      lineHeight: 20.3,
                    }}
                  >
                    {formatDateTime(item.started_utc_ms, timeZone)}
                  </Label>
                  <View style={styles.marks}>
                    <Mark icon="on-phone" label="On phone" />
                    <Mark icon="cloud" label="Not uploaded" />
                    {item.state === "interrupted" && (
                      <Mark icon="offline" label="Interrupted" />
                    )}
                  </View>
                </View>
                <View style={styles.rowEnd}>
                  {average !== null && (
                    <Label
                      style={{
                        fontFamily: fonts.uiSemibold,
                        fontSize: 16,
                        lineHeight: 18,
                      }}
                    >
                      {average}
                      <Unit size={12}> bpm</Unit>
                    </Label>
                  )}
                  <Icon
                    name="chevron-right"
                    size={20}
                    color={colors["ink-3"]}
                  />
                </View>
              </Pressable>
            );
          })}
        </View>
      )}
      {next && (
        <Action
          title={loading ? "Loading…" : "Show older sessions"}
          variant="secondary"
          disabled={loading}
          onPress={() => {
            void loadMore();
          }}
        />
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  list: { gap: 4 },
  row: {
    minHeight: 76,
    paddingVertical: 12,
    paddingHorizontal: 12,
    marginHorizontal: -12,
    borderRadius: 16,
    flexDirection: "row",
    alignItems: "center",
    gap: 16,
  },
  rowBody: { flex: 1, minWidth: 0, gap: 4 },
  marks: { flexDirection: "row", flexWrap: "wrap", gap: 12 },
  rowEnd: {
    alignItems: "flex-end",
    justifyContent: "center",
    gap: 6,
    flexShrink: 0,
  },
  thumbnailPlaceholder: { width: 60, height: 60 },
  empty: {
    alignItems: "center",
    gap: 16,
    paddingVertical: 48,
  },
  emptyArt: { width: 144, height: 144 },
});
