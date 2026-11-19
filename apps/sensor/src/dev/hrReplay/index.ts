import type { ReplaySession } from "./replayPlayback";
import { ReplayRecorderClient } from "./ReplayRecorderClient";

const replaySessionFiles = __DEV__
  ? require.context("./sessions", false, /\.json$/)
  : null;

function requestedReplaySpeed(): number {
  const speed = Number(process.env.EXPO_PUBLIC_HR_REPLAY_SPEED ?? "1");
  return Number.isFinite(speed) && speed > 0 && speed <= 60 ? speed : 1;
}

function selectReplaySession(): ReplaySession | null {
  const requestedName = process.env.EXPO_PUBLIC_HR_REPLAY;
  if (!replaySessionFiles || !requestedName) return null;
  const availableFiles = replaySessionFiles.keys().sort();
  const selectedFile =
    requestedName === "1"
      ? availableFiles[0]
      : availableFiles.find((file) => file === `./${requestedName}.json`);
  if (!selectedFile) {
    const availableNames = availableFiles
      .map((file) => file.replace(/^\.\//, "").replace(/\.json$/, ""))
      .join(", ");
    throw new Error(
      `HR replay "${requestedName}" not found in src/dev/hrReplay/sessions. Available: ${availableNames || "none, run npm run replay:prepare first"}.`,
    );
  }
  const session = replaySessionFiles(selectedFile) as ReplaySession;
  if (session.schema !== "veymur-hr-replay-v1")
    throw new Error(`${selectedFile} is not a veymur-hr-replay-v1 file.`);
  return session;
}

const selectedReplaySession = selectReplaySession();

export const hrReplayEnabled = selectedReplaySession !== null;

export const hrReplayClient = selectedReplaySession
  ? new ReplayRecorderClient(selectedReplaySession, requestedReplaySpeed())
  : null;
