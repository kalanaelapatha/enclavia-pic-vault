import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  LayoutChangeEvent,
  PanResponder,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  ToastAndroid,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../components/Button';
import { IconButton } from '../components/Header';
import { Icon } from '../components/Icon';
import { Sheet } from '../components/Sheet';
import VaultImageView from '../native/VaultImageViewNativeComponent';
import type { ScreenProps } from '../navigation/types';
import { errorMessage, Vault } from '../services/vault';
import { useSession } from '../state/SessionContext';
import { colors, radius, space } from '../theme';
import { Corner, fitAspect, moveRect, normalise, Rect, resizeRect, rotatedSize, Size } from '../utils/crop';

const ASPECTS: { label: string; value: number | null }[] = [
  { label: 'Free', value: null },
  { label: '1:1', value: 1 },
  { label: '4:3', value: 4 / 3 },
  { label: '3:4', value: 3 / 4 },
  { label: '16:9', value: 16 / 9 },
  { label: '9:16', value: 9 / 16 },
];

const HANDLE = 48; // touch area
const DOT = 22;

export function CropScreen({ navigation, route }: ScreenProps<'Crop'>) {
  const { itemId, version } = route.params;
  const { bump } = useSession();
  const insets = useSafeAreaInsets();
  const [photo, setPhoto] = useState<Size | null>(null);
  const [area, setArea] = useState<Size | null>(null);
  const [rotation, setRotation] = useState(0);
  const [aspect, setAspect] = useState<number | null>(null);
  const [rect, setRect] = useState<Rect | null>(null);
  const [saving, setSaving] = useState(false);
  const [choose, setChoose] = useState(false);

  useEffect(() => {
    Vault.getMediaInfo(itemId)
      .then(i => setPhoto({ w: i.width, h: i.height }))
      .catch(e => {
        Alert.alert('Cannot edit this photo', errorMessage(e));
        navigation.goBack();
      });
  }, [itemId, navigation]);

  // Where the (rotated) photo sits on screen.
  const display = useMemo(() => {
    if (!photo || !area) {
      return null;
    }
    const r = rotatedSize(photo, rotation);
    const scale = Math.min(area.w / r.w, area.h / r.h);
    const w = r.w * scale;
    const h = r.h * scale;
    return { w, h, left: (area.w - w) / 2, top: (area.h - h) / 2 };
  }, [photo, area, rotation]);

  const bounds = useMemo<Size | null>(() => (display ? { w: display.w, h: display.h } : null), [display]);

  // Reset the crop box whenever the frame or ratio changes.
  useEffect(() => {
    if (bounds) {
      setRect(fitAspect(bounds, aspect));
    }
  }, [bounds, aspect]);

  // Gestures read the latest values through refs so responders are created once.
  const live = useRef({ rect, bounds, aspect });
  live.current = { rect, bounds, aspect };
  const startRect = useRef<Rect | null>(null);

  const mover = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onPanResponderGrant: () => {
          startRect.current = live.current.rect;
        },
        onPanResponderMove: (_, g) => {
          const { bounds: b } = live.current;
          if (startRect.current && b) {
            setRect(moveRect(startRect.current, g.dx, g.dy, b));
          }
        },
      }),
    [],
  );

  const corners = useMemo(() => {
    const make = (corner: Corner) =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onPanResponderTerminationRequest: () => false,
        onPanResponderGrant: () => {
          startRect.current = live.current.rect;
        },
        onPanResponderMove: (_, g) => {
          const { bounds: b, aspect: a } = live.current;
          if (startRect.current && b) {
            setRect(resizeRect(startRect.current, corner, g.dx, g.dy, b, a));
          }
        },
      });
    return { tl: make('tl'), tr: make('tr'), bl: make('bl'), br: make('br') };
  }, []);

  const save = async (replace: boolean) => {
    if (!rect || !bounds) {
      return;
    }
    setSaving(true);
    const n = normalise(rect, bounds);
    try {
      await Vault.cropImage(itemId, rotation, n.x, n.y, n.w, n.h, replace);
      bump();
      ToastAndroid.show(replace ? 'Photo updated' : 'Saved as a new photo', ToastAndroid.SHORT);
      navigation.goBack();
    } catch (e) {
      setSaving(false);
      Alert.alert('Could not save', errorMessage(e));
    }
  };

  const onLayout = (e: LayoutChangeEvent) => {
    const { width, height } = e.nativeEvent.layout;
    setArea({ w: width - space.xl * 2, h: height - space.xl * 2 });
  };

  // The image view is laid out un-rotated, then rotated into the display box.
  const raw = display && (rotation % 180 === 0 ? { w: display.w, h: display.h } : { w: display.h, h: display.w });

  return (
    <View style={styles.flex}>
      <StatusBar hidden />
      <View style={[styles.top, { paddingTop: insets.top + space.xs }]}>
        <IconButton icon="close" label="Cancel" onPress={() => navigation.goBack()} />
        <Text style={styles.title}>Crop</Text>
        <Pressable
          onPress={() => {
            setRotation(0);
            setAspect(null);
            if (bounds) {
              setRect(fitAspect(bounds, null));
            }
          }}
          hitSlop={10}>
          <Text style={styles.reset}>Reset</Text>
        </Pressable>
      </View>

      <View style={styles.stage} onLayout={onLayout}>
        {display && raw && rect ? (
          <View style={[styles.photo, { left: display.left + space.xl, top: display.top + space.xl, width: display.w, height: display.h }]}>
            <View
              style={{
                ...styles.abs,
                width: raw.w,
                height: raw.h,
                left: (display.w - raw.w) / 2,
                top: (display.h - raw.h) / 2,
                transform: [{ rotate: `${rotation}deg` }],
              }}>
              <VaultImageView style={StyleSheet.absoluteFill} itemId={itemId} version={version} variant="full" resizeMode="contain" />
            </View>

            {/* Dim everything outside the crop box. */}
            <View style={[styles.shade, styles.shadeTop, { height: rect.y }]} />
            <View style={[styles.shade, styles.shadeBottom, { top: rect.y + rect.h }]} />
            <View style={[styles.shade, styles.shadeLeft, { top: rect.y, width: rect.x, height: rect.h }]} />
            <View style={[styles.shade, styles.shadeRight, { left: rect.x + rect.w, top: rect.y, height: rect.h }]} />

            <View style={[styles.box, { left: rect.x, top: rect.y, width: rect.w, height: rect.h }]} {...mover.panHandlers}>
              <View style={[styles.gridV, styles.third1V]} />
              <View style={[styles.gridV, styles.third2V]} />
              <View style={[styles.gridH, styles.third1H]} />
              <View style={[styles.gridH, styles.third2H]} />
            </View>

            {(['tl', 'tr', 'bl', 'br'] as Corner[]).map(c => (
              <View
                key={c}
                {...corners[c].panHandlers}
                style={[
                  styles.handle,
                  {
                    left: (c[1] === 'l' ? rect.x : rect.x + rect.w) - HANDLE / 2,
                    top: (c[0] === 't' ? rect.y : rect.y + rect.h) - HANDLE / 2,
                  },
                ]}
              >
                <View style={styles.dot} />
              </View>
            ))}
          </View>
        ) : (
          <ActivityIndicator style={styles.flex} color={colors.accent} />
        )}
      </View>

      <View style={[styles.bottom, { paddingBottom: insets.bottom + space.md }]}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
          {ASPECTS.map(a => (
            <Pressable key={a.label} onPress={() => setAspect(a.value)} style={[styles.chip, aspect === a.value && styles.chipOn]}>
              <Text style={[styles.chipText, aspect === a.value && styles.chipTextOn]}>{a.label}</Text>
            </Pressable>
          ))}
        </ScrollView>
        <View style={styles.actions}>
          <Pressable onPress={() => setRotation(r => (r + 90) % 360)} style={styles.rotate} accessibilityLabel="Rotate">
            <Icon name="rotate" color="#fff" />
            <Text style={styles.rotateText}>Rotate</Text>
          </Pressable>
          <Button label="Save" icon="check" onPress={() => setChoose(true)} loading={saving} style={styles.flexBtn} />
        </View>
      </View>

      <Sheet
        visible={choose}
        title="Save cropped photo"
        onClose={() => setChoose(false)}
        options={[
          { key: 'copy', label: 'Save as new copy', hint: 'Keeps the original photo', icon: 'plus', onPress: () => save(false) },
          { key: 'replace', label: 'Replace original', hint: 'The uncropped photo is erased', icon: 'edit', danger: true, onPress: () => save(true) },
        ]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: '#000' },
  flexBtn: { flex: 1 },
  top: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: space.sm, gap: space.sm },
  title: { flex: 1, color: '#fff', fontSize: 17, fontWeight: '600' },
  reset: { color: colors.accent, fontSize: 15, fontWeight: '600', paddingHorizontal: space.md },
  stage: { flex: 1 },
  photo: { position: 'absolute', overflow: 'visible' },
  abs: { position: 'absolute' },
  shade: { position: 'absolute', backgroundColor: 'rgba(0,0,0,0.62)' },
  shadeTop: { left: 0, top: 0, right: 0 },
  shadeBottom: { left: 0, right: 0, bottom: 0 },
  shadeLeft: { left: 0 },
  shadeRight: { right: 0 },
  third1V: { left: '33.33%' },
  third2V: { left: '66.66%' },
  third1H: { top: '33.33%' },
  third2H: { top: '66.66%' },
  box: { position: 'absolute', borderWidth: 1.5, borderColor: '#fff' },
  gridV: { position: 'absolute', top: 0, bottom: 0, width: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.5)' },
  gridH: { position: 'absolute', left: 0, right: 0, height: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.5)' },
  handle: { position: 'absolute', width: HANDLE, height: HANDLE, alignItems: 'center', justifyContent: 'center' },
  dot: { width: DOT, height: DOT, borderRadius: DOT / 2, backgroundColor: colors.accent, borderWidth: 3, borderColor: '#000' },
  bottom: { paddingTop: space.md, gap: space.md, backgroundColor: '#000' },
  chips: { paddingHorizontal: space.lg, gap: space.sm },
  chip: { paddingHorizontal: space.lg, paddingVertical: 8, borderRadius: radius.pill, backgroundColor: colors.surfaceAlt },
  chipOn: { backgroundColor: colors.accent },
  chipText: { color: colors.text, fontSize: 14, fontWeight: '600' },
  chipTextOn: { color: colors.onAccent },
  actions: { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingHorizontal: space.lg },
  rotate: { alignItems: 'center', gap: 2, paddingHorizontal: space.md },
  rotateText: { color: '#fff', fontSize: 12 },
});
