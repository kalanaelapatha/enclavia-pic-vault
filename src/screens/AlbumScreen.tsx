import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { BackHandler, FlatList, StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { EmptyState } from '../components/EmptyState';
import { Fab } from '../components/Fab';
import { Header } from '../components/Header';
import { MediaTile } from '../components/MediaTile';
import { SelectionBar } from '../components/SelectionBar';
import type { ScreenProps } from '../navigation/types';
import { VaultItem, viewerStore } from '../services/vault';
import { useItems } from '../state/hooks';
import { useItemActions } from '../state/useItemActions';
import { colors } from '../theme';
import { plural } from '../utils/format';

const COLUMNS = 3;
const GAP = 2;

export function AlbumScreen({ navigation, route }: ScreenProps<'Album'>) {
  const { albumId, title } = route.params;
  const { data: items, loading } = useItems(albumId);
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const size = (width - GAP * (COLUMNS - 1)) / COLUMNS;

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const selecting = selected.size > 0;
  const clear = useCallback(() => setSelected(new Set()), []);

  const actions = useItemActions(albumId, clear);

  // Hardware back leaves selection mode first.
  useEffect(() => {
    if (!selecting) {
      return;
    }
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      clear();
      return true;
    });
    return () => sub.remove();
  }, [selecting, clear]);

  // Drop selections that no longer exist (after delete/move).
  useEffect(() => {
    setSelected(prev => {
      if (prev.size === 0) {
        return prev;
      }
      const ids = new Set(items.map(i => i.id));
      const next = new Set([...prev].filter(id => ids.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [items]);

  const toggle = useCallback((id: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  const open = useCallback(
    (index: number) => {
      viewerStore.set(items);
      navigation.navigate('Viewer', { index, albumId });
    },
    [items, navigation, albumId],
  );

  const counts = useMemo(() => {
    const videos = items.filter(i => i.type === 'video').length;
    return `${plural(items.length - videos, 'photo')} · ${plural(videos, 'video')}`;
  }, [items]);

  const ids = [...selected];

  const renderItem = useCallback(
    ({ item, index }: { item: VaultItem; index: number }) => (
      <View style={index % COLUMNS === COLUMNS - 1 ? styles.cellLast : styles.cell}>
        <MediaTile
          size={size}
          itemId={item.id}
          isVideo={item.type === 'video'}
          durationMs={item.durationMs}
          selected={selected.has(item.id)}
          selecting={selecting}
          onPress={() => (selecting ? toggle(item.id) : open(index))}
          onLongPress={() => toggle(item.id)}
        />
      </View>
    ),
    [size, selected, selecting, toggle, open],
  );

  return (
    <View style={styles.flex}>
      {selecting ? (
        <Header
          title={`${selected.size} selected`}
          onBack={clear}
          backIcon="close"
          actions={[
            {
              icon: 'selectAll',
              label: 'Select all',
              onPress: () => setSelected(selected.size === items.length ? new Set() : new Set(items.map(i => i.id))),
            },
          ]}
        />
      ) : (
        <Header title={title} subtitle={items.length ? counts : undefined} onBack={() => navigation.goBack()} />
      )}

      {!loading && items.length === 0 ? (
        <EmptyState
          icon="image"
          title="Nothing here yet"
          body="Hide photos and videos from your gallery. They’re encrypted and only visible inside Enclavia."
          actionLabel="Hide photos"
          onAction={() => navigation.navigate('GalleryPicker', { albumId })}
        />
      ) : (
        <FlatList
          data={items}
          keyExtractor={i => i.id}
          numColumns={COLUMNS}
          renderItem={renderItem}
          extraData={selected}
          getItemLayout={(_, index) => ({ length: size + GAP, offset: (size + GAP) * Math.floor(index / COLUMNS), index })}
          initialNumToRender={30}
          maxToRenderPerBatch={30}
          windowSize={9}
          removeClippedSubviews
          contentContainerStyle={{ paddingBottom: insets.bottom + (selecting ? 16 : 100) }}
        />
      )}

      {selecting ? (
        <SelectionBar
          actions={[
            { icon: 'download', label: 'Save to gallery', onPress: () => actions.exportItems(ids) },
            { icon: 'move', label: 'Move', onPress: () => actions.moveItems(ids) },
            { icon: 'trash', label: 'Delete', danger: true, onPress: () => actions.deleteItems(ids) },
          ]}
        />
      ) : items.length > 0 ? (
        <Fab label="Hide more" onPress={() => navigation.navigate('GalleryPicker', { albumId })} />
      ) : null}
      {actions.ui}
    </View>
  );
}

const styles = StyleSheet.create({
  cell: { marginRight: GAP, marginBottom: GAP },
  cellLast: { marginBottom: GAP },
  flex: { flex: 1, backgroundColor: colors.bg },
});
