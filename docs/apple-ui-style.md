# Apple-inspired UI system

DBStudio uses an Apple web and Liquid Glass inspired visual language while retaining compact IDE density. The design is shared by macOS and Windows and does not imitate native window controls.

## Layering

- Glass is limited to the top toolbar, dialogs, drawers and popovers.
- Monaco, object data and query result tables remain on opaque content surfaces.
- `backdrop-filter` is progressive enhancement. Supported browsers enable it through `@supports`; reduced-transparency preferences and unsupported browsers use an opaque material with the same hierarchy.
- `prefers-reduced-transparency` always restores opaque surfaces.

## Appearance contract

- `ui.theme` accepts `system`, `light` or `dark`; missing and legacy values resolve to `system`.
- `ThemePreference` stores the user's choice and `ResolvedTheme` drives Element Plus, Monaco and `color-scheme`.
- `prefers-color-scheme` changes are applied live while the preference is `system`.
- `prefers-reduced-motion` reduces all nonessential animation and transition durations.

## Component rules

- Element Plus remains the first choice for every common control.
- Only Execute uses the primary blue treatment in the main toolbar.
- Icon-only actions require a tooltip and an accessible label.
- Status changes use an icon or shape plus text; color is never the only signal.
- Controls use 30–32 px heights, 8–10 px control radii, 12 px panel radii and 16 px modal radii.

## Development compatibility page

Run `npm run dev -- --port 4173` and open `http://127.0.0.1:4173/?mock=1`. The mock bridge is compiled only in Vite development mode and provides saved connections, metadata, history and a 200-row multilingual result set. Production builds never activate it.
