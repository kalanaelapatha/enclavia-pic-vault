import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Linking,
  Pressable,
  StyleSheet,
  Text,
  ToastAndroid,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../components/Button';
import { EmptyState } from '../components/EmptyState';
import { Header } from '../components/Header';
import { Icon } from '../components/Icon';
import { MediaTile } from '../components/MediaTile';
import { ProgressModal } from '../components/ProgressModal';
import { Sheet } from '../components/Sheet';
import type { ScreenProps } from '../navigation/types';
import { MediaAccess, requestMediaAccess } from '../services/permissions';
import { errorMessage, GalleryAlbum, GalleryItem, ImportResult, Vault, VaultEvents } from '../services/vault';
import { useAlbums } from '../state/hooks';
import { useSession } from '../state/SessionContext';
import { colors, radius, space, type } from '../theme';
import { plural } from '../utils/format';

const COLUMNS = 4;
const GAP = 2;
const PAGE = 160;

export function GalleryPickerScreen({ navigation, route }: ScreenProps<'GalleryPicker'>) {
  const { bump } = useSession();
  const { data: vaultAlbums } = useAlbums();
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const size = (width - GAP * (COLUMNS - 1)) / COLUMNS;

  const [access, setAccess] = useState<MediaAccess | 'checking'>('checking');
  const [albums, setAlbums] = useState<GalleryAlbum[]>([]);
  const [bucket, setBucket] = useState<GalleryAlbum | null>(null);
  const [media, setMedia] = useState<GalleryItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [exhausted, setExhausted] = useState(false);
  const [selected, setSelected] = useState<Map<string, GalleryItem>>(new Map());
  const [target, setTarget] = useState<string | null>(route.params.albumId);
  const [pickBucket, setPickBucket] = useState(false);
  const [pickTarget, setPickTarget] = useState(false);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const loadingRef = useRef(false);

  const targetName = target ? vaultAlbums.find(a => a.id === target)?.name ?? 'Album' : 'No album';

  const ask = useCallback(async () => {
    setAccess('checking');
    const a = await requestMediaAccess();
    setAccess(a);
    if (a === 'full' || a === 'partial') {
      try {
        const list = await Vault.getGalleryAlbums();
        setAlbums(list);
        setBucket(list[0] ?? null);
      } catch (e) {
        Alert.alert('Could not read gallery', errorMessage(e));
      }
    }
  }, []);

  useEffect(() => {
    ask();
  }, [ask]);

  const loadMore = useCallback(
    async (reset = false) => {
      if (!bucket || loadingRef.current || (!reset && exhausted)) {
        return;
      }
      loadingRef.current = true;
      setLoading(true);
      try {
        const offset = reset ? 0 : media.length;
        const page = await Vault.getGalleryMedia(bucket.id, offset, PAGE);
        setMedia(prev => (reset ? page : [...prev, ...page]));
        setExhausted(page.length < PAGE);
      } catch (e) {
        Alert.alert('Could not read gallery', errorMessage(e));
      } finally {
        loadingRef.current = false;
        setLoading(false);
      }
    },
    [bucket, exhausted, media.length],
  );

  useEffect(() => {
    if (bucket) {
      setMedia([]);
      setExhausted(false);
      loadMore(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bucket]);

  const toggle = useCallback((item: GalleryItem) => {
    setSelected(prev => {
      const next = new Map(prev);
      if (next.has(item.uri)) {
        next.delete(item.uri);
      } else {
        next.set(item.uri, item);
      }
      return next;
    });
  }, []);

  const allLoadedSelected = media.length > 0 && media.every(m => selected.has(m.uri));
  const toggleAll = () =>
    setSelected(prev => {
      const next = new Map(prev);
      if (allLoadedSelected) {
        media.forEach(m => next.delete(m.uri));
      } else {
        media.forEach(m => next.set(m.uri, m));
      }
      return next;
    });

  const offerDelete = (result: ImportResult) =>
    new Promise<void>(resolve => {
      const n = result.importedUris.length;
      Alert.alert(
        'Remove originals from gallery?',
        `${plural(n, 'item')} ${n === 1 ? 'is' : 'are'} now encrypted in Enclavia. Delete the originals so they only exist in the vault?\n\nAndroid will ask you to confirm. Copies already backed up to cloud services (e.g. Google Photos) are not affected.`,
        [
          { text: 'Keep originals', style: 'cancel', onPress: () => resolve() },
          {
            text: 'Delete originals',
            style: 'destructive',
            onPress: async () => {
              try {
                const ok = await Vault.deleteFromGallery(result.importedUris);
                ToastAndroid.show(ok ? 'Originals deleted from gallery' : 'Originals kept in gallery', ToastAndroid.SHORT);
              } catch (e) {
                Alert.alert('Could not delete originals', errorMessage(e));
              }
              resolve();
            },
          },
        ],
        { cancelable: false },
      );
    });

  const runImport = async () => {
    const uris = [...selected.keys()];
    if (uris.length === 0) {
      return;
    }
    setProgress({ done: 0, total: uris.length });
    const sub = VaultEvents.onImportProgress(p => setProgress(p));
    let result: ImportResult;
    try {
      result = await Vault.importMedia(uris, target);
    } catch (e) {
      sub.remove();
      setProgress(null);
      Alert.alert('Import failed', errorMessage(e));
      return;
    }
    sub.remove();
    setProgress(null);
    bump();

    if (result.failed.length > 0) {
      const reasons = [...new Set(result.failed.map(f => f.error))].slice(0, 3).join('\n');
      await new Promise<void>(resolve =>
        Alert.alert(
          `${plural(result.failed.length, 'item')} could not be hidden`,
          `${reasons}\n\nThose originals were left untouched.`,
          [{ text: 'OK', onPress: () => resolve() }],
        ),
      );
    }
    if (result.importedUris.length > 0) {
      await offerDelete(result);
    }
    navigation.goBack();
  };

  const renderItem = useCallback(
    ({ item, index }: { item: GalleryItem; index: number }) => (
      <View style={index % COLUMNS === COLUMNS - 1 ? styles.cellLast : styles.cell}>
        <MediaTile
          size={size}
          galleryUri={item.uri}
          isVideo={item.type === 'video'}
          durationMs={item.durationMs}
          selected={selected.has(item.uri)}
          selecting
          onPress={() => toggle(item)}
        />
      </View>
    ),
    [size, selected, toggle],
  );

  const bucketOptions = useMemo(
    () =>
      albums.map(a => ({
        key: a.id || '__all__',
        label: a.name,
        hint: plural(a.count, 'item'),
        selected: bucket?.id === a.id,
        onPress: () => setBucket(a),
      })),
    [albums, bucket],
  );

  // ------------------------------------------------------------------ permission states

  if (access === 'checking') {
    return (
      <View style={styles.flex}>
        <Header title="Hide photos" onBack={() => navigation.goBack()} backIcon="close" />
        <ActivityIndicator style={styles.flex} color={colors.accent} />
      </View>
    );
  }

  if (access === 'denied' || access === 'blocked') {
    return (
      <View style={styles.flex}>
        <Header title="Hide photos" onBack={() => navigation.goBack()} backIcon="close" />
        <EmptyState
          icon="image"
          title="Allow access to your gallery"
          body="Enclavia needs to read your photos and videos so you can choose what to hide. Nothing ever leaves your phone: the app has no internet access."
        />
        <View style={[styles.permActions, { paddingBottom: insets.bottom + space.xl }]}>
          {access === 'blocked' ? (
            <Button label="Open settings" onPress={() => Linking.openSettings()} />
          ) : (
            <Button label="Allow access" onPress={ask} />
          )}
        </View>
      </View>
    );
  }

  // ------------------------------------------------------------------ picker

  return (
    <View style={styles.flex}>
      <Header
        title={selected.size ? `${selected.size} selected` : 'Hide photos'}
        onBack={() => navigation.goBack()}
        backIcon="close"
        actions={media.length ? [{ icon: 'selectAll', label: allLoadedSelected ? 'Clear' : 'Select all', onPress: toggleAll }] : []}
      />

      <View style={styles.filters}>
        <Pressable style={styles.dropdown} onPress={() => setPickBucket(true)}>
          <Text numberOfLines={1} style={styles.dropdownText}>
            {bucket?.name ?? 'Gallery'}
          </Text>
          <Icon name="chevronDown" size={18} color={colors.textDim} />
        </Pressable>
        {access === 'partial' ? (
          <Pressable onPress={ask} style={styles.partial}>
            <Text style={styles.partialText}>Limited access · Change</Text>
          </Pressable>
        ) : null}
      </View>

      {media.length === 0 && !loading ? (
        <EmptyState icon="image" title="No photos or videos" body="This folder is empty." />
      ) : (
        <FlatList
          data={media}
          keyExtractor={i => i.uri}
          numColumns={COLUMNS}
          renderItem={renderItem}
          extraData={selected}
          getItemLayout={(_, index) => ({ length: size + GAP, offset: (size + GAP) * Math.floor(index / COLUMNS), index })}
          onEndReached={() => loadMore()}
          onEndReachedThreshold={1.5}
          initialNumToRender={40}
          maxToRenderPerBatch={40}
          windowSize={9}
          removeClippedSubviews
          contentContainerStyle={{ paddingBottom: insets.bottom + 120 }}
          ListFooterComponent={loading ? <ActivityIndicator style={styles.footer} color={colors.accent} /> : undefined}
        />
      )}

      {selected.size > 0 ? (
        <View style={[styles.bar, { paddingBottom: insets.bottom + space.md }]}>
          <Pressable style={styles.target} onPress={() => setPickTarget(true)}>
            <Icon name="folder" size={18} color={colors.textDim} />
            <View style={styles.flexShrink}>
              <Text style={type.tiny}>Hide into</Text>
              <Text numberOfLines={1} style={styles.targetName}>
                {targetName}
              </Text>
            </View>
            <Icon name="chevronDown" size={16} color={colors.textDim} />
          </Pressable>
          <Button label={`Hide ${selected.size}`} icon="lock" onPress={runImport} style={styles.hideBtn} />
        </View>
      ) : null}

      <Sheet visible={pickBucket} title="Gallery folders" options={bucketOptions} onClose={() => setPickBucket(false)} />
      <Sheet
        visible={pickTarget}
        title="Hide into album"
        onClose={() => setPickTarget(false)}
        options={[
          { key: '__none__', label: 'No album', hint: 'Only in “All items”', icon: 'image', selected: target === null, onPress: () => setTarget(null) },
          ...vaultAlbums.map(a => ({
            key: a.id,
            label: a.name,
            icon: 'folder' as const,
            selected: target === a.id,
            onPress: () => setTarget(a.id),
          })),
        ]}
      />
      <ProgressModal
        visible={!!progress}
        title="Encrypting"
        done={progress?.done ?? 0}
        total={progress?.total ?? 0}
        note="Each file is encrypted and verified before it’s added."
        onCancel={() => Vault.cancelImport()}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  cell: { marginRight: GAP, marginBottom: GAP },
  cellLast: { marginBottom: GAP },
  flex: { flex: 1, backgroundColor: colors.bg },
  flexShrink: { flexShrink: 1 },
  filters: { flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingHorizontal: space.lg, paddingBottom: space.md },
  dropdown: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.pill,
    paddingHorizontal: space.lg,
    paddingVertical: 8,
    maxWidth: '65%',
  },
  dropdownText: { color: colors.text, fontSize: 14, fontWeight: '600', flexShrink: 1 },
  partial: { paddingHorizontal: space.sm, paddingVertical: 8 },
  partialText: { color: colors.warning, fontSize: 13 },
  footer: { paddingVertical: space.xl },
  bar: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingHorizontal: space.lg,
    paddingTop: space.md,
    backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  target: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.lg,
    paddingHorizontal: space.md,
    minHeight: 52,
  },
  targetName: { color: colors.text, fontSize: 14, fontWeight: '600' },
  hideBtn: { paddingHorizontal: space.lg },
  permActions: { paddingHorizontal: space.xl },
});
