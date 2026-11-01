import React from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors, radius, space, type } from '../theme';
import { Icon, IconName } from './Icon';

export type SheetOption = {
  key: string;
  label: string;
  hint?: string;
  icon?: IconName;
  danger?: boolean;
  selected?: boolean;
  onPress: () => void;
};

type Props = { visible: boolean; title?: string; options: SheetOption[]; onClose: () => void };

/** Bottom action sheet. */
export function Sheet({ visible, title, options, onClose }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose} statusBarTranslucent navigationBarTranslucent>
      <Pressable style={styles.scrim} onPress={onClose} accessibilityLabel="Close" />
      <View style={[styles.sheet, { paddingBottom: insets.bottom + space.md }]}>
        <View style={styles.grabber} />
        {title ? <Text style={[type.caption, styles.title]}>{title}</Text> : null}
        <ScrollView bounces={false} style={styles.list}>
          {options.map(o => (
            <Pressable
              key={o.key}
              onPress={() => {
                onClose();
                o.onPress();
              }}
              style={({ pressed }) => [styles.option, pressed && styles.pressed]}>
              {o.icon ? <Icon name={o.icon} color={o.danger ? colors.danger : colors.text} /> : null}
              <View style={styles.optionText}>
                <Text style={[type.body, o.danger && { color: colors.danger }]}>{o.label}</Text>
                {o.hint ? <Text style={type.caption}>{o.hint}</Text> : null}
              </View>
              {o.selected ? <Icon name="check" color={colors.accent} /> : null}
            </Pressable>
          ))}
        </ScrollView>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  scrim: { ...StyleSheet.absoluteFill, backgroundColor: colors.scrim },
  sheet: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    maxHeight: '75%',
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingTop: space.sm,
  },
  grabber: { alignSelf: 'center', width: 40, height: 4, borderRadius: 2, backgroundColor: colors.border, marginBottom: space.sm },
  title: { paddingHorizontal: space.xl, paddingVertical: space.sm, textTransform: 'uppercase', letterSpacing: 0.8 },
  list: { flexGrow: 0 },
  option: { flexDirection: 'row', alignItems: 'center', gap: space.lg, paddingHorizontal: space.xl, paddingVertical: 14 },
  optionText: { flex: 1 },
  pressed: { backgroundColor: colors.surfaceAlt },
});
