# Implement theme switcher in React

I’ll wire a multi-palette system with persistence, then add a simple selector in the chat UI so users can switch themes from settings-like controls.

Users can choose from multiple palettes at runtime, and the choice is saved in localStorage and restored on reload.

What was added:

- Theme preset system with 3 palettes (Forest, Amethyst, Ocean) in tokens.js
- Runtime CSS variable application and dynamic MUI theme generation in App.js
- Theme selector dropdown in the sidebar UI in Sidebar.js
- Prop wiring for selector state from app to chat page in ChatPage.js

How it works now:

1. App loads selected theme ID from localStorage.
2. It resolves that palette into:
   - MUI palette tokens for createTheme
   - Full CSS variable map for your existing CSS files
3. It applies CSS variables to :root at runtime.
4. User can switch theme from the sidebar Theme dropdown.
