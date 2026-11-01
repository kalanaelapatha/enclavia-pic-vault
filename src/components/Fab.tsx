import React from 'react';
import { Pressable, StyleSheet, Text } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, radius, space } from '../theme';
import { Icon } from './Icon';

export function Fab({ label, onPress }: { label: string; onPress: () => void }) {
  const insets = useSafeAreaInsets();
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.fab, { bottom: insets.bottom + space.xl }, pressed && styles.pressed]}>
      <Icon name="plus" color={colors.onAccent} strokeWidth={2.4} />
      <Text style={styles.label}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fab: {
    position: 'absolute',
    right: space.xl,
    height: 56,
    paddingHorizontal: space.xl,
    borderRadius: radius.pill,
    backgroundColor: colors.accent,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    elevation: 6,
  },
  pressed: { opacity: 0.85 },
  label: { color: colors.onAccent, fontSize: 16, fontWeight: '700' },
});
