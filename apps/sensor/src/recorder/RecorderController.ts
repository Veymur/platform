import { recorderClient, RecorderClientError } from "./RecorderClient";
import type {
  RecorderSnapshot,
  DiagnosticStatus,
  SessionResult,
} from "./types";

export interface RecorderPermissions {
  bluetoothGranted(): Promise<boolean>;
  requestBluetooth(): Promise<boolean>;
  requestRoute(): Promise<boolean>;
  notifications(): Promise<void>;
  confirmFinish(): Promise<boolean>;
  confirmDeleteDiagnostic(id: string): Promise<boolean>;
}
export type RecorderView = {
  captures: DiagnosticStatus[];
  selectedCaptureId: string | null;
  snapshot: RecorderSnapshot | null;
  pending: string | null;
  routeRequested: boolean;
  message: string | null;
  error: string | null;
  saved: SessionResult | null;
};
export const initialRecorderView: RecorderView = {
  captures: [],
  selectedCaptureId: null,
  snapshot: null,
  pending: null,
  routeRequested: false,
  message: null,
  error: null,
  saved: null,
};
const errorText = (error: unknown) =>
  error instanceof RecorderClientError
    ? `${error.code}: ${error.message}`
    : String(error);

/** No recording timer or lifecycle commands: this controller only renders native facts and user intent. */
export class RecorderController {
  private view: RecorderView = { ...initialRecorderView };
  private generation = 0;
  private capturesRequest = 0;
  private attached = false;
  private unsubscribe?: () => void;
  constructor(
    private readonly permissions: RecorderPermissions,
    private readonly client = recorderClient,
  ) {}
  private listener?: (view: RecorderView) => void;
  private update(patch: Partial<RecorderView>) {
    this.view = { ...this.view, ...patch };
    if (this.attached) this.listener?.(this.view);
  }
  mount(listener: (view: RecorderView) => void): () => void {
    this.attached = true;
    this.listener = listener;
    const generation = ++this.generation;
    listener(this.view);
    void this.client
      .getModuleInfo()
      .then(() => {
        if (!this.attached || generation !== this.generation) return;
        this.observe();
      })
      .catch((error) => {
        if (this.attached && generation === this.generation)
          this.update({ error: errorText(error) });
      });
    return () => {
      this.attached = false;
      ++this.generation;
      this.unsubscribe?.();
      this.unsubscribe = undefined;
      this.listener = undefined;
      // Do not finish, pause, cancel discovery or change native Activity state here.
    };
  }
  private observe() {
    if (this.unsubscribe) return;
    this.unsubscribe = this.client.subscribe(
      (snapshot) => {
        const captures = this.view.captures.filter(
          (capture) => capture.captureId !== snapshot.diagnostic.captureId,
        );
        if (snapshot.diagnostic.captureId) captures.push(snapshot.diagnostic);
        this.update({
          snapshot,
          captures,
          selectedCaptureId:
            this.view.selectedCaptureId ?? snapshot.diagnostic.captureId,
          ...(snapshot.sessionId
            ? { routeRequested: snapshot.routeEnabled }
            : {}),
        });
      },
      (error) => this.update({ error: errorText(error) }),
    );
    const generation = this.generation;
    void this.refreshCaptures().catch((error) => {
      if (this.attached && generation === this.generation)
        this.update({ error: errorText(error) });
    });
  }
  private async refreshCaptures() {
    const generation = this.generation;
    const request = ++this.capturesRequest;
    const captures = await this.client.listDiagnosticCaptures();
    if (
      !this.attached ||
      generation !== this.generation ||
      request !== this.capturesRequest
    )
      return;
    const latest = this.view.snapshot?.diagnostic;
    if (latest?.captureId) {
      const index = captures.findIndex((c) => c.captureId === latest.captureId);
      if (index < 0) captures.push(latest);
      else captures[index] = latest;
    }
    const selected = captures.some(
      (c) => c.captureId === this.view.selectedCaptureId,
    )
      ? this.view.selectedCaptureId
      : (captures.at(-1)?.captureId ?? null);
    this.update({ captures, selectedCaptureId: selected });
  }
  selectCapture = (id: string) => {
    if (!this.view.pending) this.update({ selectedCaptureId: id });
  };
  exportDiagnostic = () =>
    this.run("Export diagnostics", async (current) => {
      const id = this.view.selectedCaptureId;
      if (!id)
        throw new RecorderClientError(
          "INVALID_ARGUMENT",
          "Select a diagnostic capture first.",
        );
      const file = await this.client.exportDiagnostic(id);
      if (!current()) return;
      await this.client.shareExport(file.uri);
      if (current())
        this.update({
          message:
            "Share dialog opened for the unencrypted capture ZIP. Temporary copies are removed after 24 hours while the app runs.",
        });
    });
  deleteDiagnostic = () =>
    this.run("Delete diagnostics", async (current) => {
      const id = this.view.selectedCaptureId;
      if (!id)
        throw new RecorderClientError(
          "INVALID_ARGUMENT",
          "Select a diagnostic capture first.",
        );
      await this.client.ensureActivity();
      if (!(await this.permissions.confirmDeleteDiagnostic(id)) || !current())
        return;
      await this.client.deleteDiagnostic(id);
      if (!current()) return;
      await this.refreshCaptures();
      if (current())
        this.update({
          message:
            "Capture and its temporary export copies deleted from this phone. Copies saved in other apps remain there.",
        });
    });
  private async run(
    name: string,
    action: (current: () => boolean) => Promise<void>,
  ) {
    if (this.view.pending || !this.attached) return;
    const generation = this.generation;
    const current = () => this.attached && generation === this.generation;
    this.update({ pending: name, error: null, message: null });
    try {
      await action(current);
    } catch (error) {
      if (current()) this.update({ error: errorText(error) });
    } finally {
      this.update({ pending: null });
    }
  }
  search = () =>
    this.run("Search", async (current) => {
      await this.client.ensureActivity();
      const granted = await this.permissions.requestBluetooth();
      if (!current()) return;
      if (!granted) {
        this.update({
          message:
            "Bluetooth permission denied. Open App permissions or try searching again.",
        });
        return;
      }
      await this.client.search();
    });
  cancelSearch = () =>
    this.run("Cancel search", async () => {
      await this.client.cancelSearch();
    });
  connect = (id: string) =>
    this.run("Connect", async (current) => {
      if (!(await this.permissions.bluetoothGranted()))
        throw new RecorderClientError(
          "PERMISSION_DENIED",
          "Use Find Verity Sense to grant Bluetooth permission.",
        );
      if (current()) await this.client.connect(id);
    });
  setRoute = (enabled: boolean) =>
    this.run("Route permission", async (current) => {
      if (this.view.snapshot?.sessionId)
        throw new RecorderClientError(
          "INVALID_STATE",
          "Change the route before starting a session.",
        );
      if (!enabled) {
        this.update({ routeRequested: false });
        return;
      }
      await this.client.ensureActivity();
      const granted = await this.permissions.requestRoute();
      if (!current()) return;
      this.update({
        routeRequested: granted,
        message: granted
          ? "Phone route enabled for the next session."
          : "Precise location permission denied. You can record heart rate without a route.",
      });
    });
  start = () =>
    this.run("Start", async (current) => {
      await this.client.ensureActivity();
      if (!(await this.permissions.bluetoothGranted()))
        throw new RecorderClientError(
          "PERMISSION_DENIED",
          "Grant Bluetooth permission through Find Verity Sense first.",
        );
      await this.permissions.notifications();
      if (!current()) return;
      await this.client.start({ routeRequested: this.view.routeRequested });
      if (current())
        this.update({ message: "Recording started on this phone." });
    });
  startDiagnostic = () =>
    this.run("Start diagnostics", async (current) => {
      await this.client.ensureActivity();
      const granted = await this.permissions.requestBluetooth();
      if (!current()) return;
      if (!granted) {
        this.update({
          message:
            "Bluetooth permission denied. Diagnostics remain off. Retry or open App permissions.",
        });
        return;
      }
      await this.permissions.notifications();
      if (!current()) return;
      await this.client.startDiagnostic();
      if (current())
        this.update({ message: "Diagnostic capture enabled on this phone." });
    });
  stopDiagnostic = () =>
    this.run("Stop diagnostics", async (current) => {
      await this.client.stopDiagnostic();
      if (current()) {
        await this.refreshCaptures();
        this.update({ message: "Diagnostic capture saved and closed." });
      }
    });
  pause = () =>
    this.run("Pause", async () => {
      await this.client.pause();
    });
  resume = () =>
    this.run("Resume", async () => {
      await this.client.resume();
    });
  finish = () =>
    this.run("Finish", async (current) => {
      await this.client.ensureActivity();
      if (!(await this.permissions.confirmFinish()) || !current()) return;
      const result = await this.client.finish();
      if (current())
        this.update({
          saved: result,
          message:
            result.state === "interrupted"
              ? "Available portion saved on this phone. Recording was interrupted; the uncommitted tail is unknown."
              : "Recording saved on this phone.",
        });
    });
  exportLatest = () =>
    this.run("Export", async (current) => {
      const file = await this.client.exportLatest();
      if (!current()) return;
      await this.client.shareExport(file.uri);
      if (current())
        this.update({
          message:
            "Share dialog opened. Choose an app to save or share the ZIP.",
        });
    });
  retry = () =>
    this.run("Refresh", async (current) => {
      await this.client.getModuleInfo();
      if (!current()) return;
      this.observe();
      await this.refreshCaptures();
      const snapshot = await this.client.getSnapshot();
      if (
        current() &&
        (!this.view.snapshot || snapshot.revision > this.view.snapshot.revision)
      )
        this.update({ snapshot });
    });
}
