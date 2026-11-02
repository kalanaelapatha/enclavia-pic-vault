import { TurboModuleRegistry } from 'react-native';
import type { TurboModule } from 'react-native';

export type SessionState = {
  setup: boolean;
  unlocked: boolean;
  failedAttempts: number;
  lockoutUntil: number;
  autoLockMs: number;
  minPinLength: number;
  maxPinLength: number;
};

export type UnlockResult = {
  ok: boolean;
  failedAttempts: number;
  lockoutUntil: number;
};

export type GalleryAlbum = {
  id: string;
  name: string;
  count: number;
  coverUri: string;
};

export type GalleryItem = {
  uri: string;
  type: string;
  mime: string;
  name: string;
  size: number;
  width: number;
  height: number;
  durationMs: number;
  takenAt: number;
  bucketId: string;
};

export type VaultAlbum = {
  id: string;
  name: string;
  createdAt: number;
  itemCount: number;
  coverItemId: string | null;
};

export type VaultItem = {
  id: string;
  albumId: string | null;
  type: string;
  mime: string;
  name: string;
  size: number;
  width: number;
  height: number;
  durationMs: number;
  takenAt: number;
  importedAt: number;
  hasThumb: boolean;
};

export type ImportFailure = {
  uri: string;
  error: string;
};

export type ImportResult = {
  imported: Array<VaultItem>;
  importedUris: Array<string>;
  failed: Array<ImportFailure>;
  cancelled: boolean;
};

export type ExportResult = {
  exported: number;
  failed: number;
};

export type VaultStats = {
  itemCount: number;
  imageCount: number;
  videoCount: number;
  totalBytes: number;
  freeBytes: number;
  latestItemId: string | null;
};

export type MediaInfo = {
  width: number;
  height: number;
  durationMs: number;
};

export interface Spec extends TurboModule {
  // Session & keys
  getSessionState(): Promise<SessionState>;
  setupPin(pin: string): Promise<void>;
  unlockWithPin(pin: string): Promise<UnlockResult>;
  changePin(oldPin: string, newPin: string): Promise<boolean>;
  lock(): void;
  setAutoLockMs(ms: number): void;

  // Device gallery (read-only browsing)
  getGalleryAlbums(): Promise<Array<GalleryAlbum>>;
  getGalleryMedia(
    bucketId: string,
    offset: number,
    limit: number,
  ): Promise<Array<GalleryItem>>;
  deleteFromGallery(uris: ReadonlyArray<string>): Promise<boolean>;

  // Vault
  importMedia(
    uris: ReadonlyArray<string>,
    albumId: string | null,
  ): Promise<ImportResult>;
  cancelImport(): void;
  exportItems(ids: ReadonlyArray<string>): Promise<ExportResult>;
  listAlbums(): Promise<Array<VaultAlbum>>;
  createAlbum(name: string): Promise<VaultAlbum>;
  renameAlbum(id: string, name: string): Promise<void>;
  deleteAlbum(id: string, deleteItems: boolean): Promise<void>;
  listItems(albumId: string | null): Promise<Array<VaultItem>>;
  moveItems(ids: ReadonlyArray<string>, albumId: string | null): Promise<void>;
  deleteItems(ids: ReadonlyArray<string>): Promise<void>;
  getStats(): Promise<VaultStats>;

  // Editing (results are re-encrypted; replace=false keeps the original)
  getMediaInfo(id: string): Promise<MediaInfo>;
  cropImage(
    id: string,
    rotation: number,
    x: number,
    y: number,
    width: number,
    height: number,
    replace: boolean,
  ): Promise<VaultItem>;
  trimVideo(
    id: string,
    startMs: number,
    endMs: number,
    replace: boolean,
  ): Promise<VaultItem>;
  /** Rotates/crops the video frame; re-encodes on the device. Progress: EnclaviaEditProgress. */
  cropVideo(
    id: string,
    rotation: number,
    x: number,
    y: number,
    width: number,
    height: number,
    replace: boolean,
  ): Promise<VaultItem>;
  cancelEdit(): void;
  wipeVault(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('EnclaviaVault');
