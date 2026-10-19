import { useState } from 'react';
import { Modal, Pressable, ScrollView, StatusBar, View } from 'react-native';
import { DiagnosticRecorderScreen } from './screens/DiagnosticRecorderScreen';
import { ThemeProvider, useTheme, type ThemePreference } from './ui/Theme';
import { Action, Card, Dialog, Heading, Label, Status } from './ui/components';
const tabs = ['Record', 'History', 'Profile'] as const;
function Shell() {
  const { colors, isDark, preference, setPreference } = useTheme();
  const [tab, setTab] = useState<typeof tabs[number]>('Record');
  const [diagnostics, setDiagnostics] = useState(false);
  const [about, setAbout] = useState(false);
  return <View style={{ flex: 1, paddingTop: StatusBar.currentHeight ?? 0, backgroundColor: colors.bg }}>
    <StatusBar barStyle={isDark ? 'light-content' : 'dark-content'} backgroundColor={colors.bg} />
    <ScrollView contentContainerStyle={{ padding: 20, gap: 24 }}>
      <Label style={{ color: colors.tide }}>veymur/sensor</Label><Heading>{tab}</Heading>
      {tab === 'Record' && <Card><Status title="Data on this phone" detail="Local recording requires no account or internet connection." />
        <Label>Polar Verity Sense connection and recording are available in diagnostics. The recording screen is under development.</Label>
        <Action title="Open recorder diagnostics" onPress={() => setDiagnostics(true)} />
        <Action title="About the app" secondary onPress={() => setAbout(true)} />
      </Card>}
      {tab === 'History' && <Card><Label>This section is not connected to storage yet. Existing recordings are available in diagnostics.</Label><Action title="Open diagnostics" onPress={() => setDiagnostics(true)} /></Card>}
      {tab === 'Profile' && <><Card><Status title="No account" detail="Cloud synchronization is not connected." /></Card>
        <Card><Heading>Theme</Heading>{(['system', 'light', 'dark'] as ThemePreference[]).map((value, index) => <Pressable key={value}
          accessibilityRole="radio" accessibilityState={{ checked: preference === value }} onPress={() => setPreference(value)}
          style={{ minHeight: 48, padding: 12, borderWidth: 1, borderRadius: 10, borderColor: colors.edge, backgroundColor: preference === value ? colors['tide-wash'] : colors.raised }}>
          <Label>{preference === value ? '✓ ' : ''}{['System', 'Light', 'Dark'][index]}</Label>
        </Pressable>)}<Label>The selection lasts until restart. Text size follows your phone settings.</Label></Card></>}
    </ScrollView>
    <View accessibilityRole="tablist" style={{ flexDirection: 'row', flexWrap: 'wrap', padding: 8, paddingBottom: 24, gap: 8, backgroundColor: colors.raised }}>
      {tabs.map(value => <Pressable key={value} accessibilityRole="tab" accessibilityState={{ selected: tab === value }} onPress={() => setTab(value)}
        style={{ flexGrow: 1, flexBasis: 88, minHeight: 48, padding: 8, justifyContent: 'center', backgroundColor: tab === value ? colors['tide-wash'] : colors.raised }}>
        <Label style={{ textAlign: 'center' }}>{tab === value ? '• ' : ''}{value}</Label>
      </Pressable>)}
    </View>
    <Dialog visible={about} title="About veymur/sensor" onClose={() => setAbout(false)}><Label>For adults: heart rate recording with an optional phone route. Data is stored locally. The app does not draw medical conclusions.</Label></Dialog>
    <Modal visible={diagnostics} animationType="none" onRequestClose={() => setDiagnostics(false)}>
      <View style={{ flex: 1, paddingTop: StatusBar.currentHeight ?? 0, backgroundColor: colors.bg }}><Action title="Back to the app" secondary onPress={() => setDiagnostics(false)} /><DiagnosticRecorderScreen /></View>
    </Modal>
  </View>;
}
export function SensorApp() { return <ThemeProvider><Shell /></ThemeProvider>; }
