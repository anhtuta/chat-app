export type KnownThemeId =
  | "forest"
  | "ocean"
  | "arcticBlue"
  | "mossNight"
  | "darkSlate"
  | "darkNeon"
  | "darkForest"
  | "darkOcean"
  | "darkPurple";

export type ThemeId = KnownThemeId | (string & {});

export interface ThemeOption {
  id: ThemeId;
  label: string;
}

export interface ThemeColorTokens {
  primary: string;
  primaryDark: string;
  primarySoft: string;
  surface: string;
  shadowBrand: string;
}

export interface ThemeGradientTokens {
  brand: string;
}

export type ThemeCssVars = Record<string, string>;

export interface ResolvedTheme {
  id: ThemeId;
  label: string;
  colorTokens: ThemeColorTokens;
  gradientTokens: ThemeGradientTokens;
  cssVars: ThemeCssVars;
}
