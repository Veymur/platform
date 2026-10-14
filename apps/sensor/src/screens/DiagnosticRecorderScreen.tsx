import { useState } from 'react';
import { Button, Linking, Platform, ScrollView, StatusBar, StyleSheet, Switch, Text, View } from 'react-native';
import { DeviceList } from '../components/DeviceList';
import { RecorderStatus } from '../components/RecorderStatus';
import { useRecorder } from '../recorder/useRecorder';

function AndroidRecorder() {
  const { snapshot, pending, routeRequested, message, error, controller } = useRecorder();
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const busy = pending !== null;
  const ready = snapshot?.ready === true;
  const active = snapshot?.sessionId != null;
  const recording = snapshot?.sessionState === 'recording';
  const paused = snapshot?.sessionState === 'paused';
  const finishing = snapshot?.sessionState === 'finishing';
  return <View style={styles.root}><StatusBar barStyle="dark-content" /><ScrollView contentContainerStyle={styles.body}>
    <Text accessibilityRole="header" style={styles.title}>Veymur · recording validation</Text>
    <Text>Polar Verity Sense · Android{ '\n' }Data is stored on this phone. No account required.</Text>
    <RecorderStatus snapshot={snapshot} />
    <Button title="Find Verity Sense" disabled={!ready || busy || active} onPress={() => { void controller.search(); }} />
    <Button title="Cancel search" disabled={!ready || busy || active} onPress={() => { void controller.cancelSearch(); }} />
    <DeviceList devices={snapshot?.devices ?? []} disabled={!ready || busy || active} connect={id => { void controller.connect(id); }} />
    <View style={styles.route}>
      <Text style={{ flex: 1 }}>Record phone route</Text>
      <Switch accessibilityLabel="Record phone route" disabled={!ready || busy || active} value={routeRequested} onValueChange={value => { void controller.setRoute(value); }} />
    </View>
    <Button title="Start recording" disabled={!ready || busy || active || !snapshot?.connectedDeviceId} onPress={() => { void controller.start(); }} />
    <Button title={paused ? 'Resume' : 'Pause'} disabled={busy || !(recording || paused)} onPress={() => { void (paused ? controller.resume() : controller.pause()); }} />
    <Button title={snapshot?.sessionState === 'interrupted' && active ? 'Retry saving recording' : 'Finish recording'} disabled={!ready || busy || !active || finishing} onPress={() => { void controller.finish(); }} />
    <Button title="Export last session" disabled={!ready || busy || finishing} onPress={() => { void controller.exportLatest(); }} />
    <Button title="Refresh recorder" disabled={busy} onPress={() => { void controller.retry(); }} />
    <Button title="App permissions" disabled={busy} onPress={() => { setSettingsError(null); void Linking.openSettings().catch(() => setSettingsError('Could not open App permissions. Try again from the visible app.')); }} />
    <Text>Recording stops after a force stop. The saved portion is detected automatically on the next launch.</Text>
    <Text>The diagnostic ZIP contains heart rate and, when enabled, precise coordinates. Choose where to save or share it.</Text>
  </ScrollView><View style={styles.notice} accessibilityLiveRegion="polite">
    {pending && <Text accessibilityRole="alert">{pending} in progress… Waiting for confirmation.</Text>}
    {(error || settingsError) && <Text accessibilityRole="alert">{error || settingsError}</Text>}
    {message && <Text accessibilityRole="alert">{message}</Text>}
  </View></View>;
}
export function DiagnosticRecorderScreen() {
  return Platform.OS === 'android' ? <AndroidRecorder /> : <View style={styles.body}><Text>iOS is not supported yet. The native recorder is not implemented.</Text></View>;
}
const styles = StyleSheet.create({
  root: { flex: 1, paddingTop: StatusBar.currentHeight ?? 0, backgroundColor: '#ffffff' },
  notice: { paddingHorizontal: 24, paddingBottom: 48, gap: 8 },
  body: { padding: 24, paddingTop: 24, paddingBottom: 64, gap: 16, backgroundColor: '#ffffff' },
  title: { fontSize: 24, fontWeight: '600' },
  route: { flexDirection: 'row', alignItems: 'center', gap: 16 },
});
