---
title: Brand assets
nav_order: 30
---

# Brand assets

The dwarvenpick mark is a gold and copper pick with a diamond cutout and geometric handle inlays.

- `frontend/public/brand/mark-light.png`: transparent mark with charcoal inlays for light surfaces.
- `frontend/public/brand/mark-dark.png`: transparent mark with ivory inlays for dark surfaces.
- `frontend/public/brand/favicon-light.png` and `favicon-dark.png`: 32 px browser icons, selected by the application's theme.
- `frontend/public/brand/apple-touch-icon.png`: 180 px touch icon.
- `frontend/public/favicon.png`: 64 px fallback icon.

Login and workspace loading use the active application theme. The main navigation uses the dark-surface mark in both themes because its background stays dark. Keep the existing text wordmark separate so it remains readable at small sizes.

## Visual acceptance

1. Open `/login`. Toggle light and dark mode: the mark should have no white background, and the browser icon should update.
2. Sign in. Check the loading mark during navigation and the mark at the top of the workspace menu.
3. Collapse and expand the menu. The mark should stay centered and unclipped.
4. Switch themes from the user menu, then reload. The selected theme and corresponding assets should persist.
5. Repeat at a narrow viewport and browser zoom of 200%. Check that the mark does not overlap the wordmark or controls.

The PNG files are production derivatives of the approved logo, not editable vector originals.
