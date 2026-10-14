import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text } from 'react-native';
import { probe } from './modules/veymur-bridge-probe';

export default function App() {
  const [result, setResult] = useState('Checking native module…');
  useEffect(() => {
    let mounted = true;
    probe().then(value => {
      if (value.module !== 'VeymurBridgeProbe' || !value.encryptedRoundTrip ||
          value.polarSdkVersion !== '8.4.0' || !value.sqlCipherVersion.startsWith('4.6.1')) {
        throw new Error(`Unexpected native result: ${JSON.stringify(value)}`);
      }
      const message = `PROBE_OK ${JSON.stringify(value)}`;
      console.info(message);
      if (mounted) setResult(message);
    }).catch(error => {
      const message = `PROBE_FAILED ${String(error)}`;
      console.error(message);
      if (mounted) setResult(message);
    });
    return () => { mounted = false; };
  }, []);
  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.title}>Veymur · native bridge probe</Text>
      <Text selectable>{result}</Text>
      <Text>No recording or sensor connection. Android dependency check only.</Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 32, paddingTop: 72, gap: 24 },
  title: { fontSize: 24, fontWeight: '600' },
});
