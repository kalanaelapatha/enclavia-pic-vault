import { fitAspect, moveRect, normalise, resizeRect, rotatedSize, MIN_CROP } from '../src/utils/crop';

const B = { w: 400, h: 300 };

describe('fitAspect', () => {
  it('covers the whole area when free', () => {
    expect(fitAspect(B, null)).toEqual({ x: 0, y: 0, w: 400, h: 300 });
  });
  it('centres a square', () => {
    expect(fitAspect(B, 1)).toEqual({ x: 50, y: 0, w: 300, h: 300 });
  });
  it('fits a wide ratio by width', () => {
    const r = fitAspect(B, 16 / 9);
    expect(r.w).toBe(400);
    expect(r.h).toBeCloseTo(225);
    expect(r.y).toBeCloseTo(37.5);
  });
});

describe('moveRect', () => {
  it('stays inside bounds', () => {
    const r = moveRect({ x: 10, y: 10, w: 100, h: 100 }, 1000, -1000, B);
    expect(r).toEqual({ x: 300, y: 0, w: 100, h: 100 });
  });
});

describe('resizeRect', () => {
  const start = { x: 100, y: 100, w: 100, h: 100 };
  it('drags the bottom-right corner, anchoring top-left', () => {
    expect(resizeRect(start, 'br', 50, 20, B, null)).toEqual({ x: 100, y: 100, w: 150, h: 120 });
  });
  it('drags the top-left corner, anchoring bottom-right', () => {
    expect(resizeRect(start, 'tl', -50, -60, B, null)).toEqual({ x: 50, y: 40, w: 150, h: 160 });
  });
  it('never goes below the minimum size', () => {
    const r = resizeRect(start, 'br', -500, -500, B, null);
    expect(r.w).toBe(MIN_CROP);
    expect(r.h).toBe(MIN_CROP);
  });
  it('clamps to the image edges', () => {
    const r = resizeRect(start, 'br', 1000, 1000, B, null);
    expect(r).toEqual({ x: 100, y: 100, w: 300, h: 200 });
  });
  it('keeps a fixed aspect ratio', () => {
    const r = resizeRect(start, 'br', 100, 10, B, 1);
    expect(r.w).toBeCloseTo(r.h);
    expect(r.x).toBe(100);
    expect(r.y).toBe(100);
  });
  it('keeps the aspect inside bounds', () => {
    const r = resizeRect(start, 'br', 1000, 1000, B, 1);
    expect(r.w).toBeCloseTo(200);
    expect(r.h).toBeCloseTo(200);
  });
});

describe('normalise / rotatedSize', () => {
  it('normalises to fractions', () => {
    expect(normalise({ x: 100, y: 75, w: 200, h: 150 }, B)).toEqual({ x: 0.25, y: 0.25, w: 0.5, h: 0.5 });
  });
  it('swaps dimensions for quarter turns', () => {
    expect(rotatedSize({ w: 4, h: 3 }, 90)).toEqual({ w: 3, h: 4 });
    expect(rotatedSize({ w: 4, h: 3 }, 180)).toEqual({ w: 4, h: 3 });
  });
});
