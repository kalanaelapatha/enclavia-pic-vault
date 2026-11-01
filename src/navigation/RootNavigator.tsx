import React from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useSession } from '../state/SessionContext';
import { colors } from '../theme';
import type { RootStackParamList } from './types';
import { SetupPinScreen } from '../screens/SetupPinScreen';
import { LockScreen } from '../screens/LockScreen';
import { HomeScreen } from '../screens/HomeScreen';
import { AlbumScreen } from '../screens/AlbumScreen';
import { ViewerScreen } from '../screens/ViewerScreen';
import { PlayerScreen } from '../screens/PlayerScreen';
import { GalleryPickerScreen } from '../screens/GalleryPickerScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { ChangePinScreen } from '../screens/ChangePinScreen';
import { CropScreen } from '../screens/CropScreen';
import { TrimScreen } from '../screens/TrimScreen';

const Stack = createNativeStackNavigator<RootStackParamList>();

/**
 * Auth-gated navigation: while locked, the vault screens are not mounted at all,
 * so no decrypted content stays in the view tree.
 */
export function RootNavigator() {
  const { status } = useSession();

  if (status === 'loading') {
    return (
      <View style={styles.loading}>
        <ActivityIndicator color={colors.accent} />
      </View>
    );
  }

  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
        contentStyle: { backgroundColor: colors.bg },
        animation: 'slide_from_right',
      }}>
      {status === 'setup' ? (
        <Stack.Screen name="SetupPin" component={SetupPinScreen} options={{ animation: 'fade' }} />
      ) : status === 'locked' ? (
        <Stack.Screen name="Lock" component={LockScreen} options={{ animation: 'fade' }} />
      ) : (
        <>
          <Stack.Screen name="Home" component={HomeScreen} options={{ animation: 'fade' }} />
          <Stack.Screen name="Album" component={AlbumScreen} />
          <Stack.Screen
            name="Viewer"
            component={ViewerScreen}
            options={{ animation: 'fade', contentStyle: { backgroundColor: '#000' } }}
          />
          <Stack.Screen
            name="Player"
            component={PlayerScreen}
            options={{ animation: 'fade', contentStyle: { backgroundColor: '#000' } }}
          />
          <Stack.Screen name="Crop" component={CropScreen} options={{ animation: 'fade', contentStyle: { backgroundColor: '#000' } }} />
          <Stack.Screen name="Trim" component={TrimScreen} options={{ animation: 'fade', contentStyle: { backgroundColor: '#000' } }} />
          <Stack.Screen name="GalleryPicker" component={GalleryPickerScreen} options={{ animation: 'slide_from_bottom' }} />
          <Stack.Screen name="Settings" component={SettingsScreen} />
          <Stack.Screen name="ChangePin" component={ChangePinScreen} />
        </>
      )}
    </Stack.Navigator>
  );
}

const styles = StyleSheet.create({
  loading: { flex: 1, backgroundColor: colors.bg, alignItems: 'center', justifyContent: 'center' },
});
