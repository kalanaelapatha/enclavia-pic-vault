import { codegenNativeComponent } from 'react-native';
import type { CodegenTypes as CT, HostComponent, ViewProps } from 'react-native';

type TapEvent = Readonly<{ x: CT.Double; y: CT.Double }>;
type ZoomEvent = Readonly<{ zoomed: boolean }>;

export interface NativeProps extends ViewProps {
  /** Vault item id (decrypted natively, never leaves native memory). */
  itemId?: CT.WithDefault<string, ''>;
  /** MediaStore URI for browsing the device gallery. */
  galleryUri?: CT.WithDefault<string, ''>;
  galleryIsVideo?: CT.WithDefault<boolean, false>;
  variant?: CT.WithDefault<'thumb' | 'full' | 'frame' | 'poster', 'thumb'>;
  /** For variant="frame" (strip) or "poster" (large): position in the vault video to show. */
  frameTimeMs?: CT.WithDefault<CT.Double, 0>;
  /** Change to force a reload after the item was edited in place. */
  version?: CT.WithDefault<CT.Double, 0>;
  resizeMode?: CT.WithDefault<'cover' | 'contain', 'cover'>;
  zoomable?: CT.WithDefault<boolean, false>;
  onSingleTap?: CT.DirectEventHandler<TapEvent>;
  onZoomChange?: CT.DirectEventHandler<ZoomEvent>;
}

export default codegenNativeComponent<NativeProps>(
  'VaultImageView',
) as HostComponent<NativeProps>;
