import React, { useState } from 'react';
import { Alert, ToastAndroid } from 'react-native';
import { ProgressModal } from '../components/ProgressModal';
import { Sheet } from '../components/Sheet';
import { ensureExportPermission } from '../services/permissions';
import { errorMessage, Vault, VaultEvents } from '../services/vault';
import { plural } from '../utils/format';
import { useAlbums } from './hooks';
import { useSession } from './SessionContext';

type Done = (kind: 'move' | 'delete' | 'export', ids: string[]) => void;

/** Save-to-gallery, move and delete, shared by the album grid and the viewer. */
export function useItemActions(currentAlbumId: string | null, onDone: Done) {
  const { bump } = useSession();
  const { data: albums } = useAlbums();
  const [moveIds, setMoveIds] = useState<string[] | null>(null);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);

  const exportItems = (ids: string[]) => {
    Alert.alert(
      `Save ${plural(ids.length, 'item')} to gallery?`,
      'A normal, unencrypted copy is saved to Pictures/Enclavia or Movies/Enclavia, where other apps can see it. The originals stay hidden in the vault.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Save copy',
          onPress: async () => {
            if (!(await ensureExportPermission())) {
              Alert.alert('Permission needed', 'Allow storage access to save copies to your gallery.');
              return;
            }
            setProgress({ done: 0, total: ids.length });
            const sub = VaultEvents.onExportProgress(p => setProgress(p));
            try {
              const r = await Vault.exportItems(ids);
              ToastAndroid.show(
                r.failed > 0 ? `Saved ${r.exported}, ${r.failed} failed` : `Saved ${plural(r.exported, 'item')} to gallery`,
                ToastAndroid.LONG,
              );
              onDone('export', ids);
            } catch (e) {
              Alert.alert('Export failed', errorMessage(e));
            } finally {
              sub.remove();
              setProgress(null);
            }
          },
        },
      ],
    );
  };

  const deleteItems = (ids: string[]) => {
    Alert.alert(
      `Delete ${plural(ids.length, 'item')}?`,
      'They will be permanently erased from the vault. This can’t be undone.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: async () => {
            try {
              await Vault.deleteItems(ids);
              bump();
              onDone('delete', ids);
            } catch (e) {
              Alert.alert('Delete failed', errorMessage(e));
            }
          },
        },
      ],
    );
  };

  const move = async (ids: string[], albumId: string | null, label: string) => {
    try {
      await Vault.moveItems(ids, albumId);
      bump();
      ToastAndroid.show(`Moved to ${label}`, ToastAndroid.SHORT);
      onDone('move', ids);
    } catch (e) {
      Alert.alert('Move failed', errorMessage(e));
    }
  };

  const ui = (
    <>
      <Sheet
        visible={!!moveIds}
        title="Move to album"
        onClose={() => setMoveIds(null)}
        options={
          moveIds
            ? [
                {
                  key: '__none__',
                  label: 'No album',
                  hint: 'Only in “All items”',
                  icon: 'image' as const,
                  selected: currentAlbumId === null,
                  onPress: () => move(moveIds, null, 'All items'),
                },
                ...albums.map(a => ({
                  key: a.id,
                  label: a.name,
                  hint: plural(a.itemCount, 'item'),
                  icon: 'folder' as const,
                  selected: a.id === currentAlbumId,
                  onPress: () => move(moveIds, a.id, a.name),
                })),
              ]
            : []
        }
      />
      <ProgressModal visible={!!progress} title="Saving to gallery" done={progress?.done ?? 0} total={progress?.total ?? 0} />
    </>
  );

  return { exportItems, deleteItems, moveItems: (ids: string[]) => setMoveIds(ids), ui };
}
