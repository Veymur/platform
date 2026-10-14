import { useEffect, useMemo, useState } from 'react';
import { initialRecorderView, RecorderController } from './RecorderController';
import { recorderPermissions } from './permissions';
export function useRecorder() {
  const controller = useMemo(() => new RecorderController(recorderPermissions), []);
  const [view, setView] = useState(initialRecorderView);
  useEffect(() => controller.mount(setView), [controller]);
  return { ...view, controller };
}
