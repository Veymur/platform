import { useEffect, useMemo, useState } from "react";
import {
  initialRecorderView,
  RecorderController,
  type RecorderPermissions,
} from "./RecorderController";
import { recorderPermissions } from "./permissions";
export function useRecorder(
  permissions: RecorderPermissions = recorderPermissions,
) {
  const controller = useMemo(
    () => new RecorderController(permissions),
    [permissions],
  );
  const [view, setView] = useState(initialRecorderView);
  useEffect(() => controller.mount(setView), [controller]);
  return { ...view, controller };
}
