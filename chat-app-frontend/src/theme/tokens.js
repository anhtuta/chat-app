export const THEME_STORAGE_KEY = "chat-app-theme";
export const DEFAULT_THEME_ID = "forest";

const THEME_PRESETS = {
    forest: {
        label: "Forest",
        colors: {
            primary: "#609966",
            primaryDark: "#40513B",
            primarySoft: "#9DC08B",
            surface: "#EDF1D6",
        },
    },
    ocean: {
        label: "Ocean",
        colors: {
            primary: "#2A9D8F",
            primaryDark: "#264653",
            primarySoft: "#8ECAE6",
            surface: "#F1FAEE",
        },
    },
    arcticBlue: {
        label: "Arctic Blue",
        colors: {
            primary: "#576CBC",
            primaryDark: "#0B2447",
            primarySoft: "#19376D",
            surface: "#A5D7E8",
        },
    },
    darkSlate: {
        label: "Dark Slate",
        colors: {
            primary: "#00D9FF",
            primaryDark: "#0A0E27",
            primarySoft: "#1A2847",
            surface: "#0F1419",
        },
    },
    darkForest: {
        label: "Dark Forest",
        colors: {
            primary: "#52B788",
            primaryDark: "#0B3D2C",
            primarySoft: "#1B4332",
            surface: "#081B15",
        },
    },
    darkPurple: {
        label: "Dark Purple",
        colors: {
            primary: "#5764EF",
            primaryDark: "#121214",
            primarySoft: "#1A1A1E",
            surface: "#121214",
        },
    },
};

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

const normalizeHex = (hex) => {
    const value = hex.replace("#", "").trim();
    if (value.length === 3) {
        return value
            .split("")
            .map((char) => char + char)
            .join("");
    }
    return value;
};

const hexToRgb = (hex) => {
    const normalized = normalizeHex(hex);
    const intValue = Number.parseInt(normalized, 16);
    return {
        r: (intValue >> 16) & 255,
        g: (intValue >> 8) & 255,
        b: intValue & 255,
    };
};

const rgbToHex = ({ r, g, b }) =>
    `#${[r, g, b]
        .map((channel) => clamp(Math.round(channel), 0, 255).toString(16).padStart(2, "0"))
        .join("")}`;

const mix = (hexA, hexB, weight) => {
    const a = hexToRgb(hexA);
    const b = hexToRgb(hexB);
    return rgbToHex({
        r: a.r * (1 - weight) + b.r * weight,
        g: a.g * (1 - weight) + b.g * weight,
        b: a.b * (1 - weight) + b.b * weight,
    });
};

const alpha = (hex, opacity) => {
    const { r, g, b } = hexToRgb(hex);
    return `rgba(${r}, ${g}, ${b}, ${opacity})`;
};

const buildCssVars = ({ primary, primaryDark, primarySoft, surface }) => {
    // Detect if this is a dark theme by checking if surface is dark
    const surfaceRgb = hexToRgb(surface);
    const surfaceLuminance = (0.299 * surfaceRgb.r + 0.587 * surfaceRgb.g + 0.114 * surfaceRgb.b) / 255;
    const isDarkTheme = surfaceLuminance < 0.5;

    // For dark themes, use light text; for light themes, use dark text
    const textColor = isDarkTheme ? "#FBFBFB" : primaryDark;
    const textSecondaryColor = isDarkTheme ? "#E0E0E0" : primary;
    const sidebarBg = isDarkTheme ? primaryDark : surface;
    const chatBg = isDarkTheme ? primarySoft : surface;
    const borderColor = isDarkTheme ? primarySoft : primarySoft;

    // For light themes, mix towards white; for dark, stay dark
    const surfaceMuted = isDarkTheme ? mix(surface, "#1A1A1E", 0.4) : mix(surface, "#ffffff", 0.35);
    const surfaceSoft = isDarkTheme ? mix(primarySoft, primary, 0.12) : mix(surface, primarySoft, 0.18);
    const surfaceSubtle = isDarkTheme ? mix(primarySoft, primary, 0.25) : mix(surface, primarySoft, 0.32);
    const surfaceRaised = isDarkTheme ? mix(primarySoft, "#2A2A30", 0.5) : mix(surface, "#ffffff", 0.55);
    const surfaceHover = isDarkTheme ? mix(primarySoft, primary, 0.4) : mix(surface, primarySoft, 0.45);

    return {
        "--color-primary": primary,
        "--color-primary-dark": primaryDark,
        "--color-primary-soft": primarySoft,
        "--color-surface": surface,
        "--color-surface-muted": surfaceMuted,
        "--color-surface-soft": surfaceSoft,
        "--color-surface-subtle": surfaceSubtle,
        "--color-surface-raised": surfaceRaised,
        "--color-surface-hover": surfaceHover,
        "--color-border": isDarkTheme ? mix(primarySoft, primary, 0.5) : primarySoft,
        "--color-border-strong": primary,
        "--color-border-soft": isDarkTheme ? mix(primarySoft, primary, 0.3) : mix(primarySoft, surface, 0.5),
        "--color-text-primary": textColor,
        "--color-text-heading": textColor,
        "--color-text-secondary": textSecondaryColor,
        "--color-text-tertiary": textSecondaryColor,
        "--color-text-muted": textColor,
        "--color-link": primary,
        "--color-status-success-bg": primarySoft,
        "--color-status-success-soft": alpha(primary, isDarkTheme ? 0.25 : 0.28),
        "--color-status-success-text": isDarkTheme ? "#4ADE80" : primaryDark,
        "--color-status-live-text": primary,
        "--color-status-error-bg": isDarkTheme ? alpha(primary, 0.35) : primaryDark,
        "--color-status-error-soft": alpha(primary, isDarkTheme ? 0.2 : 0.18),
        "--color-status-error-text": isDarkTheme ? "#FF6B6B" : surface,
        "--color-status-live-error": isDarkTheme ? "#FF6B6B" : primaryDark,
        "--color-selection-bg": alpha(primary, isDarkTheme ? 0.25 : 0.35),
        "--color-message-received": isDarkTheme ? surfaceSubtle : surfaceSubtle,
        "--color-auth-error-bg": alpha(primary, isDarkTheme ? 0.15 : 0.16),
        "--color-auth-error-text": textColor,
        "--color-overlay": alpha(primaryDark, isDarkTheme ? 0.6 : 0.5),
        "--color-overlay-soft": alpha(primaryDark, isDarkTheme ? 0.4 : 0.3),
        "--color-shadow-soft": alpha(primaryDark, isDarkTheme ? 0.3 : 0.1),
        "--color-shadow-medium": alpha(primaryDark, isDarkTheme ? 0.4 : 0.16),
        "--color-shadow-strong": alpha(primaryDark, isDarkTheme ? 0.5 : 0.28),
        "--color-shadow-brand": alpha(primary, isDarkTheme ? 0.3 : 0.35),
        "--color-button-ghost": alpha(primary, isDarkTheme ? 0.15 : 0.2),
        "--color-button-ghost-hover": alpha(primary, isDarkTheme ? 0.25 : 0.3),
        "--color-button-ghost-border": alpha(primary, isDarkTheme ? 0.4 : 0.45),
        "--gradient-brand": `linear-gradient(135deg, ${primary} 0%, ${primaryDark} 100%)`,
    };
};

export const themeOptions = Object.entries(THEME_PRESETS).map(([id, theme]) => ({
    id,
    label: theme.label,
}));

export const resolveThemeTokens = (themeId) => {
    const selected = THEME_PRESETS[themeId] || THEME_PRESETS[DEFAULT_THEME_ID];
    const { primary, primaryDark, primarySoft, surface } = selected.colors;

    return {
        id: themeId in THEME_PRESETS ? themeId : DEFAULT_THEME_ID,
        label: selected.label,
        colorTokens: {
            primary,
            primaryDark,
            primarySoft,
            surface,
            shadowBrand: alpha(primaryDark, 0.35),
        },
        gradientTokens: {
            brand: `linear-gradient(135deg, ${primary} 0%, ${primaryDark} 100%)`,
        },
        cssVars: buildCssVars({ primary, primaryDark, primarySoft, surface }),
    };
};