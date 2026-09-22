---
title: Brand assets
nav_order: 30
---

# Brand assets

The dwarvenpick mark is a gold and copper pick with a diamond cutout and geometric handle inlays.

- `frontend/public/brand/mark-light.png`: transparent gold/copper mark with black inlays, used in both themes.
- `frontend/public/brand/favicon-light.png`: 32 px browser icon, used in both themes.
- `frontend/public/brand/apple-touch-icon.png`: 180 px touch icon.
- `frontend/public/favicon.png`: 64 px fallback icon.
- `frontend/public/brand/auth-background.jpg`: optimized fortress artwork shared by login and workspace loading in both themes.

Login, workspace loading, main navigation and browser icons all use the same black-detail artwork regardless of theme. Keep the existing text wordmark separate so it remains readable at small sizes.

## Visual acceptance

1. Open `/login`. Toggle light and dark mode: the mark should have no white background, and the logo and browser icon should retain their black details.
2. Sign in. Check the loading mark during navigation and the mark at the top of the workspace menu.
3. Collapse and expand the menu. The mark should stay centered and unclipped.
4. Switch themes from the user menu, then reload. The selected theme should persist and the logo should remain unchanged.
5. Repeat at a narrow viewport and browser zoom of 200%. Check that the mark does not overlap the wordmark or controls.

The PNG files are production derivatives of the approved logo, not editable vector originals.

## Sign-in appearance and behavior

Login and workspace loading share a centered, responsive 420 px card over the same background. Short viewports scroll instead of clipping the form. The SSO button uses a gold background and dark text in both themes.

The password visibility button only reveals the current input temporarily; submitting masks it again. Passwords are never stored in browser storage.

For local and LDAP password sign-in, **Remember this device** is off by default. When selected, the HttpOnly session cookie survives browser restarts for at most the configured session timeout from sign-in (8 hours by default via `DWARVENPICK_SESSION_TIMEOUT`). Server-side expiration still applies, and logout invalidates the session. This does not extend the timeout or bypass authentication. Only use it on a trusted device. SSO persistence remains controlled by the identity provider; the checkbox applies to password sign-in only.

For UAT, open `/login` in both themes, check the background and gold SSO button, toggle password visibility, then sign in with and without remembering the device. Check the loading background during sign-in and confirm logout requires authentication again. Repeat at a narrow and short viewport.
