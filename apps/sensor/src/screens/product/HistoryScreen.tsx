import { useCallback, useEffect, useState } from "react";
import { Pressable, View } from "react-native";
import { Action, Label, Mark, Status, Warning } from "../../ui/components";
import { AppBar, Screen } from "../../ui/Screen";
import { useTheme } from "../../ui/Theme";
import { radius, spacing } from "../../ui/tokens";
import { useProduct } from "../../product/ProductContext";
import {
  useSessionShapes,
  type ShapeState,
} from "../../product/useSessionShapes";
import { SessionShape } from "../../ui/SessionShape";
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
const stateLabel = (state: SavedSession["state"]) =>
  state === "completed"
    ? "Saved"
    : state === "interrupted"
      ? "Interrupted · saved part"
      : "Recording now";
const errorText = (cause: unknown) =>
  cause instanceof Error ? withoutErrorCode(cause.message) : String(cause);

function ShapeThumbnail({ shape }: { shape: ShapeState | undefined }) {
  if (shape === undefined || typeof shape === "string")
    return (
      <View
        style={{ width: 64, height: 64 }}
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
      />
    );
  return (
    <View
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <SessionShape label="Session shape" size={64} columns={shape} />
    </View>
  );
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
        <View style={{ gap: 16 }}>
          <Label style={{ fontSize: 20, fontWeight: "600" }}>
            Your sessions will appear here
          </Label>
          <Muted>Start a recording from the Record tab.</Muted>
          <Action title="Go to Record" onPress={onRecord} />
        </View>
      )}
      {items?.map((item) => (
        <Pressable
          key={item.id}
          accessibilityRole="button"
          accessibilityLabel={`${formatDateTime(item.started_utc_ms, timeZone)}, ${formatDuration(item.recorded_active_ms)}, ${stateLabel(item.state)}, on this phone`}
          onPress={() => onOpen(item.id)}
          style={({ pressed }) => ({
            minHeight: spacing.tap,
            padding: 16,
            gap: 16,
            flexDirection: "row",
            alignItems: "center",
            borderWidth: 1,
            borderRadius: radius.medium,
            borderColor: colors.rule,
            backgroundColor: pressed ? colors.sunk : colors.raised,
          })}
        >
          <ShapeThumbnail shape={shapes.get(item.id)} />
          <View style={{ flex: 1, gap: 4 }}>
            <Label style={{ fontWeight: "600" }}>
              {formatDateTime(item.started_utc_ms, timeZone)}
            </Label>
            <Label>
              {formatDuration(item.recorded_active_ms)} ·{" "}
              {item.route_requested ? "pulse and route" : "pulse only"}
            </Label>
            <Label style={{ color: colors["ink-2"] }}>
              {stateLabel(item.state)} · 📱 On this phone
            </Label>
          </View>
        </Pressable>
      ))}
      {next && (
        <Action
          title={loading ? "Loading…" : "Show older sessions"}
          secondary
          disabled={loading}
          onPress={() => {
            void loadMore();
          }}
        />
      )}
    </Screen>
  );
}
