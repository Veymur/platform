import { nativeRecorder, type NativeRecorderModule } from '../../modules/veymur-recorder';
import { encodeLocalRequest, decodeLocalResult, type LocalOperations } from '../../modules/veymur-recorder/src/local-data/operations';
import { RecorderClientError, recorderError } from './RecorderClient';

/** All operations run on the native owner; this adapter never opens a database. */
export class LocalDataClient {
  constructor(private readonly load: () => NativeRecorderModule = nativeRecorder) {}
  listSessions(request: LocalOperations['list']['request']) { return this.execute('list', request); }
  getSessionDetails(request: LocalOperations['details']['request']) { return this.execute('details', request); }
  readStream(request: LocalOperations['stream']['request']) { return this.execute('stream', request); }
  getPreferences() { return this.execute('preferences', {}); }
  saveSettings(request: LocalOperations['saveSettings']['request']) { return this.execute('saveSettings', request); }
  saveRanges(request: LocalOperations['saveRanges']['request']) { return this.execute('saveRanges', request); }
  deleteSession(id: string) { return this.execute('delete', { id }); }
  pendingDeletions(request: LocalOperations['pendingDeletions']['request']) { return this.execute('pendingDeletions', request); }
  async execute<K extends keyof LocalOperations>(operation: K, request: LocalOperations[K]['request']): Promise<LocalOperations[K]['result']> {
    let encoded: string;
    try { encoded = encodeLocalRequest(operation, request); }
    catch { throw new RecorderClientError('INVALID_ARGUMENT', 'Invalid local data request.'); }
    let value: unknown;
    try { value = await this.load().localData(encoded); } catch(error) { throw recorderError(error); }
    try { return decodeLocalResult(operation, value); }
    catch { throw new RecorderClientError('INCOMPATIBLE_BINARY', 'Invalid local data response; rebuild the app.'); }
  }
}
export const localDataClient = new LocalDataClient();
