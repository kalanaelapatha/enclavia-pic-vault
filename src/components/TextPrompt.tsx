import React, { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Modal, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { colors, radius, space, type } from '../theme';
import { Button } from './Button';

type Props = {
  visible: boolean;
  title: string;
  initialValue?: string;
  placeholder?: string;
  confirmLabel?: string;
  onCancel: () => void;
  onSubmit: (value: string) => void;
};

export function TextPrompt({ visible, title, initialValue = '', placeholder, confirmLabel = 'Save', onCancel, onSubmit }: Props) {
  const [value, setValue] = useState(initialValue);
  useEffect(() => {
    if (visible) {
      setValue(initialValue);
    }
  }, [visible, initialValue]);
  const trimmed = value.trim();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel} statusBarTranslucent navigationBarTranslucent>
      <KeyboardAvoidingView behavior="height" style={styles.center}>
        <Pressable style={styles.scrim} onPress={onCancel} />
        <View style={styles.card}>
          <Text style={type.heading}>{title}</Text>
          <TextInput
            value={value}
            onChangeText={setValue}
            placeholder={placeholder}
            placeholderTextColor={colors.textFaint}
            autoFocus
            maxLength={60}
            autoCorrect={false}
            autoComplete="off"
            importantForAutofill="no"
            // Album names are private: keep them out of the keyboard's learned words.
            keyboardType="visible-password"
            selectionColor={colors.accent}
            style={styles.input}
            onSubmitEditing={() => trimmed && onSubmit(trimmed)}
            returnKeyType="done"
          />
          <View style={styles.row}>
            <Button label="Cancel" kind="ghost" onPress={onCancel} style={styles.flex} />
            <Button label={confirmLabel} onPress={() => onSubmit(trimmed)} disabled={!trimmed} style={styles.flex} />
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, justifyContent: 'center', padding: space.xl },
  scrim: { ...StyleSheet.absoluteFill, backgroundColor: colors.scrim },
  card: { backgroundColor: colors.surface, borderRadius: radius.xl, padding: space.xl, gap: space.lg },
  input: {
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.md,
    paddingHorizontal: space.lg,
    paddingVertical: space.md,
    color: colors.text,
    fontSize: 16,
    borderWidth: 1,
    borderColor: colors.border,
  },
  row: { flexDirection: 'row', gap: space.md },
  flex: { flex: 1, paddingHorizontal: space.md },
});
