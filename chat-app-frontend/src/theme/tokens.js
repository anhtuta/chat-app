// The tradeoff is important: with plain CSS plus JS, you still have two styling systems.
// If you want a true single source of truth for both CSS files and MUI theme values, 
// the next step would be one of these:
// 1. Generate the CSS variables from the JS token file at build time.
// 2. Move more of the styling into MUI/theme-driven components and reduce plain CSS.
// 3. Accept mirrored tokens: CSS variables for `.css` files, JS tokens for `createTheme`.
// For now, just accepts 2 sources of truth, but keep the token values consistent between them.

export const colorTokens = {
    primary: "#609966",
    primaryDark: "#40513B",
    primarySoft: "#9DC08B",
    surface: "#EDF1D6",
    shadowBrand: "rgba(64, 81, 59, 0.35)",
};

export const gradientTokens = {
    brand: `linear-gradient(135deg, ${colorTokens.primary} 0%, ${colorTokens.primaryDark} 100%)`,
};