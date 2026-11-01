export const colors = {
  bg: '#0A0C11',
  surface: '#12151D',
  surfaceAlt: '#191D28',
  surfaceHigh: '#212634',
  border: '#262C3A',
  text: '#EEF1F6',
  textDim: '#9AA3B5',
  textFaint: '#5F6779',
  accent: '#6EE7B7',
  accentDim: 'rgba(110, 231, 183, 0.14)',
  onAccent: '#05261A',
  danger: '#F87171',
  dangerDim: 'rgba(248, 113, 113, 0.14)',
  warning: '#FBBF24',
  scrim: 'rgba(5, 7, 10, 0.72)',
  selection: 'rgba(110, 231, 183, 0.28)',
} as const;

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;

export const radius = { sm: 8, md: 12, lg: 16, xl: 22, pill: 999 } as const;

export const type = {
  title: { fontSize: 22, fontWeight: '700' as const, color: colors.text, letterSpacing: 0.2 },
  heading: { fontSize: 17, fontWeight: '600' as const, color: colors.text },
  body: { fontSize: 15, color: colors.text },
  caption: { fontSize: 13, color: colors.textDim },
  tiny: { fontSize: 11, color: colors.textDim },
};
