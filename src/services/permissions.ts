import { PermissionsAndroid, Platform } from 'react-native';
import type { Permission } from 'react-native';

export type MediaAccess = 'full' | 'partial' | 'denied' | 'blocked';

const P = PermissionsAndroid.PERMISSIONS;
const VISUAL_USER_SELECTED =
  'android.permission.READ_MEDIA_VISUAL_USER_SELECTED' as Permission;

function mediaPermissions(): Permission[] {
  const api = Number(Platform.Version);
  if (api >= 34) {
    return [P.READ_MEDIA_IMAGES, P.READ_MEDIA_VIDEO, VISUAL_USER_SELECTED];
  }
  if (api >= 33) {
    return [P.READ_MEDIA_IMAGES, P.READ_MEDIA_VIDEO];
  }
  if (api >= 30) {
    return [P.READ_EXTERNAL_STORAGE];
  }
  // Android 8-10 also need write access to delete originals and export copies.
  return [P.READ_EXTERNAL_STORAGE, P.WRITE_EXTERNAL_STORAGE];
}

async function current(): Promise<MediaAccess> {
  const api = Number(Platform.Version);
  if (api >= 33) {
    const [img, vid] = await Promise.all([
      PermissionsAndroid.check(P.READ_MEDIA_IMAGES),
      PermissionsAndroid.check(P.READ_MEDIA_VIDEO),
    ]);
    if (img && vid) {
      return 'full';
    }
    if (api >= 34 && (await PermissionsAndroid.check(VISUAL_USER_SELECTED))) {
      return 'partial';
    }
    return 'denied';
  }
  const checks = await Promise.all(mediaPermissions().map(p => PermissionsAndroid.check(p)));
  return checks.every(Boolean) ? 'full' : 'denied';
}

/** Requests gallery access. Safe to call repeatedly. */
export async function requestMediaAccess(): Promise<MediaAccess> {
  const now = await current();
  if (now === 'full') {
    return now;
  }
  const result = await PermissionsAndroid.requestMultiple(mediaPermissions());
  const after = await current();
  if (after !== 'denied') {
    return after;
  }
  const blocked = Object.values(result).some(r => r === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN);
  return blocked ? 'blocked' : 'denied';
}

/** Android 8-9 need WRITE_EXTERNAL_STORAGE to save exported copies. */
export async function ensureExportPermission(): Promise<boolean> {
  if (Number(Platform.Version) >= 29) {
    return true;
  }
  const r = await PermissionsAndroid.request(P.WRITE_EXTERNAL_STORAGE);
  return r === PermissionsAndroid.RESULTS.GRANTED;
}
