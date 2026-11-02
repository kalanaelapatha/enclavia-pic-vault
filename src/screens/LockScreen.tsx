import React, { useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text } from 'react-native';
import { PinLayout } from '../components/PinLayout';
import { PinPad } from '../components/PinPad';
import { Vault } from '../services/vault';
import { useSession } from '../state/SessionContext';
import { colors, space } from '../theme';
import { formatCountdown } from '../utils/format';

const FREE_ATTEMPTS = 5;

export function LockScreen() {
  const { state, refresh } = useSession();
  const min = state?.minPinLength ?? 6;
  const max = state?.maxPinLength ?? 16;
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [shake, setShake] = useState(0);
  const [lockoutUntil, setLockoutUntil] = useState(state?.lockoutUntil ?? 0);
  const [now, setNow] = useState(Date.now());

  const lockedOut = lockoutUntil > now;

  useEffect(() => {
    if (!lockedOut) {
      return;
    }
    // The native side times the lockout on the boot clock; re-read it so a clock change
    // can't leave a wrong countdown on screen.
    const t = setInterval(() => {
      Vault.getSessionState()
        .then(s => setLockoutUntil(s.lockoutUntil))
        .catch(() => {})
        .finally(() => setNow(Date.now()));
    }, 1000);
    return () => clearInterval(t);
  }, [lockedOut]);

  const submit = async () => {
    if (busy || lockedOut) {
      return;
    }
    setBusy(true);
    try {
      const r = await Vault.unlockWithPin(pin);
      if (r.ok) {
        await refresh();
        return;
      }
      setPin('');
      setShake(s => s + 1);
      setNow(Date.now());
      setLockoutUntil(r.lockoutUntil);
      const left = FREE_ATTEMPTS - r.failedAttempts;
      setError(left > 0 ? `Wrong PIN. ${left} ${left === 1 ? 'try' : 'tries'} before a timeout.` : 'Wrong PIN.');
    } catch {
      setError('Could not unlock. Try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <PinLayout
      icon="lock"
      title="Enclavia is locked"
      subtitle="Enter your PIN"
      error={lockedOut ? `Too many attempts. Try again in ${formatCountdown(lockoutUntil, now)}.` : error}
      footer={
        <Pressable
          onPress={() =>
            Alert.alert(
              'Forgot your PIN?',
              'Your PIN can’t be recovered or bypassed: it is what keeps your files encrypted.\n\nTo start over, clear this app’s storage in Android Settings › Apps › Enclavia › Storage. This permanently deletes everything hidden in the vault.',
            )
          }
          hitSlop={10}>
          <Text style={styles.link}>Forgot PIN?</Text>
        </Pressable>
      }>
      <PinPad
        value={pin}
        onChange={v => {
          setPin(v);
          if (!lockedOut) {
            setError(null);
          }
        }}
        onSubmit={submit}
        minLength={min}
        maxLength={max}
        disabled={busy || lockedOut}
        errorKey={shake}
      />
    </PinLayout>
  );
}

const styles = StyleSheet.create({
  link: { color: colors.textDim, fontSize: 14, paddingVertical: space.sm },
});
