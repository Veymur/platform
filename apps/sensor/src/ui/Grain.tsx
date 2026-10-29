/* eslint-disable @typescript-eslint/no-require-imports -- Metro asset loading uses require(). */
import { Image, StyleSheet, View } from "react-native";

/** Subtle paper grain, repeated behind the screen content. */
export function Grain() {
  return (
    <View accessible={false} pointerEvents="none" style={styles.grain}>
      <Image
        accessible={false}
        resizeMode="repeat"
        source={require("../../assets/textures/grain.png")}
        style={styles.image}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  grain: {
    position: "absolute",
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
  },
  image: {
    width: "100%",
    height: "100%",
    opacity: 0.5,
  },
});
