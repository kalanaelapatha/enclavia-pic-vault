export type Rect = { x: number; y: number; w: number; h: number };
export type Size = { w: number; h: number };
export type Corner = 'tl' | 'tr' | 'bl' | 'br';

export const MIN_CROP = 48;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** Largest rect with [aspect] (w/h) centred in [bounds]; whole area when aspect is null. */
export function fitAspect(bounds: Size, aspect: number | null): Rect {
  if (!aspect) {
    return { x: 0, y: 0, w: bounds.w, h: bounds.h };
  }
  let w = bounds.w;
  let h = w / aspect;
  if (h > bounds.h) {
    h = bounds.h;
    w = h * aspect;
  }
  return { x: (bounds.w - w) / 2, y: (bounds.h - h) / 2, w, h };
}

/** Moves the rect by (dx, dy), keeping it inside bounds. */
export function moveRect(start: Rect, dx: number, dy: number, bounds: Size): Rect {
  return {
    ...start,
    x: clamp(start.x + dx, 0, bounds.w - start.w),
    y: clamp(start.y + dy, 0, bounds.h - start.h),
  };
}

/** Drags one corner; the opposite corner stays put. Respects bounds, minimum size and aspect. */
export function resizeRect(start: Rect, corner: Corner, dx: number, dy: number, bounds: Size, aspect: number | null): Rect {
  const left = corner === 'tl' || corner === 'bl';
  const top = corner === 'tl' || corner === 'tr';
  const ax = left ? start.x + start.w : start.x;
  const ay = top ? start.y + start.h : start.y;
  const mx = clamp((left ? start.x : start.x + start.w) + dx, 0, bounds.w);
  const my = clamp((top ? start.y : start.y + start.h) + dy, 0, bounds.h);

  const maxW = left ? ax : bounds.w - ax;
  const maxH = top ? ay : bounds.h - ay;
  const minW = Math.min(MIN_CROP, maxW);
  const minH = Math.min(MIN_CROP, maxH);
  let w = clamp(left ? ax - mx : mx - ax, minW, maxW);
  let h = clamp(top ? ay - my : my - ay, minH, maxH);

  if (aspect) {
    if (w / h > aspect) {
      w = h * aspect;
    } else {
      h = w / aspect;
    }
    if (w > maxW) {
      w = maxW;
      h = w / aspect;
    }
    if (h > maxH) {
      h = maxH;
      w = h * aspect;
    }
  }
  return { x: left ? ax - w : ax, y: top ? ay - h : ay, w, h };
}

/** Converts a rect in display pixels to 0..1 fractions of the image. */
export function normalise(rect: Rect, bounds: Size): Rect {
  return {
    x: clamp(rect.x / bounds.w, 0, 1),
    y: clamp(rect.y / bounds.h, 0, 1),
    w: clamp(rect.w / bounds.w, 0, 1),
    h: clamp(rect.h / bounds.h, 0, 1),
  };
}

/** Size of the photo once rotated by 0/90/180/270 degrees. */
export function rotatedSize(size: Size, rotation: number): Size {
  return rotation % 180 === 0 ? size : { w: size.h, h: size.w };
}
