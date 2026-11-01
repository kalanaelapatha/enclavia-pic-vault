import { useCallback, useEffect, useRef, useState } from 'react';
import { useFocusEffect } from '@react-navigation/native';
import { Vault, VaultAlbum, VaultItem, VaultStats } from '../services/vault';
import { useSession } from './SessionContext';

/** Loads data on focus and whenever the vault changes. */
function useVaultQuery<T>(load: () => Promise<T>, initial: T, key: string) {
  const { revision, status } = useSession();
  const [data, setData] = useState<T>(initial);
  const [loading, setLoading] = useState(true);
  const loadRef = useRef(load);
  loadRef.current = load;

  const reload = useCallback(async () => {
    if (status !== 'unlocked') {
      return;
    }
    try {
      setData(await loadRef.current());
    } catch {
      // Locked mid-request: the session switches to the lock screen on its own.
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => {
    reload();
  }, [reload, revision, key]);

  useFocusEffect(
    useCallback(() => {
      reload();
    }, [reload]),
  );

  return { data, loading, reload };
}

export function useAlbums() {
  return useVaultQuery<VaultAlbum[]>(() => Vault.listAlbums(), [], 'albums');
}

export function useItems(albumId: string | null) {
  return useVaultQuery<VaultItem[]>(() => Vault.listItems(albumId), [], `items:${albumId ?? '*'}`);
}

export function useStats() {
  return useVaultQuery<VaultStats | null>(() => Vault.getStats(), null, 'stats');
}
