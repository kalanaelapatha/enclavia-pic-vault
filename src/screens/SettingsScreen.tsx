import React, { useState } from 'react';
import { Alert, Modal, Pressable, ScrollView, StyleSheet, Switch, Text, ToastAndroid, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Header, IconButton } from '../components/Header';
import { Icon, IconName } from '../components/Icon';
import { PinLayout } from '../components/PinLayout';
import { PinPad } from '../components/PinPad';
import { Sheet } from '../components/Sheet';
import type { ScreenProps } from '../navigation/types';
import { errorMessage, Vault } from '../services/vault';
import { useStats } from '../state/hooks';
import { useSession } from '../state/SessionContext';
import { colors, radius, space, type } from '../theme';
import { formatBytes, plural } from '../utils/format';

const AUTO_LOCK: { ms: number; label: string }[] = [
  { ms: 0, label: 'Immediately' },
  { ms: 30_000, label: 'After 30 seconds' },
  { ms: 60_000, label: 'After 1 minute' },
  { ms: 300_000, label: 'After 5 minutes' },
];

const PROTECTIONS: { icon: IconName; title: string; body: string }[] = [
  { icon: 'offline', title: 'No internet access', body: 'The app has no network permission, so Android blocks every connection.' },
  { icon: 'shield', title: 'AES-256-GCM encryption', body: 'Every file, thumbnail and name is encrypted. Keys are bound to your PIN and this phone’s secure hardware.' },
  { icon: 'eyeOff', title: 'Screen protection', body: 'Screenshots, screen recording and the recent-apps preview are blocked.' },
  { icon: 'lock', title: 'No backups', body: 'Vault data is excluded from cloud and device-to-device backups.' },
];

export function SettingsScreen({ navigation }: ScreenProps<'Settings'>) {
  const { state, refresh } = useSession();
  const { data: stats } = useStats();
  const insets = useSafeAreaInsets();
  const [autoLockOpen, setAutoLockOpen] = useState(false);
  const [wipeOpen, setWipeOpen] = useState(false);
  const [bioBusy, setBioBusy] = useState(false);

  const autoLockMs = state?.autoLockMs ?? 0;
  const autoLockLabel = AUTO_LOCK.find(a => a.ms === autoLockMs)?.label ?? 'Immediately';

  const toggleBiometric = async (enable: boolean) => {
    setBioBusy(true);
    try {
      if (enable) {
        const ok = await Vault.enableBiometric('Enable biometric unlock', 'Cancel');
        if (ok) {
          ToastAndroid.show('Biometric unlock enabled', ToastAndroid.SHORT);
        }
      } else {
        await Vault.disableBiometric();
      }
    } catch (e) {
      Alert.alert('Biometrics unavailable', errorMessage(e));
    } finally {
      await refresh();
      setBioBusy(false);
    }
  };

  const setAutoLock = async (ms: number) => {
    Vault.setAutoLockMs(ms);
    await refresh();
  };

  return (
    <View style={styles.flex}>
      <Header title="Settings" onBack={() => navigation.goBack()} />
      <ScrollView contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + space.xxl }]}>
        <Section title="Security">
          {state?.biometricAvailable ? (
            <Row
              icon="fingerprint"
              title="Biometric unlock"
              subtitle="Fingerprint or face, backed by a hardware key"
              right={
                <Switch
                  value={!!state?.biometricEnabled}
                  disabled={bioBusy}
                  onValueChange={toggleBiometric}
                  trackColor={{ true: colors.accent, false: colors.surfaceHigh }}
                  thumbColor="#fff"
                />
              }
            />
          ) : (
            <Row icon="fingerprint" title="Biometric unlock" subtitle="Set up a strong fingerprint or face unlock in Android settings first" />
          )}
          <Row icon="timer" title="Auto-lock" subtitle={autoLockLabel} onPress={() => setAutoLockOpen(true)} chevron />
          <Row icon="key" title="Change PIN" onPress={() => navigation.navigate('ChangePin')} chevron />
          <Row icon="lock" title="Lock now" onPress={() => Vault.lock()} />
        </Section>

        <Section title="Storage">
          <Row icon="image" title={stats ? plural(stats.imageCount, 'photo') : '—'} subtitle={stats ? plural(stats.videoCount, 'video') : undefined} />
          <Row
            icon="folder"
            title={stats ? `${formatBytes(stats.totalBytes)} in vault` : '—'}
            subtitle={stats ? `${formatBytes(stats.freeBytes)} free on this phone` : undefined}
          />
        </Section>

        <Section title="How you’re protected">
          {PROTECTIONS.map(p => (
            <Row key={p.title} icon={p.icon} title={p.title} subtitle={p.body} />
          ))}
        </Section>

        <Section title="Danger zone">
          <Row icon="trash" title="Erase vault" subtitle="Permanently delete every hidden file and reset the PIN" danger onPress={() => setWipeOpen(true)} />
        </Section>

        <Text style={[type.tiny, styles.version]}>Enclavia 1.0.0 · offline build</Text>
      </ScrollView>

      <Sheet
        visible={autoLockOpen}
        title="Lock the vault when you leave the app"
        onClose={() => setAutoLockOpen(false)}
        options={AUTO_LOCK.map(a => ({ key: String(a.ms), label: a.label, selected: a.ms === autoLockMs, onPress: () => setAutoLock(a.ms) }))}
      />
      <WipeModal visible={wipeOpen} onClose={() => setWipeOpen(false)} onWiped={refresh} />
    </View>
  );
}

function WipeModal({ visible, onClose, onWiped }: { visible: boolean; onClose: () => void; onWiped: () => void }) {
  const { state } = useSession();
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [shake, setShake] = useState(0);
  const [busy, setBusy] = useState(false);

  const close = () => {
    setPin('');
    setError(null);
    onClose();
  };

  const submit = async () => {
    setBusy(true);
    try {
      const r = await Vault.unlockWithPin(pin);
      if (!r.ok) {
        setPin('');
        setShake(s => s + 1);
        setError(r.lockoutUntil > Date.now() ? 'Too many attempts. Try again later.' : 'Wrong PIN');
        return;
      }
      Alert.alert('Erase everything?', 'All hidden photos and videos will be destroyed. This can’t be undone.', [
        { text: 'Cancel', style: 'cancel', onPress: close },
        {
          text: 'Erase',
          style: 'destructive',
          onPress: async () => {
            try {
              await Vault.wipeVault();
              close();
              onWiped();
            } catch (e) {
              Alert.alert('Erase failed', errorMessage(e));
            }
          },
        },
      ]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={close} statusBarTranslucent navigationBarTranslucent>
      <PinLayout
        icon="trash"
        title="Confirm with your PIN"
        subtitle="Required to erase the vault"
        error={error}
        top={<IconButton icon="close" label="Cancel" onPress={close} />}>
        <PinPad
          value={pin}
          onChange={v => {
            setPin(v);
            setError(null);
          }}
          onSubmit={submit}
          minLength={state?.minPinLength ?? 6}
          maxLength={state?.maxPinLength ?? 16}
          disabled={busy}
          errorKey={shake}
        />
      </PinLayout>
    </Modal>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={[type.caption, styles.sectionTitle]}>{title.toUpperCase()}</Text>
      <View style={styles.card}>{children}</View>
    </View>
  );
}

type RowProps = {
  icon: IconName;
  title: string;
  subtitle?: string;
  right?: React.ReactNode;
  onPress?: () => void;
  chevron?: boolean;
  danger?: boolean;
};

function Row({ icon, title, subtitle, right, onPress, chevron, danger }: RowProps) {
  const content = (
    <>
      <Icon name={icon} color={danger ? colors.danger : colors.accent} />
      <View style={styles.rowText}>
        <Text style={[type.body, danger && { color: colors.danger }]}>{title}</Text>
        {subtitle ? <Text style={[type.caption, styles.lh]}>{subtitle}</Text> : null}
      </View>
      {right}
      {chevron ? <Icon name="chevronRight" color={colors.textFaint} size={18} /> : null}
    </>
  );
  if (!onPress) {
    return <View style={styles.row}>{content}</View>;
  }
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.row, pressed && styles.pressed]}>
      {content}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: colors.bg },
  content: { paddingHorizontal: space.lg, gap: space.xl },
  section: { gap: space.sm },
  sectionTitle: { letterSpacing: 1, paddingHorizontal: space.xs },
  card: { backgroundColor: colors.surface, borderRadius: radius.lg, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.lg, paddingHorizontal: space.lg, paddingVertical: 14 },
  rowText: { flex: 1 },
  lh: { lineHeight: 18, marginTop: 2 },
  pressed: { backgroundColor: colors.surfaceAlt },
  version: { textAlign: 'center' },
});
