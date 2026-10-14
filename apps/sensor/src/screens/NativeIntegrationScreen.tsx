import { useEffect, useState } from 'react';
import { Platform, ScrollView, StyleSheet, Text } from 'react-native';
import { getModuleInfo } from '../../modules/veymur-recorder';
import { recorderClient } from '../recorder/RecorderClient';

export function NativeIntegrationScreen() {
  const [status, setStatus] = useState('Checking the native module…');
  useEffect(() => {
    if (Platform.OS !== 'android') return;
    let mounted = true;
    let unsubscribe: (() => void) | undefined;
    let reportedReady = false;
    getModuleInfo().then(info => {
      const message = `RECORDER_MODULE_OK ${JSON.stringify(info)}`;
      console.info(message);
      if (!mounted) return;
      unsubscribe = recorderClient.subscribe(snapshot => {
        setStatus(snapshot.ready ? 'Android recorder bridge connected. Local storage ready.' : 'Preparing local storage…');
        if (snapshot.ready && !reportedReady) {
          reportedReady = true;
          console.info(`RECORDER_BRIDGE_OK revision=${snapshot.revision} ready=true`);
        }
      }, error => { if (mounted) setStatus(String(error)); });
    }).catch(error => {
      console.error(`RECORDER_MODULE_FAILED ${String(error)}`);
      if (mounted) setStatus(String(error));
    });
    return () => { mounted = false; unsubscribe?.(); };
  }, []);

  const supportedPlatform = Platform.OS === 'android';
  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text accessibilityRole="header" style={styles.title}>Veymur Sensor</Text>
      <Text accessibilityRole="alert" selectable>
        {supportedPlatform ? status : 'iOS is not supported yet. The native recorder is not implemented.'}
      </Text>
      {supportedPlatform && <Text>
        The native recorder is available through the bridge. Recording controls and permission dialogs will be added in the next stage.
      </Text>}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 32, paddingTop: 72, gap: 24 },
  title: { fontSize: 24, fontWeight: '600' },
});
