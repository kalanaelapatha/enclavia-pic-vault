import React from 'react';
import Svg, { Circle, Path, Rect } from 'react-native-svg';
import { colors } from '../theme';

export type IconName =
  | 'back' | 'chevronRight' | 'chevronDown' | 'plus' | 'close' | 'check' | 'lock' | 'trash'
  | 'download' | 'folder' | 'move' | 'settings' | 'play' | 'image' | 'video' | 'fingerprint'
  | 'shield' | 'backspace' | 'more' | 'edit' | 'offline' | 'timer' | 'key' | 'selectAll' | 'eyeOff';

type Props = { name: IconName; size?: number; color?: string; strokeWidth?: number };

export function Icon({ name, size = 22, color = colors.text, strokeWidth = 1.9 }: Props) {
  const stroke = { stroke: color, strokeWidth, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, fill: 'none' };
  const fill = { fill: color };
  const body = (() => {
    switch (name) {
      case 'back': return <Path d="M15 18l-6-6 6-6" {...stroke} />;
      case 'chevronRight': return <Path d="M9 18l6-6-6-6" {...stroke} />;
      case 'chevronDown': return <Path d="M6 9l6 6 6-6" {...stroke} />;
      case 'plus': return <Path d="M12 5v14M5 12h14" {...stroke} />;
      case 'close': return <Path d="M18 6L6 18M6 6l12 12" {...stroke} />;
      case 'check': return <Path d="M20 6L9 17l-5-5" {...stroke} />;
      case 'lock':
        return (<>
          <Rect x={4} y={11} width={16} height={10} rx={2.5} {...stroke} />
          <Path d="M8 11V7.5a4 4 0 0 1 8 0V11" {...stroke} />
        </>);
      case 'trash': return <Path d="M4 7h16M9 7V4.5h6V7M18 7l-.9 12.1a2 2 0 0 1-2 1.9H8.9a2 2 0 0 1-2-1.9L6 7M10 11v6M14 11v6" {...stroke} />;
      case 'download': return <Path d="M12 4v11M7.5 10.5L12 15l4.5-4.5M5 20h14" {...stroke} />;
      case 'folder': return <Path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2.2h7.5A2.5 2.5 0 0 1 21 9.7v7.8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z" {...stroke} />;
      case 'move': return <Path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2.2h7.5A2.5 2.5 0 0 1 21 9.7v7.8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5zM9 13.5h6M12.5 11l2.5 2.5-2.5 2.5" {...stroke} />;
      case 'settings':
        return (<>
          <Path d="M4 7h9M17 7h3M4 12h3M11 12h9M4 17h11M19 17h1" {...stroke} />
          <Circle cx={15} cy={7} r={2} {...stroke} />
          <Circle cx={9} cy={12} r={2} {...stroke} />
          <Circle cx={17} cy={17} r={2} {...stroke} />
        </>);
      case 'play': return <Path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.6-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z" {...fill} />;
      case 'image':
        return (<>
          <Rect x={3} y={3} width={18} height={18} rx={3} {...stroke} />
          <Circle cx={8.5} cy={8.5} r={1.6} {...stroke} />
          <Path d="M21 15.5l-5-5L5 21" {...stroke} />
        </>);
      case 'video':
        return (<>
          <Rect x={2.5} y={6} width={13.5} height={12} rx={2.5} {...stroke} />
          <Path d="M16 10.5l5.5-3.5v10L16 13.5" {...stroke} />
        </>);
      case 'fingerprint': return <Path d="M12 11.5v3.5M8.2 13.5v-1.8a3.8 3.8 0 0 1 7.6 0v2.6c0 1.8-.4 3.4-1.1 4.8M5 11.7a7 7 0 0 1 14 0v1.6M5.4 16.5c.5 1.1.7 2.1.7 3M9.4 20.4c.5-1.3.8-2.8.8-4.4M7 5.5A9 9 0 0 1 17 5.5" {...stroke} />;
      case 'shield': return <Path d="M12 3l7.5 3v5.6c0 4.7-3.2 8.3-7.5 9.4-4.3-1.1-7.5-4.7-7.5-9.4V6z" {...stroke} />;
      case 'backspace': return <Path d="M20 5H9.2a1.5 1.5 0 0 0-1.1.5L3 12l5.1 6.5a1.5 1.5 0 0 0 1.1.5H20a1.5 1.5 0 0 0 1.5-1.5v-11A1.5 1.5 0 0 0 20 5zM17 9.5l-5 5M12 9.5l5 5" {...stroke} />;
      case 'more':
        return (<>
          <Circle cx={5} cy={12} r={1.7} {...fill} />
          <Circle cx={12} cy={12} r={1.7} {...fill} />
          <Circle cx={19} cy={12} r={1.7} {...fill} />
        </>);
      case 'edit': return <Path d="M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17zM14 8l3 3" {...stroke} />;
      case 'offline': return <Path d="M3 3l18 18M8.6 16.4a4.8 4.8 0 0 1 6.8 0M5.2 12.9a9.6 9.6 0 0 1 4.3-2.4M18.8 12.9a9.6 9.6 0 0 0-2-1.5M2 9.3a14.4 14.4 0 0 1 4-2.7M22 9.3A14.4 14.4 0 0 0 11 5.6M12 20h.01" {...stroke} />;
      case 'timer':
        return (<>
          <Circle cx={12} cy={13} r={8} {...stroke} />
          <Path d="M12 9v4l2.5 2M9.5 2.5h5" {...stroke} />
        </>);
      case 'key':
        return (<>
          <Circle cx={7.5} cy={15.5} r={4} {...stroke} />
          <Path d="M10.4 12.6L20 3M16.5 6.5l3 3M14 9l2 2" {...stroke} />
        </>);
      case 'selectAll':
        return (<>
          <Rect x={3.5} y={3.5} width={17} height={17} rx={3.5} {...stroke} />
          <Path d="M8 12.2l2.8 2.8L16 9.5" {...stroke} />
        </>);
      case 'eyeOff': return <Path d="M3 3l18 18M10.6 5.1A9.8 9.8 0 0 1 12 5c5 0 8.5 4.3 9.5 7-.4 1-1.2 2.3-2.4 3.6M6.4 6.5C4.4 7.8 3 9.8 2.5 12c1 2.7 4.5 7 9.5 7 1.7 0 3.2-.5 4.5-1.2M9.9 9.9a3 3 0 0 0 4.2 4.2" {...stroke} />;
    }
  })();
  return <Svg width={size} height={size} viewBox="0 0 24 24">{body}</Svg>;
}
