import { codegenNativeComponent } from 'react-native';
import type { CodegenTypes as CT, HostComponent, ViewProps } from 'react-native';

type ErrorEvent = Readonly<{ message: string }>;

export interface NativeProps extends ViewProps {
  /** Vault item id. Decrypted on the fly while streaming; no plaintext is written to disk. */
  itemId?: CT.WithDefault<string, ''>;
  paused?: CT.WithDefault<boolean, false>;
  /** Play only this range (ms). -1 = from start / to end. */
  clipStartMs?: CT.WithDefault<CT.Double, -1>;
  clipEndMs?: CT.WithDefault<CT.Double, -1>;
  repeat?: CT.WithDefault<boolean, false>;
  onPlaybackError?: CT.DirectEventHandler<ErrorEvent>;
}

export default codegenNativeComponent<NativeProps>(
  'VaultVideoView',
) as HostComponent<NativeProps>;
