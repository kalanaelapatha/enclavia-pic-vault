import React, { memo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import VaultImageView from '../native/VaultImageViewNativeComponent';
import { colors } from '../theme';
import { formatDuration } from '../utils/format';
import { Icon } from './Icon';

type Props = {
  size: number;
  itemId?: string;
  galleryUri?: string;
  isVideo: boolean;
  durationMs: number;
  selected: boolean;
  selecting: boolean;
  onPress: () => void;
  onLongPress?: () => void;
};

function MediaTileImpl({ size, itemId, galleryUri, isVideo, durationMs, selected, selecting, onPress, onLongPress }: Props) {
  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      delayLongPress={280}
      accessibilityRole="imagebutton"
      accessibilityState={{ selected }}
      style={[styles.tile, { width: size, height: size }]}>
      <VaultImageView
        style={StyleSheet.absoluteFill}
        itemId={itemId ?? ''}
        galleryUri={galleryUri ?? ''}
        galleryIsVideo={isVideo}
        variant="thumb"
        resizeMode="cover"
      />
      {isVideo ? (
        <View style={styles.videoBadge}>
          <Icon name="play" size={11} color="#fff" />
          <Text style={styles.videoText}>{formatDuration(durationMs)}</Text>
        </View>
      ) : null}
      {selected ? <View style={styles.selectedOverlay} /> : null}
      {selecting ? (
        <View style={[styles.check, selected && styles.checkOn]}>
          {selected ? <Icon name="check" size={14} color={colors.onAccent} strokeWidth={3} /> : null}
        </View>
      ) : null}
    </Pressable>
  );
}

export const MediaTile = memo(MediaTileImpl);

const styles = StyleSheet.create({
  tile: { backgroundColor: colors.surfaceAlt, overflow: 'hidden' },
  videoBadge: {
    position: 'absolute',
    left: 5,
    bottom: 5,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
    backgroundColor: 'rgba(0,0,0,0.55)',
    borderRadius: 6,
    paddingHorizontal: 5,
    paddingVertical: 2,
  },
  videoText: { color: '#fff', fontSize: 11, fontWeight: '600' },
  selectedOverlay: { ...StyleSheet.absoluteFill, backgroundColor: colors.selection, borderWidth: 2, borderColor: colors.accent },
  check: {
    position: 'absolute',
    top: 6,
    right: 6,
    width: 22,
    height: 22,
    borderRadius: 11,
    borderWidth: 2,
    borderColor: '#fff',
    backgroundColor: 'rgba(0,0,0,0.25)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkOn: { backgroundColor: colors.accent, borderColor: colors.accent },
});
