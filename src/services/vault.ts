import { DeviceEventEmitter } from 'react-native';
import NativeVault from '../native/NativeVault';

export type {
  SessionState,
  UnlockResult,
  GalleryAlbum,
  GalleryItem,
  VaultAlbum,
  VaultItem,
  ImportResult,
  ExportResult,
  VaultStats,
} from '../native/NativeVault';

export type Progress = { done: number; total: number };

export const Vault = NativeVault;

export const VaultEvents = {
  onLocked(listener: () => void) {
    return DeviceEventEmitter.addListener('EnclaviaLocked', listener);
  },
  onImportProgress(listener: (p: Progress) => void) {
    return DeviceEventEmitter.addListener('EnclaviaImportProgress', listener);
  },
  onExportProgress(listener: (p: Progress) => void) {
    return DeviceEventEmitter.addListener('EnclaviaExportProgress', listener);
  },
};

export function errorMessage(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e) {
    return String((e as { message: unknown }).message);
  }
  return 'Something went wrong';
}

/** Hands the item list to the viewer without pushing large arrays through navigation params. */
let viewerItems: import('../native/NativeVault').VaultItem[] = [];
export const viewerStore = {
  set(items: import('../native/NativeVault').VaultItem[]) {
    viewerItems = items;
  },
  get() {
    return viewerItems;
  },
};
