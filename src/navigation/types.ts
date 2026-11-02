import type { NativeStackScreenProps } from '@react-navigation/native-stack';

export type RootStackParamList = {
  SetupPin: undefined;
  Lock: undefined;
  Home: undefined;
  Album: { albumId: string | null; title: string };
  Viewer: { index: number; albumId: string | null };
  Player: { itemId: string; name: string };
  Crop: { itemId: string; version: number; isVideo?: boolean };
  Trim: { itemId: string; name: string };
  GalleryPicker: { albumId: string | null };
  Settings: undefined;
  ChangePin: undefined;
};

export type ScreenProps<T extends keyof RootStackParamList> = NativeStackScreenProps<RootStackParamList, T>;
