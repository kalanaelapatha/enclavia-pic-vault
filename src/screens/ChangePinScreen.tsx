import React, { useState } from 'react';
import { ToastAndroid } from 'react-native';
import { IconButton } from '../components/Header';
import { PinLayout } from '../components/PinLayout';
import { PinPad } from '../components/PinPad';
import type { ScreenProps } from '../navigation/types';
import { errorMessage, Vault } from '../services/vault';
import { useSession } from '../state/SessionContext';
import { validatePin } from '../utils/format';

type Step = 'current' | 'new' | 'confirm';

export function ChangePinScreen({ navigation }: ScreenProps<'ChangePin'>) {
  const { state } = useSession();
  const min = state?.minPinLength ?? 6;
  const max = state?.maxPinLength ?? 16;
  const [step, setStep] = useState<Step>('current');
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [shake, setShake] = useState(0);
  const [busy, setBusy] = useState(false);

  const fail = (msg: string) => {
    setError(msg);
    setShake(s => s + 1);
    setPin('');
  };

  const submit = async () => {
    if (step === 'current') {
      setCurrent(pin);
      setPin('');
      setError(null);
      setStep('new');
      return;
    }
    if (step === 'new') {
      const problem = validatePin(pin, min, max);
      if (problem) {
        return fail(problem);
      }
      if (pin === current) {
        return fail('Choose a different PIN');
      }
      setNext(pin);
      setPin('');
      setError(null);
      setStep('confirm');
      return;
    }
    if (pin !== next) {
      setStep('new');
      return fail('PINs don’t match. Try again.');
    }
    setBusy(true);
    try {
      const ok = await Vault.changePin(current, next);
      if (!ok) {
        setStep('current');
        setBusy(false);
        return fail('Current PIN is incorrect.');
      }
      ToastAndroid.show('PIN changed', ToastAndroid.SHORT);
      navigation.goBack();
    } catch (e) {
      setBusy(false);
      fail(errorMessage(e));
    }
  };

  const titles: Record<Step, string> = {
    current: 'Enter current PIN',
    new: 'Choose a new PIN',
    confirm: 'Confirm new PIN',
  };

  return (
    <PinLayout
      icon="key"
      title={titles[step]}
      subtitle={step === 'new' ? `${min}–${max} digits` : undefined}
      error={error}
      top={<IconButton icon="close" label="Cancel" onPress={() => navigation.goBack()} />}>
      <PinPad
        value={pin}
        onChange={v => {
          setPin(v);
          setError(null);
        }}
        onSubmit={submit}
        minLength={step === 'current' ? 1 : min}
        maxLength={max}
        disabled={busy}
        errorKey={shake}
      />
    </PinLayout>
  );
}
