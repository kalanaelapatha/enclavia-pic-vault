import React, { useState } from 'react';
import { StatusBar, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { IconButton } from '../components/Header';
import VaultVideoView from '../native/VaultVideoViewNativeComponent';
import type { ScreenProps } from '../navigation/types';
import { space } from '../theme';

/** Plays an encrypted video; frames are decrypted in memory as they stream. */
export function PlayerScreen({ navigation, route }: ScreenProps<'Player'>) {
  const insets = useSafeAreaInsets();
  const [error, setError] = useState<string | null>(null);
  return (
    <View style={styles.flex}>
      <StatusBar hidden />
      <VaultVideoView
        style={[StyleSheet.absoluteFill, { top: insets.top + 56, bottom: insets.bottom }]}
        itemId={route.params.itemId}
        onPlaybackError={e => setError(e.nativeEvent.message)}
      />
      <View style={[styles.top, { paddingTop: insets.top + space.xs }]}>
        <IconButton icon="back" label="Back" onPress={() => navigation.goBack()} />
        <Text numberOfLines={1} style={styles.title}>
          {route.params.name}
        </Text>
      </View>
      {error ? (
        <View style={styles.errorWrap} pointerEvents="none">
          <Text style={styles.error}>This video can’t be played on this device ({error}).</Text>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: '#000' },
  top: { position: 'absolute', top: 0, left: 0, right: 0, flexDirection: 'row', alignItems: 'center', paddingHorizontal: space.sm },
  title: { flex: 1, color: '#fff', fontSize: 16, fontWeight: '600', paddingHorizontal: space.xs },
  errorWrap: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center', padding: space.xxl },
  error: { color: '#fff', textAlign: 'center', fontSize: 15 },
});
