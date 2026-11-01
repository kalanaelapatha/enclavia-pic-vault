import React, { useState } from 'react';
import { Alert, FlatList, Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Fab } from '../components/Fab';
import { Header } from '../components/Header';
import { Icon } from '../components/Icon';
import { Sheet } from '../components/Sheet';
import { TextPrompt } from '../components/TextPrompt';
import VaultImageView from '../native/VaultImageViewNativeComponent';
import type { ScreenProps } from '../navigation/types';
import { errorMessage, Vault, VaultAlbum } from '../services/vault';
import { useAlbums, useStats } from '../state/hooks';
import { useSession } from '../state/SessionContext';
import { colors, radius, space, type } from '../theme';
import { formatBytes, plural } from '../utils/format';

type Tile = VaultAlbum | { id: '__new__' };

export function HomeScreen({ navigation }: ScreenProps<'Home'>) {
  const { bump, revision } = useSession();
  const { data: albums } = useAlbums();
  const { data: stats } = useStats();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const tileW = (width - space.lg * 2 - space.md) / 2;

  const [creating, setCreating] = useState(false);
  const [renaming, setRenaming] = useState<VaultAlbum | null>(null);
  const [menuFor, setMenuFor] = useState<VaultAlbum | null>(null);

  const createAlbum = async (name: string) => {
    setCreating(false);
    try {
      await Vault.createAlbum(name);
      bump();
    } catch (e) {
      Alert.alert('Could not create album', errorMessage(e));
    }
  };

  const renameAlbum = async (name: string) => {
    const album = renaming;
    setRenaming(null);
    if (!album) {
      return;
    }
    try {
      await Vault.renameAlbum(album.id, name);
      bump();
    } catch (e) {
      Alert.alert('Could not rename album', errorMessage(e));
    }
  };

  const deleteAlbum = (album: VaultAlbum) => {
    const run = async (deleteItems: boolean) => {
      try {
        await Vault.deleteAlbum(album.id, deleteItems);
        bump();
      } catch (e) {
        Alert.alert('Could not delete album', errorMessage(e));
      }
    };
    if (album.itemCount === 0) {
      run(false);
      return;
    }
    Alert.alert(
      `Delete “${album.name}”?`,
      `This album has ${plural(album.itemCount, 'item')}. Keep them in “All items”, or delete them permanently?`,
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Keep items', onPress: () => run(false) },
        {
          text: 'Delete all',
          style: 'destructive',
          onPress: () =>
            Alert.alert('Delete permanently?', 'These files will be erased from the vault. This can’t be undone.', [
              { text: 'Cancel', style: 'cancel' },
              { text: 'Delete', style: 'destructive', onPress: () => run(true) },
            ]),
        },
      ],
    );
  };

  const data: Tile[] = [...albums, { id: '__new__' }];
  const total = stats?.itemCount ?? 0;

  const header = (
    <View style={styles.headerBlock}>
      <View style={styles.statusRow}>
        <View style={styles.chip}>
          <Icon name="offline" size={14} color={colors.accent} />
          <Text style={styles.chipText}>Offline</Text>
        </View>
        <View style={styles.chip}>
          <Icon name="shield" size={14} color={colors.accent} />
          <Text style={styles.chipText}>AES-256</Text>
        </View>
      </View>

      <Pressable
        onPress={() => navigation.navigate('Album', { albumId: null, title: 'All items' })}
        style={({ pressed }) => [styles.allCard, pressed && styles.pressed]}>
        {stats?.latestItemId ? (
          <VaultImageView style={StyleSheet.absoluteFill} itemId={stats.latestItemId} version={revision} variant="thumb" resizeMode="cover" />
        ) : null}
        <View style={styles.allShade} />
        <View style={styles.allText}>
          <Text style={styles.allTitle}>All items</Text>
          <Text style={styles.allSub}>
            {stats
              ? `${plural(stats.imageCount, 'photo')} · ${plural(stats.videoCount, 'video')} · ${formatBytes(stats.totalBytes)}`
              : ' '}
          </Text>
        </View>
        <View style={styles.allArrow}>
          <Icon name="chevronRight" color="#fff" />
        </View>
      </Pressable>

      {stats && total === 0 ? (
        <Text style={[type.caption, styles.hint]}>
          Your vault is empty. Tap “Hide photos” to move pictures and videos from your gallery into Enclavia.
        </Text>
      ) : null}

      <Text style={[type.caption, styles.section]}>ALBUMS</Text>
    </View>
  );

  return (
    <View style={styles.flex}>
      <Header
        title="Enclavia"
        large
        actions={[
          { icon: 'settings', label: 'Settings', onPress: () => navigation.navigate('Settings') },
          { icon: 'lock', label: 'Lock now', onPress: () => Vault.lock() },
        ]}
      />
      <FlatList
        data={data}
        keyExtractor={a => a.id}
        numColumns={2}
        ListHeaderComponent={header}
        columnWrapperStyle={styles.row}
        contentContainerStyle={[styles.list, { paddingBottom: insets.bottom + 110 }]}
        renderItem={({ item }) =>
          item.id === '__new__' ? (
            <Pressable
              onPress={() => setCreating(true)}
              style={({ pressed }) => [styles.newTile, { width: tileW, height: tileW }, pressed && styles.pressed]}>
              <Icon name="plus" size={28} color={colors.textDim} />
              <Text style={type.caption}>New album</Text>
            </Pressable>
          ) : (
            <AlbumTile
              album={item as VaultAlbum}
              size={tileW}
              revision={revision}
              onPress={() => navigation.navigate('Album', { albumId: item.id, title: (item as VaultAlbum).name })}
              onLongPress={() => setMenuFor(item as VaultAlbum)}
            />
          )
        }
      />
      <Fab label="Hide photos" onPress={() => navigation.navigate('GalleryPicker', { albumId: null })} />

      <TextPrompt visible={creating} title="New album" placeholder="Album name" confirmLabel="Create" onCancel={() => setCreating(false)} onSubmit={createAlbum} />
      <TextPrompt
        visible={!!renaming}
        title="Rename album"
        initialValue={renaming?.name}
        onCancel={() => setRenaming(null)}
        onSubmit={renameAlbum}
      />
      <Sheet
        visible={!!menuFor}
        title={menuFor?.name}
        onClose={() => setMenuFor(null)}
        options={
          menuFor
            ? [
                { key: 'rename', label: 'Rename', icon: 'edit', onPress: () => setRenaming(menuFor) },
                { key: 'delete', label: 'Delete album', icon: 'trash', danger: true, onPress: () => deleteAlbum(menuFor) },
              ]
            : []
        }
      />
    </View>
  );
}

function AlbumTile({ album, size, revision, onPress, onLongPress }: { album: VaultAlbum; size: number; revision: number; onPress: () => void; onLongPress: () => void }) {
  return (
    <Pressable onPress={onPress} onLongPress={onLongPress} style={({ pressed }) => [{ width: size }, pressed && styles.pressed]}>
      <View style={[styles.cover, { width: size, height: size }]}>
        {album.coverItemId ? (
          <VaultImageView style={StyleSheet.absoluteFill} itemId={album.coverItemId} version={revision} variant="thumb" resizeMode="cover" />
        ) : (
          <Icon name="folder" size={34} color={colors.textFaint} />
        )}
      </View>
      <Text numberOfLines={1} style={[type.body, styles.albumName]}>
        {album.name}
      </Text>
      <Text style={type.caption}>{plural(album.itemCount, 'item')}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: colors.bg },
  list: { paddingHorizontal: space.lg },
  row: { justifyContent: 'space-between', marginBottom: space.lg },
  headerBlock: { gap: space.md, marginBottom: space.sm },
  statusRow: { flexDirection: 'row', gap: space.sm },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.accentDim,
    borderRadius: radius.pill,
    paddingHorizontal: 10,
    paddingVertical: 5,
  },
  chipText: { color: colors.accent, fontSize: 12, fontWeight: '600' },
  allCard: {
    height: 150,
    borderRadius: radius.xl,
    overflow: 'hidden',
    backgroundColor: colors.surfaceAlt,
    justifyContent: 'flex-end',
  },
  allShade: { ...StyleSheet.absoluteFill, backgroundColor: 'rgba(5,7,10,0.45)' },
  allText: { padding: space.lg },
  allTitle: { color: '#fff', fontSize: 22, fontWeight: '700' },
  allSub: { color: 'rgba(255,255,255,0.85)', fontSize: 13, marginTop: 2 },
  allArrow: { position: 'absolute', right: space.lg, bottom: space.lg + 6 },
  section: { marginTop: space.md, letterSpacing: 1 },
  cover: {
    borderRadius: radius.lg,
    overflow: 'hidden',
    backgroundColor: colors.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
  },
  albumName: { marginTop: space.sm, fontWeight: '600' },
  newTile: {
    borderRadius: radius.lg,
    borderWidth: 1.5,
    borderStyle: 'dashed',
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.sm,
  },
  pressed: { opacity: 0.75 },
  hint: { lineHeight: 19, paddingHorizontal: space.xs },
});
