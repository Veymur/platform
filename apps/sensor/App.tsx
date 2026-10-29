import { SensorApp } from "./src/SensorApp";
import { useVeymurFonts } from "./src/ui/fonts";

export default function App() {
  // Fonts load asynchronously; on failure the system-font fallback remains usable.
  useVeymurFonts();
  return <SensorApp />;
}
