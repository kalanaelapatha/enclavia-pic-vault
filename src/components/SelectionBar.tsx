import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, space } from '../theme';
import { Icon, IconName } from './Icon';

export type BarAction = { icon: IconName; label: string; onPress: () => void; danger?: boolean };

/** Bottom toolbar shown while items are selected. */
export function SelectionBar({ actions, disabled }: { actions: BarAction[]; disabled?: boolean }) {
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.bar, { paddingBottom: insets.bottom + space.sm }]}>
      {actions.map(a => (
        <Pressable
          key={a.label}
          disabled={disabled}
          onPress={a.onPress}
          accessibilityRole="button"
          style={({ pressed }) => [styles.action, pressed && styles.pressed, disabled && styles.disabled]}>
          <Icon name={a.icon} color={a.danger ? colors.danger : colors.text} />
          <Text style={[styles.label, a.danger && { color: colors.danger }]}>{a.label}</Text>
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
    paddingTop: space.sm,
  },
  action: { flex: 1, alignItems: 'center', gap: 4, paddingVertical: space.sm, borderRadius: 12 },
  pressed: { backgroundColor: colors.surfaceAlt },
  disabled: { opacity: 0.4 },
  label: { color: colors.text, fontSize: 12 },
});
