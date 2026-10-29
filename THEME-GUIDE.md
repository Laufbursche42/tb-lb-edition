# Custom Theme Guide  -  Laufbursche Edition

Custom themes let you replace the fold area (the top section of the main dashboard) with your own HTML layout. You supply HTML for the structure and CSS for the look; the app fills your elements with live scooter data automatically, every 500 ms, without any JavaScript on your side.

---

## Table of contents

- [What a theme is and is not](#what-a-theme-is-and-is-not)
- [The bundle format](#the-bundle-format)
- [theme.html  -  structure and hooks](#themehtml--structure-and-hooks)
  - [Live value hooks  -  data-lb](#live-value-hooks--data-lb)
  - [State hooks  -  data-lb-state](#state-hooks--data-lb-state)
  - [Action hooks  -  data-lb-action](#action-hooks--data-lb-action)
  - [Tile registration  -  data-tile-id](#tile-registration--data-tile-id)
  - [Available data keys](#available-data-keys)
- [theme.css  -  layout and variables](#themecss--layout-and-variables)
  - [App-provided CSS variables](#app-provided-css-variables)
- [theme.json  -  metadata](#themejson--metadata)
- [Security model  -  no JavaScript](#security-model--no-javascript)
- [How to import](#how-to-import)
- [Portability between Laufbursche apps](#portability-between-laufbursche-apps)
- [Translations and language](#translations-and-language)
- [Complete minimal example](#complete-minimal-example)
- [Tips and limitations](#tips-and-limitations)

---

## What a theme is and is not

A custom theme **replaces the HTML inside `#fold-inner`**  -  the part of the main screen that sits above the scroll area. You control layout, typography and colours with your own HTML and CSS. The app keeps driving everything else: the scroll tiles below, the quick-action button row, the recording banner and all the sub-pages.

**A theme is not a web app.** It cannot run JavaScript, load external resources or change the app's logic. It is structure and style only, with data fed in by the app.

---

## The bundle format

A theme is a **ZIP file** containing exactly three files at the root level of the archive:

| File | Required | Purpose |
|------|----------|---------|
| `theme.html` | **Yes** | HTML fragment injected into `#fold-inner` |
| `theme.css` | **Yes** | CSS loaded for this theme (scoped to your HTML) |
| `theme.json` | No | Metadata: display name, version, optional CSS-variable declarations |

The ZIP filename becomes the import fallback name if `theme.json` has no `name` field.

**Size limit:** 2 MB per file.

---

## theme.html  -  structure and hooks

`theme.html` is a plain HTML fragment  -  **no `<html>`, `<head>` or `<body>` tags**, just the content you want inside `#fold-inner`. The app sanitizes it before storing (see [Security model](#security-model--no-javascript)).

### Live value hooks  -  data-lb

Add `data-lb="key"` to any element. The app sets that element's `textContent` to the current scooter value every 500 ms:

```html
<div class="my-speed" data-lb="speed"></div>
<div class="my-soc"   data-lb="soc"></div>
```

Values are numbers (or `true`/`false` for booleans). Format them with CSS (`content` on `::after` for units, or just text next to the value). If the scooter is disconnected, the last known value stays until the next connection.

### State hooks  -  data-lb-state

Add `data-lb-state="key:classname"` to toggle a CSS class based on whether a data value is truthy:

```html
<div class="speed-box" data-lb-state="park:is-parked">
```

When `data.park` is `true`, the class `is-parked` is added; when `false`, it is removed. Use this for visual state changes (different background when parked, flashing when locked, etc.).

### Action hooks  -  data-lb-action

One built-in action is supported:

| Value | Behaviour |
|-------|-----------|
| `speed-unlock` | Triple-tap this element to trigger the eKFV speed-unlock sequence (same as the default speed tile). |

```html
<div class="my-speed" data-lb="speed" data-lb-action="speed-unlock"></div>
```

### Tile registration  -  data-tile-id

Wrap sections of your theme in an element with `data-tile-id` and `data-tile-label` to register them with the tile manager. Registered tiles appear in the theme manager's tile list, can be shown or hidden, and can be drag-reordered by the user:

```html
<div data-tile-id="my-speed-block" data-tile-label="Tacho">
  <!-- speed content -->
</div>
<div data-tile-id="my-motor-block" data-tile-label="Motoren">
  <!-- motor content -->
</div>
```

Without `data-tile-id`, a section is always shown and cannot be managed.

### Available data keys

All keys come from the scooter's BLE telemetry, parsed every 500 ms. Raw numbers are `0` when the scooter is disconnected; `park` is `false`.

| Key | Type | Unit | Description |
|-----|------|------|-------------|
| `speed` | number | km/h or mph | Current speed |
| `avgSpeed` | number | km/h or mph | Average speed (this ride) |
| `maxSpeed` | number | km/h or mph | Maximum speed (this ride) |
| `soc` | number | % | State of charge |
| `soh` | number | % | State of health |
| `volt` | number | V | Pack voltage |
| `poleVolt` | number | V | Pole voltage |
| `current` | number | A | Pack current (null when not reported) |
| `gear` | number |  -  | Active gear (1-3) |
| `rearCurrent` | number | A | Rear motor current |
| `frontCurrent` | number | A | Front motor current |
| `rTemp` | number | °C | Rear motor temperature |
| `fTemp` | number | °C | Front motor temperature |
| `power` | number | kW | Total power |
| `singleMile` | number | km or mi | Trip distance |
| `totalMile` | number | km or mi | Odometer |
| `fb` | number |  -  | Feedback/regen value |
| `rssi` | number | dBm | Bluetooth signal strength |
| `park` | boolean |  -  | `true` when scooter is in park |
| `vcuUnlock` | number |  -  | `0` = locked, `1` = unlocked, `-1` = unknown |

**Unit note:** `speed`, `avgSpeed`, `maxSpeed`, `singleMile` and `totalMile` automatically follow the app's km/mph setting  -  the number you receive is already in the user's chosen unit.

**Value format:** all numbers are raw JavaScript floats, e.g. `speed` = `25.3`, `power` = `0.24`. There is no formatting control without JavaScript. If you need an integer display, CSS cannot round numbers; design your layout so a one-decimal value fits (wider container, or simply accept the precision the scooter reports).

---

## theme.css  -  layout and variables

`theme.css` is loaded as a scoped stylesheet after the app's own CSS. Write selectors that target your own class names, or element IDs you defined in `theme.html`.

You do **not** need to set colours or fonts from scratch. Use the app's CSS variables and your theme automatically adapts to dark mode, light mode and any per-theme style the user has configured.

### App-provided CSS variables

These variables are always defined on `:root` by the app:

**Colours**

| Variable | Description |
|----------|-------------|
| `--card` | Tile / card background |
| `--bg` | Page background |
| `--text` | Primary text |
| `--label` | Secondary / muted text |
| `--value` | Accent / highlight colour (user-configurable) |
| `--ble` | BLE speed indicator colour |
| `--gps` | GPS speed indicator colour |
| `--good` | Positive / OK state colour |
| `--warn` | Warning colour |
| `--danger` | Error / critical colour |
| `--unlock` | "Unlocked / entdrosselt" indicator colour |
| `--btnfill` | Button fill colour |
| `--border` | Border / divider colour |

**Typography**

| Variable | Default | Description |
|----------|---------|-------------|
| `--fs-2xs` | 10 px | Smallest text (badges, tags) |
| `--fs-xs` | 11 px | Extra-small text |
| `--fs-sm` | 12 px | Small text |
| `--fs-md` | 13 px | Medium text |
| `--fs-base` | 14 px | Base body text |
| `--fs-xl` | 16 px | Large text |
| `--fw-semibold` | 600 | Semibold weight |
| `--fw-bold` | 700 | Bold weight |

**Speed-tile font sizes** (user-configurable via Style Editor)

| Variable | Description |
|----------|-------------|
| `--sz-speed-val` | Speed number |
| `--sz-speed-lbl` | Speed label |
| `--sz-hero-val` | Hero tile value |
| `--sz-hero-lbl` | Hero tile label |
| `--sz-m1-mid` | Motor tile 1 value |
| `--sz-m2-mid` | Motor tile 2 value |

**Shape**

| Variable | Default | Description |
|----------|---------|-------------|
| `--radius` | 12 px | Standard border radius |

---

## theme.json  -  metadata

Optional. If present, it must be valid JSON:

```json
{
  "name": "My Theme",
  "version": "1.0"
}
```

| Field | Description |
|-------|-------------|
| `name` | Display name shown in the app's theme list. Falls back to the ZIP filename if absent. |
| `version` | Free-form version string, informational only. |

---

## Security model  -  no JavaScript

**JavaScript does not run in themes.** This is enforced in two layers:

**Layer 1  -  import-time sanitizer (Java, `ThemeManager.sanitizeHtml`).**
Applied when the ZIP is imported and before the HTML is stored. The following are stripped unconditionally:
- `<script>` tags and their content
- `<iframe>` tags and their content (can embed arbitrary documents)
- `<base>` tags (would change relative URL resolution)
- All inline event handlers (`onclick=`, `onerror=`, `onload=`, etc.)
- `javascript:` URIs in `href`, `src`, `action` and `formaction` attributes
- `data:` URIs in `src` and `href` (can carry HTML or JS payloads)
- External `http://` and `https://` URLs in `src` and `href`

**Layer 2  -  load-time sanitizer.**
The same sanitizer runs again when the theme is read from disk and injected into the page, so stored themes that pre-date a sanitizer update are re-checked on every load.

**Why scripts would not work even without the sanitizer:** `<script>` tags injected via `innerHTML` are not executed by modern browsers and Android WebView. The sanitizer exists as defence-in-depth, not as the only line of protection.

**What you can do instead of JavaScript:**
- Use `data-lb-state` for conditional CSS classes.
- Use CSS `@keyframes`, transitions and custom properties for animation.
- Use `data-lb-action` for the built-in speed-unlock tap.

---

## How to import

### Step 1  -  Create the ZIP

All three files must be **at the root of the archive** (not inside a subfolder). The app looks for `theme.html`, `theme.css` and `theme.json` by filename regardless of path, so a subfolder also works, but root is cleaner.

**Windows:** select all three files in Explorer, right-click → "Compress to ZIP file" (Windows 11) or "Send to → Compressed (zipped) folder".

**macOS / Linux terminal:**
```
zip my-theme.zip theme.html theme.css theme.json
```

**Android (for quick testing):** use a file manager app that supports ZIP creation (e.g. MT Manager, ZArchiver).

### Step 2  -  Import

1. Transfer the ZIP to your phone (AirDrop, cable, cloud drive, etc.).
2. In the app: **Settings → Display → Theme manager → Import theme**.
3. Pick the ZIP from your file browser.
4. The theme appears in the list; tap **Activate** to apply it.
5. A **Theme → Style** button opens the Style Editor, where the user can override colours, brightness and font sizes on top of your theme.

To export a theme you already imported (for sharing or backup): **Theme manager → (tap the theme) → Export**.

---

## Portability between Laufbursche apps

The ZIP bundle format and the `data-lb` attribute names are a **shared API across all Laufbursche apps**. A theme built for one app can be imported into any other Laufbursche app that supports custom themes.

When building a theme meant to be used across multiple apps:
- Use only the `data-lb` keys listed in [Available data keys](#available-data-keys)  -  these are the stable cross-app set.
- Use only the CSS variables listed in [App-provided CSS variables](#app-provided-css-variables).
- Do not rely on fixed IDs or classes that belong to one app's own HTML (like `#speed-row` from the default layout).

Apps may expose additional app-specific keys or CSS variables beyond this list; check each app's own documentation.

---

## Translations and language

The app runs in German or English. When you switch languages the app re-runs the full i18n pass over the entire document, including your injected theme HTML.

### Using existing app strings

Add `data-i18n="key"` to any element and the app replaces its text content with the translated string at runtime. The fallback text between the tags is shown in a browser during development:

```html
<span data-i18n="dash.hero.gear.label">Gear</span>
```

You can only reference keys that already exist in the app's built-in i18n table. Useful built-in keys for theme elements:

| Key | DE | EN |
|-----|----|----|
| `dash.hero.gear.label` | Gang | Gear |
| `dash.hero.battery.unit` | Batterie % | Battery % |
| `dash.motors.section` | Motoren | Motors |
| `dash.motor.rear.title` | HINTEN | REAR |
| `dash.motor.front.title` | VORNE | FRONT |
| `dash.motor.power.title` | LEISTUNG | POWER |
| `dash.speed.vcu.unit` | km/h - VCU | km/h - VCU |
| `dash.speed.gps.unit` | km/h - GPS | km/h - GPS |

`data-i18n-aria`, `data-i18n-title` and `data-i18n-alt` work the same way for `aria-label`, `title` and `alt` attributes.

### Adding your own translated strings

Declare a `strings` block in `theme.json`. The app merges these into the global i18n table when your theme loads, so they work exactly like built-in keys:

```json
{
  "name": "My Theme",
  "version": "1.0",
  "strings": {
    "de": {
      "mtheme.speed.label": "Tempo",
      "mtheme.gear.label":  "Gang",
      "mtheme.unit.kmh":    "km/h"
    },
    "en": {
      "mtheme.speed.label": "Speed",
      "mtheme.gear.label":  "Gear",
      "mtheme.unit.kmh":    "km/h"
    }
  }
}
```

Then in `theme.html`:

```html
<span class="lbl" data-i18n="mtheme.speed.label">Speed</span>
<span class="val" data-lb="speed">-</span>
<span class="unit" data-i18n="mtheme.unit.kmh">km/h</span>
```

**Key naming:** prefix your keys with something unique to your theme (e.g. the theme name) to avoid collisions with built-in keys or other themes. A missing key falls back to the fallback text you wrote between the tags, so the interface stays readable even if a key is undefined.

**One language only:** if your theme targets a single audience, just hardcode the labels directly in the HTML without `data-i18n`. That is perfectly fine and keeps the bundle simpler.

---

## Complete minimal example

### theme.json
```json
{
  "name": "Minimal",
  "version": "1.0"
}
```

### theme.html
```html
<div class="mn-wrap" data-tile-id="mn-main" data-tile-label="Dashboard">
  <div class="mn-speed-row">
    <span class="mn-speed-val" data-lb="speed" data-lb-action="speed-unlock">-</span>
    <span class="mn-speed-unit">km/h</span>
  </div>
  <div class="mn-row">
    <div class="mn-cell">
      <span class="mn-lbl">SOC</span>
      <span class="mn-val" data-lb="soc">-</span>
      <span class="mn-unit">%</span>
    </div>
    <div class="mn-cell">
      <span class="mn-lbl">Gang</span>
      <span class="mn-val" data-lb="gear">-</span>
    </div>
    <div class="mn-cell">
      <span class="mn-lbl">Spannung</span>
      <span class="mn-val" data-lb="volt">-</span>
      <span class="mn-unit">V</span>
    </div>
  </div>
  <div class="mn-row">
    <div class="mn-cell" data-lb-state="park:mn-parked">
      <span class="mn-lbl">Hinten °C</span>
      <span class="mn-val" data-lb="rTemp">-</span>
    </div>
    <div class="mn-cell">
      <span class="mn-lbl">Vorne °C</span>
      <span class="mn-val" data-lb="fTemp">-</span>
    </div>
    <div class="mn-cell">
      <span class="mn-lbl">Power</span>
      <span class="mn-val" data-lb="power">-</span>
      <span class="mn-unit">kW</span>
    </div>
  </div>
</div>
```

### theme.css
```css
.mn-wrap {
  padding: 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--card);
  border-radius: var(--radius);
}

.mn-speed-row {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 6px;
}
.mn-speed-val {
  font-size: 64px;
  font-weight: var(--fw-bold);
  color: var(--value);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}
.mn-speed-unit {
  font-size: var(--fs-md);
  color: var(--label);
}

.mn-row {
  display: flex;
  gap: 8px;
}
.mn-cell {
  flex: 1;
  background: var(--bg);
  border-radius: 8px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.mn-cell.mn-parked {
  border: 1px solid var(--warn);
}
.mn-lbl {
  font-size: var(--fs-xs);
  color: var(--label);
}
.mn-val {
  font-size: 24px;
  font-weight: var(--fw-bold);
  color: var(--text);
  font-variant-numeric: tabular-nums;
}
.mn-unit {
  font-size: var(--fs-xs);
  color: var(--label);
}
```

---

## Tips and limitations

- **`innerHTML` injection.** Your HTML replaces the entire content of `#fold-inner`. The app re-injects it on every theme activation and page reload. Do not reference IDs from the default layout (like `#speed-row`)  -  they will not exist.
- **No external images.** `http://` and `https://` URLs are stripped from `src` attributes. Use CSS `background-color` and SVG shapes for icons and graphics.
- **No custom fonts from the web.** Stick to the system font stack or inline a base64 font-face (keep file size in mind  -  2 MB limit).
- **The scroll area is unchanged.** Your theme only covers `#fold-inner`. The tiles below it (grid-vehicle, grid-gear, etc.) are always present and keep working.
- **CSS load order.** `theme.css` is loaded as `active-theme.css` right after `dashboard.css` in the page `<head>`. Your selectors win over app rules at equal specificity. Use your own class names (prefix them) to avoid accidentally changing app UI outside `#fold-inner`.
- **Content Security Policy.** The page enforces `style-src file:` - only local files are allowed. External CSS, Google Fonts and icon CDNs are blocked at the browser level before the sanitizer even runs. Inline font-face with base64 data works; external font URLs do not.
- **Dark / light mode.** Do not hardcode colours  -  use `var(--card)`, `var(--text)`, etc. so the theme works in both modes automatically.
- **Testing.** Open `telemetry.html` directly in a desktop browser (Chrome / Firefox). The `LB` bridge is absent so the theme CSS and HTML do not load from storage, but you can: (a) paste your HTML into `#fold-inner` via DevTools, (b) manually inject a `<link>` pointing to your local `theme.css`, (c) add a `<script>` that fills `data-lb` elements with dummy values  -  that script is only for browser testing and must be removed before zipping.
- **Tile manager sync.** If you add `data-tile-id` elements, their labels appear in the theme manager. Keep labels short (under 20 characters) so the list stays readable.
