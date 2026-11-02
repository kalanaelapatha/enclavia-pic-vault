import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FlatList, Pressable, StatusBar, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon } from '../components/Icon';
import { IconButton } from '../components/Header';
import VaultImageView from '../native/VaultImageViewNativeComponent';
import type { ScreenProps } from '../navigation/types';
import { VaultItem, viewerStore } from '../services/vault';
import { useItems } from '../state/hooks';
import { useItemActions } from '../state/useItemActions';
import { space } from '../theme';
import { formatBytes, formatDate, formatDuration } from '../utils/format';

export function ViewerScreen({ navigation, route }: ScreenProps<'Viewer'>) {
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [items, setItems] = useState<VaultItem[]>(() => viewerStore.get());
  const [index, setIndex] = useState(Math.min(route.params.index, Math.max(0, items.length - 1)));
  const [chrome, setChrome] = useState(true);
  const [zoomed, setZoomed] = useState(false);
  const listRef = useRef<FlatList<VaultItem>>(null);

  const current = items[index];
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const currentId = useRef(current?.id);
  currentId.current = current?.id;

  // Pick up edits (new copies, replaced files) while keeping the same photo on screen.
  const { data: fresh, loading: freshLoading } = useItems(route.params.albumId);
  useEffect(() => {
    if (freshLoading || fresh.length === 0) {
      return;
    }
    const signature = (list: VaultItem[]) => list.map(i => `${i.id}:${i.importedAt}`).join('|');
    if (signature(fresh) === signature(itemsRef.current)) {
      return; // nothing changed: don't disturb an in-progress swipe
    }
    setItems(fresh);
    const idx = fresh.findIndex(i => i.id === currentId.current);
    if (idx >= 0) {
      setIndex(idx);
      requestAnimationFrame(() => listRef.current?.scrollToIndex({ index: idx, animated: false }));
    }
  }, [fresh, freshLoading]);

  const actions = useItemActions(route.params.albumId, (kind, ids) => {
    if (kind === 'export') {
      return;
    }
    // Moved items leave this album (unless we're viewing "All items"); deleted items leave everywhere.
    if (kind === 'move' && route.params.albumId === null) {
      return;
    }
    const gone = new Set(ids);
    const next = items.filter(i => !gone.has(i.id));
    if (next.length === 0) {
      navigation.goBack();
      return;
    }
    setItems(next);
    setIndex(i => Math.min(i, next.length - 1));
  });

  const onViewable = useRef(({ viewableItems }: { viewableItems: Array<{ index?: number | null }> }) => {
    const first = viewableItems[0];
    if (first?.index != null) {
      setIndex(first.index);
      setZoomed(false);
    }
  }).current;

  const toggleChrome = useCallback(() => setChrome(c => !c), []);

  const renderItem = useCallback(
    ({ item }: { item: VaultItem }) => (
      <View style={{ width, height }}>
        {item.type === 'video' ? (
          <Pressable style={StyleSheet.absoluteFill} onPress={() => navigation.navigate('Player', { itemId: item.id, name: item.name })}>
            <VaultImageView style={StyleSheet.absoluteFill} itemId={item.id} version={item.importedAt} variant="thumb" resizeMode="contain" />
            <View style={styles.playWrap}>
              <View style={styles.play}>
                <Icon name="play" size={34} color="#fff" />
              </View>
            </View>
          </Pressable>
        ) : (
          <VaultImageView
            style={StyleSheet.absoluteFill}
            itemId={item.id}
            version={item.importedAt}
            variant="full"
            resizeMode="contain"
            zoomable
            onSingleTap={toggleChrome}
            onZoomChange={e => setZoomed(e.nativeEvent.zoomed)}
          />
        )}
      </View>
    ),
    [width, height, navigation, toggleChrome],
  );

  const meta = useMemo(() => {
    if (!current) {
      return '';
    }
    const parts = [formatBytes(current.size)];
    if (current.type === 'video') {
      parts.unshift(formatDuration(current.durationMs));
    } else if (current.width && current.height) {
      parts.unshift(`${current.width}×${current.height}`);
    }
    return parts.join(' · ');
  }, [current]);

  if (!current) {
    return null;
  }

  return (
    <View style={styles.flex}>
      <StatusBar hidden={!chrome} />
      <FlatList
        ref={listRef}
        data={items}
        keyExtractor={i => i.id}
        renderItem={renderItem}
        horizontal
        pagingEnabled
        scrollEnabled={!zoomed}
        showsHorizontalScrollIndicator={false}
        initialScrollIndex={index}
        getItemLayout={(_, i) => ({ length: width, offset: width * i, index: i })}
        onViewableItemsChanged={onViewable}
        viewabilityConfig={{ itemVisiblePercentThreshold: 60 }}
        windowSize={3}
        initialNumToRender={1}
        maxToRenderPerBatch={2}
        removeClippedSubviews
      />

      {chrome ? (
        <>
          <View style={[styles.top, { paddingTop: insets.top + space.xs }]}>
            <IconButton icon="back" label="Back" onPress={() => navigation.goBack()} />
            <View style={styles.titles}>
              <Text numberOfLines={1} style={styles.title}>
                {formatDate(current.takenAt)}
              </Text>
              <Text numberOfLines={1} style={styles.sub}>
                {current.name} · {meta}
              </Text>
            </View>
            <Text style={styles.counter}>
              {index + 1}/{items.length}
            </Text>
          </View>
          <View style={[styles.bottom, { paddingBottom: insets.bottom + space.sm }]}>
            {current.type === 'video' ? (
              <BarButton icon="scissors" label="Cut" onPress={() => navigation.navigate('Trim', { itemId: current.id, name: current.name })} />
            ) : null}
            <BarButton
              icon="crop"
              label="Crop"
              onPress={() =>
                navigation.navigate('Crop', { itemId: current.id, version: current.importedAt, isVideo: current.type === 'video' })
              }
            />
            <BarButton icon="download" label="Save" onPress={() => actions.exportItems([current.id])} />
            <BarButton icon="move" label="Move" onPress={() => actions.moveItems([current.id])} />
            <BarButton icon="trash" label="Delete" onPress={() => actions.deleteItems([current.id])} danger />
          </View>
        </>
      ) : null}
      {actions.ui}
    </View>
  );
}

function BarButton({ icon, label, onPress, danger }: { icon: 'download' | 'move' | 'trash' | 'crop' | 'scissors'; label: string; onPress: () => void; danger?: boolean }) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.barBtn, pressed && styles.pressed]} accessibilityRole="button">
      <Icon name={icon} color={danger ? '#FCA5A5' : '#fff'} />
      <Text style={[styles.barText, danger && styles.dangerText]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: '#000' },
  top: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: space.sm,
    paddingBottom: space.sm,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  titles: { flex: 1, paddingHorizontal: space.xs },
  title: { color: '#fff', fontSize: 16, fontWeight: '600' },
  sub: { color: 'rgba(255,255,255,0.7)', fontSize: 12 },
  counter: { color: 'rgba(255,255,255,0.8)', fontSize: 13, paddingHorizontal: space.md },
  bottom: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    flexDirection: 'row',
    paddingTop: space.sm,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  barBtn: { flex: 1, alignItems: 'center', gap: 4, paddingVertical: space.sm, borderRadius: 12 },
  barText: { color: '#fff', fontSize: 12 },
  dangerText: { color: '#FCA5A5' },
  pressed: { backgroundColor: 'rgba(255,255,255,0.08)' },
  playWrap: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center' },
  play: {
    width: 76,
    height: 76,
    borderRadius: 38,
    backgroundColor: 'rgba(0,0,0,0.55)',
    alignItems: 'center',
    justifyContent: 'center',
    paddingLeft: 4,
    borderWidth: 1.5,
    borderColor: 'rgba(255,255,255,0.6)',
  },
});
