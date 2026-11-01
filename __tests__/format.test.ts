/**
 * @format
 */
import { formatBytes, formatCountdown, formatDuration, plural, validatePin } from '../src/utils/format';

describe('formatBytes', () => {
  it('formats sizes', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(25 * 1024 * 1024)).toBe('25 MB');
  });
});

describe('formatDuration', () => {
  it('formats minutes and hours', () => {
    expect(formatDuration(0)).toBe('0:00');
    expect(formatDuration(65_000)).toBe('1:05');
    expect(formatDuration(3_725_000)).toBe('1:02:05');
  });
});

describe('formatCountdown', () => {
  it('counts down seconds and minutes', () => {
    expect(formatCountdown(30_000, 0)).toBe('30s');
    expect(formatCountdown(125_000, 0)).toBe('2m 05s');
    expect(formatCountdown(0, 10)).toBe('0s');
  });
});

describe('plural', () => {
  it('pluralises', () => {
    expect(plural(1, 'photo')).toBe('1 photo');
    expect(plural(3, 'photo')).toBe('3 photos');
  });
});

describe('validatePin', () => {
  const v = (p: string) => validatePin(p, 6, 16);
  it('enforces length', () => {
    expect(v('12')).toMatch(/at least 6/);
    expect(v('1'.repeat(17))).toMatch(/at most 16/);
  });
  it('rejects weak PINs', () => {
    expect(v('111111')).toMatch(/repeating/);
    expect(v('123456')).toMatch(/sequences/);
    expect(v('987654')).toMatch(/sequences/);
  });
  it('rejects non-digits', () => {
    expect(v('12a456')).toMatch(/Digits/);
  });
  it('accepts reasonable PINs', () => {
    expect(v('482913')).toBeNull();
    expect(v('90817263')).toBeNull();
  });
});
