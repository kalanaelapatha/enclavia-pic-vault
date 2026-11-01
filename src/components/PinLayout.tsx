import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, space, type } from '../theme';
import { Icon, IconName } from './Icon';

type Props = {
  icon: IconName;
  title: string;
  subtitle?: string;
  error?: string | null;
  children: React.ReactNode;
  footer?: React.ReactNode;
  top?: React.ReactNode;
};

export function PinLayout({ icon, title, subtitle, error, children, footer, top }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.wrap, { paddingTop: insets.top + space.lg, paddingBottom: insets.bottom + space.lg }]}>
      <View style={styles.top}>{top}</View>
      <View style={styles.head}>
        <View style={styles.badge}>
          <Icon name={icon} size={30} color={colors.accent} />
        </View>
        <Text style={[type.title, styles.center]}>{title}</Text>
        {subtitle ? <Text style={[type.caption, styles.center, styles.subtitle]}>{subtitle}</Text> : null}
        <Text style={[styles.error]} accessibilityLiveRegion="polite">
          {error ?? ' '}
        </Text>
      </View>
      {children}
      <View style={styles.footer}>{footer}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: colors.bg, alignItems: 'center', paddingHorizontal: space.xl },
  top: { alignSelf: 'stretch', minHeight: 44 },
  head: { alignItems: 'center', gap: space.sm, marginTop: space.lg, marginBottom: space.xl, flexShrink: 1 },
  badge: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: colors.accentDim,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: space.sm,
  },
  center: { textAlign: 'center' },
  subtitle: { maxWidth: 300, lineHeight: 19 },
  error: { color: colors.danger, fontSize: 14, minHeight: 20, textAlign: 'center' },
  footer: { flex: 1, justifyContent: 'flex-end', alignItems: 'center' },
});
