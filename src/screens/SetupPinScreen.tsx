import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../components/Button';
import { Icon, IconName } from '../components/Icon';
import { PinLayout } from '../components/PinLayout';
import { PinPad } from '../components/PinPad';
import { IconButton } from '../components/Header';
import { errorMessage, Vault } from '../services/vault';
import { useSession } from '../state/SessionContext';
import { colors, radius, space, type } from '../theme';
import { validatePin } from '../utils/format';

const FEATURES: { icon: IconName; title: string; body: string }[] = [
  { icon: 'offline', title: 'Fully offline', body: 'No internet permission. Nothing you hide can ever be uploaded.' },
  { icon: 'shield', title: 'AES-256 encrypted', body: 'Photos, videos and names are encrypted with a key protected by your PIN and the phone’s secure hardware.' },
  { icon: 'eyeOff', title: 'Private by design', body: 'Screenshots and app previews are blocked, and the vault locks when you leave.' },
];

export function SetupPinScreen() {
  const { state, refresh } = useSession();
  const insets = useSafeAreaInsets();
  const min = state?.minPinLength ?? 6;
  const max = state?.maxPinLength ?? 16;
  const [step, setStep] = useState<'intro' | 'create' | 'confirm'>('intro');
  const [first, setFirst] = useState('');
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [shake, setShake] = useState(0);
  const [busy, setBusy] = useState(false);

  if (step === 'intro') {
    return (
      <ScrollView
        style={styles.flex}
        contentContainerStyle={[styles.intro, { paddingTop: insets.top + space.xxl, paddingBottom: insets.bottom + space.xl }]}>
        <View style={styles.logo}>
          <Icon name="shield" size={40} color={colors.accent} />
        </View>
        <Text style={[type.title, styles.center, styles.brand]}>Enclavia</Text>
        <Text style={[type.caption, styles.center]}>A private, encrypted vault for your photos and videos.</Text>
        <View style={styles.features}>
          {FEATURES.map(f => (
            <View key={f.title} style={styles.feature}>
              <Icon name={f.icon} color={colors.accent} />
              <View style={styles.flex}>
                <Text style={type.heading}>{f.title}</Text>
                <Text style={[type.caption, styles.lh]}>{f.body}</Text>
              </View>
            </View>
          ))}
        </View>
        <Text style={[type.caption, styles.warning]}>
          Your PIN can’t be recovered. If you forget it, the hidden files can’t be decrypted by anyone, including us.
        </Text>
        <Button label="Create PIN" onPress={() => setStep('create')} />
      </ScrollView>
    );
  }

  const submit = async () => {
    if (step === 'create') {
      const problem = validatePin(pin, min, max);
      if (problem) {
        setError(problem);
        setShake(s => s + 1);
        return;
      }
      setFirst(pin);
      setPin('');
      setError(null);
      setStep('confirm');
      return;
    }
    if (pin !== first) {
      setError('PINs don’t match. Try again.');
      setShake(s => s + 1);
      setPin('');
      setFirst('');
      setStep('create');
      return;
    }
    setBusy(true);
    try {
      await Vault.setupPin(pin);
      await refresh();
    } catch (e) {
      setBusy(false);
      setError(errorMessage(e));
    }
  };

  return (
    <PinLayout
      icon="key"
      title={step === 'create' ? 'Create a PIN' : 'Confirm your PIN'}
      subtitle={step === 'create' ? `${min}–${max} digits. Avoid birthdays and simple patterns.` : 'Enter the same PIN again.'}
      error={error}
      top={
        <IconButton
          icon="back"
          label="Back"
          onPress={() => {
            setPin('');
            setError(null);
            setStep(step === 'confirm' ? 'create' : 'intro');
          }}
        />
      }>
      <PinPad
        value={pin}
        onChange={v => {
          setPin(v);
          setError(null);
        }}
        onSubmit={submit}
        minLength={min}
        maxLength={max}
        disabled={busy}
        errorKey={shake}
      />
    </PinLayout>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  intro: { paddingHorizontal: space.xl, gap: space.md, flexGrow: 1 },
  logo: {
    alignSelf: 'center',
    width: 84,
    height: 84,
    borderRadius: 26,
    backgroundColor: colors.accentDim,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: space.sm,
  },
  brand: { fontSize: 30 },
  center: { textAlign: 'center' },
  features: { backgroundColor: colors.surface, borderRadius: radius.xl, padding: space.lg, gap: space.lg, marginVertical: space.lg },
  feature: { flexDirection: 'row', gap: space.lg, alignItems: 'flex-start' },
  lh: { lineHeight: 19, marginTop: 2 },
  warning: { textAlign: 'center', lineHeight: 19, marginBottom: space.md, color: colors.warning },
});
