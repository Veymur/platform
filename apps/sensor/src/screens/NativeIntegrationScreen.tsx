import { useEffect, useState } from 'react';
import { Platform, ScrollView, StyleSheet, Text } from 'react-native';
import { getModuleInfo } from '../../modules/veymur-recorder';

export function NativeIntegrationScreen() {
  const [status, setStatus] = useState('Checking the native module…');
  useEffect(() => {
    if (Platform.OS !== 'android') return;
    let mounted = true;
    getModuleInfo().then(info => {
      const message = `RECORDER_MODULE_OK ${JSON.stringify(info)}`;
      console.info(message);
      if (mounted) setStatus('Android native module connected.');
    }).catch(error => {
      console.error(`RECORDER_MODULE_FAILED ${String(error)}`);
      if (mounted) setStatus(String(error));
    });
    return () => { mounted = false; };
  }, []);

  const supportedPlatform = Platform.OS === 'android';
  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text accessibilityRole="header" style={styles.title}>Veymur Sensor</Text>
      <Text accessibilityRole="alert" selectable>
        {supportedPlatform ? status : 'iOS is not supported yet. The native recorder is not implemented.'}
      </Text>
      {supportedPlatform && <Text>
        Recording is not available in this build. Sensor connection and local saving will be added in the next stages.
      </Text>}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 32, paddingTop: 72, gap: 24 },
  title: { fontSize: 24, fontWeight: '600' },
});
