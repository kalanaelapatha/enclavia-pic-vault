import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, PanResponder, Pressable, StatusBar, StyleSheet, Text, ToastAndroid, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../components/Button';
import { IconButton } from '../components/Header';
import { Icon } from '../components/Icon';
import { Sheet } from '../components/Sheet';
import VaultImageView from '../native/VaultImageViewNativeComponent';
import VaultVideoView from '../native/VaultVideoViewNativeComponent';
import type { ScreenProps } from '../navigation/types';
import { errorMessage, Vault } from '../services/vault';
import { useSession } from '../state/SessionContext';
import { colors, radius, space } from '../theme';
import { formatClock } from '../utils/format';

const FRAMES = 8;
const MIN_CLIP_MS = 1000;
const HANDLE_W = 18;
const STRIP_H = 64;

type Range = { start: number; end: number };

export function TrimScreen({ navigation, route }: ScreenProps<'Trim'>) {
  const { itemId, name } = route.params;
  const { bump } = useSession();
  const insets = useSafeAreaInsets();
  const [duration, setDuration] = useState(0);
  const [range, setRange] = useState<Range>({ start: 0, end: 0 });
  const [drag, setDrag] = useState<Range | null>(null);
  const [width, setWidth] = useState(0);
  const [paused, setPaused] = useState(false);
  const [saving, setSaving] = useState(false);
  const [choose, setChoose] = useState(false);

  useEffect(() => {
    Vault.getMediaInfo(itemId)
      .then(i => {
        setDuration(i.durationMs);
        setRange({ start: 0, end: i.durationMs });
      })
      .catch(e => {
        Alert.alert('Cannot edit this video', errorMessage(e));
        navigation.goBack();
      });
  }, [itemId, navigation]);

  const live = useRef({ range, duration, width });
  live.current = { range, duration, width };
  const base = useRef<Range>(range);
  const pending = useRef<Range | null>(null);

  // One responder per handle. While dragging only the overlay moves; the
  // player is re-clipped on release so it isn't rebuilt on every pixel.
  const handles = useMemo(() => {
    const make = (which: 'start' | 'end') =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onPanResponderTerminationRequest: () => false,
        onPanResponderGrant: () => {
          base.current = live.current.range;
          pending.current = live.current.range;
          setPaused(true);
        },
        onPanResponderMove: (_, g) => {
          const { duration: d, width: w } = live.current;
          if (!d || !w) {
            return;
          }
          const delta = (g.dx / w) * d;
          const b = base.current;
          const next =
            which === 'start'
              ? { start: Math.round(Math.min(Math.max(0, b.start + delta), b.end - MIN_CLIP_MS)), end: b.end }
              : { start: b.start, end: Math.round(Math.max(Math.min(d, b.end + delta), b.start + MIN_CLIP_MS)) };
          pending.current = next;
          setDrag(next);
        },
        onPanResponderRelease: () => {
          if (pending.current) {
            setRange(pending.current);
          }
          setDrag(null);
          setPaused(false);
        },
      });
    return { start: make('start'), end: make('end') };
  }, []);

  const shown = drag ?? range;
  const x = (ms: number) => (duration ? (ms / duration) * width : 0);
  const unchanged = range.start <= 0 && range.end >= duration;

  const save = async (replace: boolean) => {
    setSaving(true);
    setPaused(true);
    try {
      await Vault.trimVideo(itemId, range.start, range.end, replace);
      bump();
      ToastAndroid.show(replace ? 'Video updated' : 'Saved as a new video', ToastAndroid.SHORT);
      navigation.goBack();
    } catch (e) {
      setSaving(false);
      Alert.alert('Could not cut video', errorMessage(e));
    }
  };

  return (
    <View style={styles.flex}>
      <StatusBar hidden />
      <View style={[styles.top, { paddingTop: insets.top + space.xs }]}>
        <IconButton icon="close" label="Cancel" onPress={() => navigation.goBack()} />
        <Text numberOfLines={1} style={styles.title}>
          Cut video
        </Text>
      </View>

      <View style={styles.flex}>
        {duration > 0 ? (
          <VaultVideoView
            style={StyleSheet.absoluteFill}
            itemId={itemId}
            clipStartMs={range.start}
            clipEndMs={range.end}
            repeat
            paused={paused}
          />
        ) : (
          <ActivityIndicator style={styles.flex} color={colors.accent} />
        )}
      </View>

      <View style={[styles.panel, { paddingBottom: insets.bottom + space.md }]}>
        <View style={styles.times}>
          <Text style={styles.time}>{formatClock(shown.start)}</Text>
          <Text style={styles.length}>{formatClock(shown.end - shown.start)} selected</Text>
          <Text style={styles.time}>{formatClock(shown.end)}</Text>
        </View>

        <View style={styles.track} onLayout={e => setWidth(e.nativeEvent.layout.width)}>
          <View style={styles.strip}>
            {duration > 0
              ? Array.from({ length: FRAMES }).map((_, i) => (
                  <VaultImageView
                    key={i}
                    style={styles.frame}
                    itemId={itemId}
                    variant="frame"
                    frameTimeMs={Math.round((duration * (i + 0.5)) / FRAMES)}
                    resizeMode="cover"
                  />
                ))
              : null}
          </View>
          {width > 0 && duration > 0 ? (
            <>
              <View style={[styles.dim, styles.dimLeft, { width: x(shown.start) }]} />
              <View style={[styles.dim, styles.dimRight, { left: x(shown.end) }]} />
              <View style={[styles.window, { left: x(shown.start), width: x(shown.end) - x(shown.start) }]} />
              <View {...handles.start.panHandlers} style={[styles.handle, { left: x(shown.start) - HANDLE_W }]}>
                <View style={styles.grip} />
              </View>
              <View {...handles.end.panHandlers} style={[styles.handle, { left: x(shown.end) }]}>
                <View style={styles.grip} />
              </View>
            </>
          ) : null}
        </View>

        <Text style={styles.note}>
          Drag the handles to choose the part to keep. Cuts are lossless and start at the nearest keyframe.
        </Text>

        <View style={styles.actions}>
          <Pressable onPress={() => setPaused(p => !p)} style={styles.play} accessibilityLabel={paused ? 'Play' : 'Pause'}>
            <Icon name={paused ? 'play' : 'pause'} color="#fff" />
          </Pressable>
          <Button
            label="Save"
            icon="scissors"
            onPress={() => setChoose(true)}
            disabled={unchanged || duration === 0}
            loading={saving}
            style={styles.flexBtn}
          />
        </View>
      </View>

      <Sheet
        visible={choose}
        title={`Save cut of “${name}”`}
        onClose={() => setChoose(false)}
        options={[
          { key: 'copy', label: 'Save as new video', hint: 'Keeps the full original', icon: 'plus', onPress: () => save(false) },
          { key: 'replace', label: 'Replace original', hint: 'The cut-out parts are erased', icon: 'edit', danger: true, onPress: () => save(true) },
        ]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: '#000' },
  flexBtn: { flex: 1 },
  top: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: space.sm, paddingBottom: space.sm },
  title: { flex: 1, color: '#fff', fontSize: 17, fontWeight: '600', paddingHorizontal: space.xs },
  panel: { paddingTop: space.lg, paddingHorizontal: space.xl, gap: space.md, backgroundColor: colors.surface },
  times: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  time: { color: colors.textDim, fontSize: 13, fontVariant: ['tabular-nums'] },
  length: { color: colors.accent, fontSize: 14, fontWeight: '700', fontVariant: ['tabular-nums'] },
  track: { height: STRIP_H, marginHorizontal: HANDLE_W },
  strip: { flexDirection: 'row', height: STRIP_H, borderRadius: radius.sm, overflow: 'hidden', backgroundColor: colors.surfaceAlt },
  frame: { flex: 1, height: STRIP_H },
  dim: { position: 'absolute', top: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.65)' },
  dimLeft: { left: 0 },
  dimRight: { right: 0 },
  window: { position: 'absolute', top: 0, bottom: 0, borderTopWidth: 3, borderBottomWidth: 3, borderColor: colors.accent },
  handle: {
    position: 'absolute',
    top: 0,
    width: HANDLE_W,
    height: STRIP_H,
    backgroundColor: colors.accent,
    borderRadius: 4,
    alignItems: 'center',
    justifyContent: 'center',
  },
  grip: { width: 3, height: 22, borderRadius: 2, backgroundColor: colors.onAccent },
  note: { color: colors.textFaint, fontSize: 12, lineHeight: 17 },
  actions: { flexDirection: 'row', alignItems: 'center', gap: space.md },
  play: { width: 52, height: 52, borderRadius: 26, backgroundColor: colors.surfaceHigh, alignItems: 'center', justifyContent: 'center' },
});
