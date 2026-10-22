import { useState } from "react";
import { Action, Dialog, Label, Status, Warning } from "../../ui/components";
import { recorderClient } from "../../recorder/RecorderClient";
import {
  provisionalAggregateParameters,
  withoutErrorCode,
} from "../../product/sessionMath";
import type { SessionExportFormat } from "../../../modules/veymur-recorder";
import { Muted } from "./shared";

const formatTitles: Record<SessionExportFormat, string> = {
  csv: "Pulse values (CSV)",
  gpx: "Route (GPX)",
};

export function ExportDialog({
  visible,
  sessionId,
  routeRequested,
  onClose,
}: {
  visible: boolean;
  sessionId: string;
  routeRequested: boolean;
  onClose: () => void;
}) {
  const [pending, setPending] = useState<SessionExportFormat | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const exportAs = async (format: SessionExportFormat) => {
    setPending(format);
    setMessage(null);
    setError(null);
    try {
      const file = await recorderClient.exportSession({
        id: sessionId,
        format,
        parameters: provisionalAggregateParameters,
      });
      await recorderClient.shareExport(file.uri);
      setMessage(
        "Share dialog opened. Choose where to save or send the file. Nothing is uploaded by veymur.",
      );
    } catch (cause) {
      setError(
        cause instanceof Error
          ? withoutErrorCode(cause.message)
          : String(cause),
      );
    } finally {
      setPending(null);
    }
  };
  const close = () => {
    setMessage(null);
    setError(null);
    onClose();
  };
  return (
    <Dialog
      visible={visible}
      title="Export this session"
      onClose={close}
      closeTitle="Close"
    >
      <Label>Export works without an account or a subscription.</Label>
      <Action
        title={pending === "csv" ? "Preparing…" : formatTitles.csv}
        disabled={pending !== null}
        onPress={() => {
          void exportAs("csv");
        }}
      />
      {routeRequested ? (
        <Action
          title={pending === "gpx" ? "Preparing…" : formatTitles.gpx}
          disabled={pending !== null}
          onPress={() => {
            void exportAs("gpx");
          }}
        />
      ) : (
        <Muted>Route (GPX) is not available: the route was off.</Muted>
      )}
      <Muted>
        Exported files are not encrypted. The CSV contains your pulse values;
        the GPX contains precise coordinates. Copies you save elsewhere are not
        removed when you delete the session here.
      </Muted>
      {message && <Status title={message} />}
      {error && <Warning title="Couldn't export" detail={error} />}
    </Dialog>
  );
}
