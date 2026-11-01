import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { AppState } from 'react-native';
import { SessionState, Vault, VaultEvents } from '../services/vault';

export type SessionStatus = 'loading' | 'setup' | 'locked' | 'unlocked';

type SessionValue = {
  status: SessionStatus;
  state: SessionState | null;
  refresh: () => Promise<SessionState | null>;
  /** Bumped after every vault mutation so screens know to reload. */
  revision: number;
  bump: () => void;
};

const SessionContext = createContext<SessionValue | null>(null);

function toStatus(s: SessionState): SessionStatus {
  if (!s.setup) {
    return 'setup';
  }
  return s.unlocked ? 'unlocked' : 'locked';
}

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<SessionState | null>(null);
  const [status, setStatus] = useState<SessionStatus>('loading');
  const [revision, setRevision] = useState(0);

  const refresh = useCallback(async () => {
    try {
      const s = await Vault.getSessionState();
      setState(s);
      setStatus(toStatus(s));
      return s;
    } catch {
      setStatus('locked');
      return null;
    }
  }, []);

  useEffect(() => {
    refresh();
    // Native side locks on background/timeout and tells us; drop to the lock screen immediately.
    const lockSub = VaultEvents.onLocked(() => {
      setStatus(prev => (prev === 'unlocked' ? 'locked' : prev));
      refresh();
    });
    // Belt and braces: re-check whenever the app returns to the foreground.
    const appSub = AppState.addEventListener('change', next => {
      if (next === 'active') {
        refresh();
      }
    });
    return () => {
      lockSub.remove();
      appSub.remove();
    };
  }, [refresh]);

  const bump = useCallback(() => setRevision(r => r + 1), []);

  const value = useMemo(() => ({ status, state, refresh, revision, bump }), [status, state, refresh, revision, bump]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const ctx = useContext(SessionContext);
  if (!ctx) {
    throw new Error('useSession must be used inside SessionProvider');
  }
  return ctx;
}
