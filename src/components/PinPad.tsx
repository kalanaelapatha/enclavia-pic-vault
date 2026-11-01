import React, { useEffect, useRef } from 'react';
import { Animated, Pressable, StyleSheet, Text, View, Vibration } from 'react-native';
import { colors, space } from '../theme';
import { Icon } from './Icon';

type Props = {
  value: string;
  onChange: (v: string) => void;
  onSubmit: () => void;
  maxLength: number;
  minLength: number;
  disabled?: boolean;
  errorKey?: number; // bump to shake the dots
  leftKey?: { icon: 'fingerprint'; onPress: () => void; label: string } | null;
};

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];

/**
 * Custom keypad: the system keyboard is never used for the PIN, so keyboard apps
 * can't log or "learn" it.
 */
export function PinPad({ value, onChange, onSubmit, maxLength, minLength, disabled, errorKey, leftKey }: Props) {
  const shake = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!errorKey) {
      return;
    }
    Vibration.vibrate(60);
    Animated.sequence(
      [10, -10, 7, -7, 0].map(toValue => Animated.timing(shake, { toValue, duration: 55, useNativeDriver: true })),
    ).start();
  }, [errorKey, shake]);

  const press = (d: string) => {
    if (!disabled && value.length < maxLength) {
      onChange(value + d);
    }
  };
  const back = () => !disabled && onChange(value.slice(0, -1));
  const canSubmit = !disabled && value.length >= minLength;

  const dots = Math.max(minLength, value.length);

  return (
    <View style={styles.wrap}>
      <Animated.View style={[styles.dots, { transform: [{ translateX: shake }] }]}>
        {Array.from({ length: dots }).map((_, i) => (
          <View key={i} style={[styles.dot, i < value.length && styles.dotOn]} />
        ))}
      </Animated.View>
      <View style={styles.grid}>
        {KEYS.map(k => (
          <Key key={k} label={k} onPress={() => press(k)} disabled={disabled} />
        ))}
        {value.length > 0 ? (
          <Key icon="backspace" label="Delete" onPress={back} onLongPress={() => !disabled && onChange('')} disabled={disabled} subtle />
        ) : leftKey ? (
          <Key icon="fingerprint" label={leftKey.label} onPress={leftKey.onPress} disabled={disabled} subtle />
        ) : (
          <View style={[styles.key, styles.keySubtle]} />
        )}
        <Key label="0" onPress={() => press('0')} disabled={disabled} />
        <Key icon="check" label="Confirm" onPress={onSubmit} disabled={!canSubmit} accent />
      </View>
    </View>
  );
}

type KeyProps = {
  label: string;
  icon?: 'backspace' | 'check' | 'fingerprint';
  onPress: () => void;
  onLongPress?: () => void;
  disabled?: boolean;
  subtle?: boolean;
  accent?: boolean;
};

function Key({ label, icon, onPress, onLongPress, disabled, subtle, accent }: KeyProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      onLongPress={onLongPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.key,
        subtle && styles.keySubtle,
        accent && styles.keyAccent,
        disabled && styles.keyDisabled,
        pressed && styles.keyPressed,
      ]}>
      {icon ? (
        <Icon name={icon} size={26} color={accent ? colors.onAccent : colors.text} />
      ) : (
        <Text style={styles.keyText}>{label}</Text>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  wrap: { alignItems: 'center', width: '100%' },
  dots: { flexDirection: 'row', gap: 14, marginBottom: space.xxl, minHeight: 16, flexWrap: 'wrap', justifyContent: 'center' },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: 1.6, borderColor: colors.textFaint },
  dotOn: { backgroundColor: colors.accent, borderColor: colors.accent },
  grid: { flexDirection: 'row', flexWrap: 'wrap', width: 288, justifyContent: 'space-between', rowGap: 16 },
  key: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: colors.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
  },
  keySubtle: { backgroundColor: 'transparent' },
  keyAccent: { backgroundColor: colors.accent },
  keyDisabled: { opacity: 0.35 },
  keyPressed: { backgroundColor: colors.surfaceHigh },
  keyText: { fontSize: 30, fontWeight: '400', color: colors.text },
});
