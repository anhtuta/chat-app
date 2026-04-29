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
    amethyst: {
        label: "Amethyst",
        colors: {
            primary: "#667EEA",
            primaryDark: "#764BA2",
            primarySoft: "#9A7BC7",
            surface: "#F5F2FB",
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
    cyanSlate: {
        label: "Cyan Slate",
        colors: {
            primary: "#00ADB5",
            primaryDark: "#222831",
            primarySoft: "#393E46",
            surface: "#EEEEEE",
        },
    },
    amberSlate: {
        label: "Amber Slate",
        colors: {
            primary: "#FFD369",
            primaryDark: "#222831",
            primarySoft: "#393E46",
            surface: "#EEEEEE",
        },
    },
    neonViolet: {
        label: "Neon Violet",
        colors: {
            primary: "#892CDC",
            primaryDark: "#000000",
            primarySoft: "#52057B",
            surface: "#BC6FF1",
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
    mossNight: {
        label: "Moss Night",
        colors: {
            primary: "#4E9F3D",
            primaryDark: "#191A19",
            primarySoft: "#1E5128",
            surface: "#D8E9A8",
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
    const surfaceMuted = mix(surface, "#ffffff", 0.35);
    const surfaceSoft = mix(surface, primarySoft, 0.18);
    const surfaceSubtle = mix(surface, primarySoft, 0.32);
    const surfaceRaised = mix(surface, "#ffffff", 0.55);
    const surfaceHover = mix(surface, primarySoft, 0.45);

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
        "--color-border": primarySoft,
        "--color-border-strong": primary,
        "--color-border-soft": mix(primarySoft, surface, 0.5),
        "--color-text-primary": primaryDark,
        "--color-text-heading": primaryDark,
        "--color-text-secondary": primary,
        "--color-text-tertiary": primary,
        "--color-text-muted": primaryDark,
        "--color-link": primaryDark,
        "--color-status-success-bg": primarySoft,
        "--color-status-success-soft": alpha(primarySoft, 0.28),
        "--color-status-success-text": primaryDark,
        "--color-status-live-text": primary,
        "--color-status-error-bg": primaryDark,
        "--color-status-error-soft": alpha(primaryDark, 0.18),
        "--color-status-error-text": surface,
        "--color-status-live-error": primaryDark,
        "--color-selection-bg": alpha(primarySoft, 0.35),
        "--color-message-received": surfaceSubtle,
        "--color-auth-error-bg": alpha(primaryDark, 0.16),
        "--color-auth-error-text": primaryDark,
        "--color-overlay": alpha(primaryDark, 0.5),
        "--color-overlay-soft": alpha(primaryDark, 0.3),
        "--color-shadow-soft": alpha(primaryDark, 0.1),
        "--color-shadow-medium": alpha(primaryDark, 0.16),
        "--color-shadow-strong": alpha(primaryDark, 0.28),
        "--color-shadow-brand": alpha(primaryDark, 0.35),
        "--color-button-ghost": alpha(surface, 0.2),
        "--color-button-ghost-hover": alpha(surface, 0.3),
        "--color-button-ghost-border": alpha(surface, 0.45),
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