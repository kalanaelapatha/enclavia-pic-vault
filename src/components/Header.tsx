import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, space, type } from '../theme';
import { Icon, IconName } from './Icon';

export type HeaderAction = { icon: IconName; onPress: () => void; label: string; color?: string };

type Props = {
  title: string;
  subtitle?: string;
  onBack?: () => void;
  backIcon?: IconName;
  actions?: HeaderAction[];
  large?: boolean;
};

export function IconButton({ icon, onPress, label, color }: HeaderAction) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      hitSlop={8}
      style={({ pressed }) => [styles.iconBtn, pressed && styles.pressed]}>
      <Icon name={icon} color={color ?? colors.text} />
    </Pressable>
  );
}

export function Header({ title, subtitle, onBack, backIcon = 'back', actions = [], large }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.wrap, { paddingTop: insets.top + space.sm }]}>
      <View style={styles.row}>
        {onBack ? <IconButton icon={backIcon} onPress={onBack} label="Back" /> : null}
        <View style={[styles.titles, !onBack && styles.titlesNoBack]}>
          <Text numberOfLines={1} style={large ? type.title : type.heading}>
            {title}
          </Text>
          {subtitle ? (
            <Text numberOfLines={1} style={type.caption}>
              {subtitle}
            </Text>
          ) : null}
        </View>
        {actions.map(a => (
          <IconButton key={a.label} {...a} />
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { backgroundColor: colors.bg, paddingHorizontal: space.sm, paddingBottom: space.sm },
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 48 },
  titles: { flex: 1, paddingHorizontal: space.xs },
  titlesNoBack: { paddingLeft: space.md },
  iconBtn: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  pressed: { backgroundColor: colors.surfaceHigh },
});
