import { Button, Text, View } from 'react-native';
import type { RecorderSnapshot } from '../recorder/types';
export function DeviceList({ devices, disabled, connect }: { devices: RecorderSnapshot['devices']; disabled: boolean; connect: (id: string) => void }) {
  return <View style={{ gap: 12 }}>
    <Text accessibilityRole="header">Discovered sensors</Text>
    {devices.length === 0 && <Text>No sensors listed. Turn on your Verity Sense and search.</Text>}
    {devices.map(device => <View key={device.id}>
      <Text selectable>{device.name} · {device.id}</Text>
      <Button title={`Connect ${device.id}`} disabled={disabled} onPress={() => connect(device.id)} />
    </View>)}
  </View>;
}
