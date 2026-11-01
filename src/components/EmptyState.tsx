import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, space, type } from '../theme';
import { Button } from './Button';
import { Icon, IconName } from './Icon';

type Props = { icon: IconName; title: string; body: string; actionLabel?: string; onAction?: () => void };

export function EmptyState({ icon, title, body, actionLabel, onAction }: Props) {
  return (
    <View style={styles.wrap}>
      <View style={styles.badge}>
        <Icon name={icon} size={30} color={colors.accent} />
      </View>
      <Text style={[type.heading, styles.center]}>{title}</Text>
      <Text style={[type.caption, styles.center, styles.body]}>{body}</Text>
      {actionLabel && onAction ? <Button label={actionLabel} icon="plus" onPress={onAction} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space.xxl, gap: space.md },
  badge: {
    width: 68,
    height: 68,
    borderRadius: 34,
    backgroundColor: colors.accentDim,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: space.sm,
  },
  center: { textAlign: 'center' },
  body: { maxWidth: 300, marginBottom: space.md, lineHeight: 19 },
});
