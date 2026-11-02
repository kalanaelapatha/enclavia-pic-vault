import React from 'react';
import { Modal, StyleSheet, Text, View } from 'react-native';
import { colors, radius, space, type } from '../theme';
import { Button } from './Button';

type Props = {
  visible: boolean;
  title: string;
  done: number;
  total: number;
  note?: string;
  /** Replaces the default "done of total" line. */
  caption?: string;
  onCancel?: () => void;
};

export function ProgressModal({ visible, title, done, total, note, caption, onCancel }: Props) {
  const pct = total > 0 ? Math.min(1, done / total) : 0;
  return (
    <Modal visible={visible} transparent animationType="fade" statusBarTranslucent navigationBarTranslucent onRequestClose={() => onCancel?.()}>
      <View style={styles.center}>
        <View style={styles.card}>
          <Text style={type.heading}>{title}</Text>
          <Text style={type.caption}>{caption ?? `${done} of ${total}`}</Text>
          <View style={styles.track}>
            <View style={[styles.fill, { width: `${Math.round(pct * 100)}%` }]} />
          </View>
          {note ? <Text style={type.caption}>{note}</Text> : null}
          {onCancel ? <Button label="Stop" kind="secondary" onPress={onCancel} /> : null}
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, justifyContent: 'center', padding: space.xl, backgroundColor: colors.scrim },
  card: { backgroundColor: colors.surface, borderRadius: radius.xl, padding: space.xl, gap: space.md },
  track: { height: 8, borderRadius: 4, backgroundColor: colors.surfaceHigh, overflow: 'hidden' },
  fill: { height: 8, borderRadius: 4, backgroundColor: colors.accent },
});
