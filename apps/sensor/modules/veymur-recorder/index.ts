export { getModuleInfo, nativeRecorder } from './src';
export type * from './src';

// Separate namespace: local SessionState is independent of the diagnostic bridge.
export type * as LocalData from './src/local-data/types';
