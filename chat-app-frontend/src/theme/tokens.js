// The tradeoff is important: with plain CSS plus JS, you still have two styling systems.
// If you want a true single source of truth for both CSS files and MUI theme values, 
// the next step would be one of these:
// 1. Generate the CSS variables from the JS token file at build time.
// 2. Move more of the styling into MUI/theme-driven components and reduce plain CSS.
// 3. Accept mirrored tokens: CSS variables for `.css` files, JS tokens for `createTheme`.
// For now, just accepts 2 sources of truth, but keep the token values consistent between them.

export const colorTokens = {
    primary: "#667eea",
    primaryDark: "#764ba2",
    primarySoft: "#4c51bf",
    surface: "#ffffff",
    shadowBrand: "rgba(31, 38, 135, 0.37)",
};

export const gradientTokens = {
    brand: `linear-gradient(135deg, ${colorTokens.primary} 0%, ${colorTokens.primaryDark} 100%)`,
};