export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  const value = bytes / Math.pow(1024, i);
  return `${value >= 10 || i === 0 ? Math.round(value) : value.toFixed(1)} ${units[i]}`;
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) {
    return '0:00';
  }
  const total = Math.round(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = s.toString().padStart(2, '0');
  return h > 0 ? `${h}:${m.toString().padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** 83400 -> "1:23.4" (for precise trimming). */
export function formatClock(ms: number): string {
  const t = Math.max(0, Math.round(ms / 100)) / 10;
  const m = Math.floor(t / 60);
  const s = (t - m * 60).toFixed(1).padStart(4, '0');
  return `${m}:${s}`;
}

export function formatDate(ms: number): string {
  if (!ms) {
    return '';
  }
  const d = new Date(ms);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const hh = d.getHours().toString().padStart(2, '0');
  const mm = d.getMinutes().toString().padStart(2, '0');
  return `${d.getDate()} ${months[d.getMonth()]} ${d.getFullYear()}, ${hh}:${mm}`;
}

export function formatCountdown(untilMs: number, now: number): string {
  const s = Math.max(0, Math.ceil((untilMs - now) / 1000));
  if (s >= 60) {
    const m = Math.floor(s / 60);
    return `${m}m ${(s % 60).toString().padStart(2, '0')}s`;
  }
  return `${s}s`;
}

export function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}

/** PIN rules shared with the native layer (digits only, length bounds). */
export function validatePin(pin: string, min: number, max: number): string | null {
  if (!/^\d*$/.test(pin)) {
    return 'Digits only';
  }
  if (pin.length < min) {
    return `Use at least ${min} digits`;
  }
  if (pin.length > max) {
    return `Use at most ${max} digits`;
  }
  if (/^(\d)\1+$/.test(pin)) {
    return 'Avoid repeating the same digit';
  }
  if ('01234567890123456789'.includes(pin) || '98765432109876543210'.includes(pin)) {
    return 'Avoid simple sequences';
  }
  return null;
}
