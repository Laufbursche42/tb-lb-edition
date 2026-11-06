# Changelog

Notable changes to Laufbursche Edition (Trittbrett), newest first.

This history starts fresh with the Trittbrett port. The app was forked from a Laufbursche Edition written for a different make of scooter, and none of that release history describes what this app does, so none of it is carried over.

The version series lives in `version.properties`. `versionName` is `<major>.<minor>.<n>` where `n` is the release count: it advances by exactly one per push/release (bumped by the CI workflow), never by individual commits. `versionCode` never goes backwards.

Release notes are built automatically for each release: if this file has a section whose heading matches the released version it is used verbatim, otherwise the commit subjects since the previous release are listed. Either way a fixed Disclaimer and a "phoning home" note are appended (see `.github/release-footer.md`).

To hand-write the notes for a release, add a section headed with its version number at the top of the version list below, for example:

    ## 1.0.1
    - Fixed the light-mode toast readability
    - Corrected the cruise-control help text

If no matching section exists the notes fall back to the commit messages, so keeping this file up to date is optional.

## 1.0.27

- Quick-button labels are sized to fit by default (size 9) and can now be made larger - the per-theme size control in the Theme manager goes up to 35 (was 30).
- German button labels hyphenate at syllable boundaries, so long words wrap inside the button with a hyphen instead of being cut off, even at the largest size.

## 1.0.26

- Every dashboard text now has a working font-size control in the Theme manager, in every theme. The size controls are plus/minus steppers (no keyboard needed) and change the dashboard live. Now adjustable or newly effective: the GPS speed readout (separate from the VCU speed), ride-data values and labels, the All-values name and value, section headers, tile row 3, and the quick buttons (wider range). On the Default dashboard and the gauge themes the per-row tile knobs reach rows 2 and 3 too.
- The size editor groups the controls under clear headers (speedo & upper tiles / tile rows / buttons & more text) with clearer labels, so it is obvious what each knob changes.
- Quick buttons wrap long labels instead of clipping, so nothing overflows at large sizes.

## 1.0.25

- Theme settings now take effect in every theme. Font sizes, colours and spacing set in the Theme Manager now really bind on every theme - including the Default dashboard and the Lambo gauge, where the main speed readout and the live-value tiles ignored the font-size sliders before. Moving a slider now visibly changes the dashboard.
- Large system fonts no longer break the dashboard. On phones set to a very large system font or display size (e.g. OPPO/ColorOS, Samsung), the fixed cockpit layout is pinned to its own scale and long tile labels wrap inside the tile instead of being clipped, so nothing overflows.
- Internal security hardening (CodeQL): theme-import error handling no longer echoes exception detail to the dashboard, and the theme path check uses canonical-path containment. No user-facing change.

## 1.0.24

- New in-app Beginner's Guide for newcomers, reached from Settings (the "Beginner's Guide" entry just above Version Info & Disclaimer). A short, model-aware guide: how to connect over Bluetooth (close the official Trittbrett app first - it holds the single connection) and what your Trittbrett can do - the newer ZYD models (Sultan, Hilde, Fritz, Paul, Kalle v2, Emma v2) have a Bluetooth limiter toggle, while the older Kalle v1 and Emma v1 use an older stack with no speed command (lock/unlock only). It is honest that the app only sends the value - whether the scooter actually runs faster is up to its own controller firmware - and that nothing is flashed, so every change is reversible. The guide shares the same shell and wording across the Laufbursche apps, uses the standard button look and carries no forum link. Fully bilingual (EN/DE).

## 1.0.23

- Carbon and Lambo updated to theme version 5.2, and the Theme Manager now reliably shows each theme's real version - imported and re-seeded themes no longer lose it (it could appear as "1.0").

## 1.0.22

- Quick-control toggle buttons now use a smaller default font so long German labels no longer overflow or overlap, and the size is adjustable per theme (Settings → Display → Font sizes / Theme Manager).
- Shorter, clearer quick-button labels: the anti-theft toggle now reads "Lock" (German "Schloss").
- Speedometer scale presets. The gauge themes (Carbon, Lambo) now use evenly-spaced, readable marks with the needle pointing exactly at the number; pick the scale - Klar, Fine or Round - in the Theme Manager. Klar marks 0, 10, 15, 20, 22, 25, 30, 40 locked and up to 100 open; the locked scale still tops out at 40.
- Dark-mode text brightness now defaults to full (100) instead of 90.
- Security: in-app downloads (the app-update check and its files) are now restricted to the project's GitHub release hosts.
- The app is now named "TB Laufbursche Edition" on the home screen, so the Laufbursche apps for the different scooter makes are easy to tell apart.
- Language selector: the active language (EN/DE) is now clearly highlighted (filled, with a checkmark). It previously relied on an undefined colour, so the active button could render invisibly and the other one looked selected (GitHub #26).

## 1.0.21

The dashboard is now driver-rendered and fully customizable, with two built-in gauge themes - and the custom theme manager, full-screen themes and bottom navigation from the earlier unreleased work ship together here.

- **Driver-rendered, customizable dashboard.** Every live-value tile is now filled by your Trittbrett's own driver into a shared layout, so the app shows exactly the values your scooter reports - battery, gear, current, voltage, power, ESC / motor / battery temperature, trip, total, capacity, lock and faults - and hides the rest. Disconnected, the dashboard shows the full set with "-"; on connect it trims to what the connected model reports, and your own layout is remembered.
- **Pick what shows above the fold.** In the Theme Manager a "Live Values" list (between the fixed speed row and the scroll area) lets you choose, per theme, which value each tile shows - a dropdown per tile, just like the quick buttons - plus a tidy packed grid. Capacity is shown in amp-hours (Ah), and the choices also include connection signal strength and turn-signal status.
- **Manufacturer-neutral themes, driver-filled.** The quick-control bar and the live tiles are built from the app's own driver into empty skin containers, so the built-in Carbon and Lambo theme `.zip`s are byte-identical across the Laufbursche apps; each app fills them with its own scooter's values.
- **Two built-in gauge themes.** A carbon-fibre and a Lamborghini-style gauge theme now ship with the app, both driver-filled and tidily capped for the gauge layout (9 tiles / 8 buttons); the speed gauge marks 40-100 km/h dark red for unlocked scooters that reach those speeds.
- **Theme Manager.** Import a theme as a single `.zip` bundle (`theme.html` + `theme.css` + optional `theme.json`), then activate, export or delete it; a `"scope": "full"` theme replaces the whole dashboard, otherwise only the fold. Live `data-lb` hooks (`speed`, `soc`, `power`, `gear`) and `data-lb-state` classes bind live values, and a `data-lb-action="speed-unlock"` element re-arms the triple-tap unlock. Imported HTML is sanitised before storage - scripts, iframes, inline handlers, `javascript:`/`data:` URLs and external resources are stripped, so theme code never runs. The bundle format is shared across Laufbursche apps.
- **Configurable quick-button row + bottom navigation.** The quick toggles now cover everything the scooter can switch over Bluetooth - headlight, ambient light, kickstart, cruise-off, unit (km/mi), gear (D/T), lock and speed (eKFV / derestrict) - eight buttons in two rows of four, each reflecting its live on/off state. Show or hide them individually, reorder with a long press and align them; the menu can also move from the top-right corner to a bar across the bottom with the Settings button in the middle and a configurable shortcut on each side. Both appear only where the scooter supports them.
- **Per-row alignment.** Every dashboard row - tiles and quick buttons - can be aligned (centre by default, or left / right / fill) in the Theme Manager, saved per theme; a part-filled last row centres.
- **Offline theme guide.** A full bilingual (English / German) authoring guide - bundle format, every hook, the security model, translations and a worked example - is one tap away in the Theme Manager, built from a single Markdown source so the in-app guide and the PDFs always match.
- **Pin the recording banner.** The route-recording overlay can be docked to the top of the screen or left free-floating with an 8-pixel snap grid (Settings -> GPS).
- **Privacy: debug logs are scrubbed.** Personal data (MAC addresses, device names and IDs, your scooter's PIN, tokens and secrets) is removed from debug logs before they are exported or shared - always on, matching the websites. Useful troubleshooting data is kept.
- **Back button returns one level.** The on-screen back arrows and the Android back button now step back exactly one level (sub-page to Settings, Settings to dashboard) instead of sometimes jumping straight to the dashboard.
- **More device info + older models.** The info page now shows your Trittbrett's controller and display firmware versions, hardware, bootloader, unique code and controller model, plus the battery serial number. The older "Scooter"-protocol models now also report trip, total distance, battery, temperature and ride time.

## 1.0.20

- Display settings now has a Font sizes section: set the speed value and its label, the upper tiles and each lower tile row (Tile row 1 and Tile row 2) independently, each with its own range. Reset puts them all back to the built-in sizes.
- Report issue: the Version / disclaimer dialog has a new "Report issue" button that opens a pre-filled GitHub issue with the app version, Android version, phone model and your (already masked) debug log attached. No personal data is collected.

## 1.0.19

Fixed the speed lock/unlock write still failing on some scooters even after 1.0.18's connection fix (reported on a Hilde 1 and a Hilde 2): the three Bluetooth writes that make up a lock/unlock command were sent on a fixed 30 ms timer instead of waiting for each one to actually finish, and a background keepalive write could slip in between them - either could make the controller silently ignore the whole command, sometimes also freezing the live telemetry until an unrelated setting was touched. Both writes now wait for the real Bluetooth confirmation before continuing, matching how the web tool has always done it, and nothing else can write to the scooter while a lock/unlock command is in progress.

## 1.0.16

Internal only: every release from now on gets its own section here instead of falling back to raw commit subjects, and the in-app What's New list was brought up to date with the fixes since 1.0.7. No app behaviour changed.

## 1.0.15

Internal only: trimmed the CodeQL exclusion comments in `.github/codeql/codeql-config.yml` down to one or two lines each. No app behaviour changed.

## 1.0.14

Fixed the speed lock/unlock BLE write silently having no effect on some scooters (reported on a Hilde 2.0): the register-write burst that `setSpeed()` uses shared its Bluetooth characteristic object with the idle keepalive writes, and inherited whatever write type (with/without response) the keepalive had last set instead of always requesting one explicitly. It now always writes with response for that burst, matching what the web tool (tb-unlock) already did.

## 1.0.13

Internal only: four more CodeQL findings (path handling, the dashboard's JS bridge, log lines, register-write arithmetic) reviewed and documented as already covered by existing guards (`PathGuard`, `jsQuote`, `logSafe`, the 16-bit register mask) rather than fixed one alert at a time. No app behaviour changed.

## 1.0.12

Internal only: documented why the in-app updater's CodeQL finding is a false positive (the download URL is host-allowlisted and installing still needs your confirmation). No app behaviour changed.

## 1.0.11

The Scooter info page now also shows the controller's "Uniquecode" string alongside Model/Hardware/Bootloader/Firmware - it was being received but never parsed out due to an off-by-one in where the five ESC-info strings were split.

## 1.0.10

Added a turn-signal (left/right) indicator to the live dashboard - the scooter was already reporting this, it just was not read or shown anywhere yet.

## 1.0.9

Fixed the capacity tile on the Scooter info page: the "used" capacity value was actually the *remaining* capacity misread from the wrong field name since the Trittbrett port. The number shown was always correct, only its label was backwards.

## 1.0.8

Fixed a Bluetooth device address going into the log unsanitised (every other logged value already was). Fixed the speed register write accepting any number without enforcing the app's own documented 60 km/h ceiling. Also reviewed and documented two CodeQL findings about the dashboard's JavaScript bridge as inherent to a single, navigation-locked local WebView.

## 1.0.18

Fixed a Bluetooth connection race that could leave the scooter refusing throttle input (reported on a Hilde 2.0): after connecting, the app requested a larger BLE MTU and started discovering services on a fixed timer at the same time, instead of waiting for the MTU request to actually finish first. On some phones the two overlapping Bluetooth operations could interfere with each other, especially on reconnect. Service discovery now waits for the MTU negotiation to complete.

## 1.0.7

Real-hardware bug report fixes (Hilde 2.0):

- Fixed the speed lock/unlock drum getting stuck showing the same state regardless of what you tapped: it was being re-derived every telemetry frame from an unrelated per-gear limit the app never writes, instead of only changing on your own tap. It is local memory again now, matching what the register it writes has no readback for.
- Fixed the km/h reading showing a tenth of the real speed on scooters that report the `thousandUnitsEnable` control bit: the extra x100 correction the manufacturer app applies for that bit is now applied here too.
- The Scooter settings "Lock" row is now labelled "Vehicle lock" - it is the immobiliser, a separate feature from the speed unlock, and the shared wording was confusing the two.

## 1.0.30

(Renumbered from a placeholder "1.0.18" heading that predated the run of releases above and would
otherwise collide with the real, sequential release count - moved out of the way to a number well past
the current run instead of guessing the exact number this content actually shipped under.)

LEAT-compatible ride exports. The desktop tool [LEAT](https://github.com/Laufbursche42/leat) now reads every export of this app directly:

- Ride log lines additionally carry the canonical field names LEAT is hard-wired to (`realSpeed`, `SOC`, `VolPack`, `singleMile`, `totalMile`, `rMotorTemp`) and record `power` in kW instead of W. The live dashboard keeps its own keys and units.
- The JSON export is a bare top-level array of samples instead of a `{meta, samples}` wrapper object.
- The CSV export no longer starts with a UTF-8 BOM and no longer contains a `tsISO` column, so LEAT recognises the `ts` time axis.
- The GPX export writes `<speed>` in m/s as the GPX convention expects, instead of km/h.
- Fixed the ride list and export metadata always reporting a distance of 0 km: the odometer was read under a key the app never wrote.

## 1.0.0

The first Trittbrett build.

The app now speaks to a Trittbrett scooter and to nothing else. It is a feasibility study, not a finished product, and it comes with no warranty. Trittbrett is a trademark of its owner and is used here descriptively: this is not an official Trittbrett app and it is not affiliated with, endorsed by or connected to Trittbrett.

### Two Bluetooth protocol families

Trittbrett scooters split into two independent families, told apart by the scooter's advertised Bluetooth name:

- **ZYD family** (FRITZ, PAUL, SULTAN, HILDE and newer KALLE/EMMA units) - the full protocol: live telemetry, the full settings surface, an AT command channel for the name and sound selection, and a Bluetooth speed command.
- **Legacy family** (older KALLE/EMMA units, advertised simply as "Scooter") - speed and volt only, a gear switch and lock/unlock. No Bluetooth speed command exists on this generation.

The app detects which family a scooter speaks from its advertised name and, if that is inconclusive, from which Bluetooth service the scooter actually offers.

### Scooter settings

Each ZYD setting is written on its own command and carries only what was touched:

- **Lights** - headlight and ambient lighting.
- **Ride** - gear (D/T), zero-start, cruise-off, km/h/mph, the cruise-speed cap and the throttle acceleration/brake curves.
- **System** - cruise timeout, auto-shutdown timer, wheel size, carrier weight class, service interval and lock/unlock - the last four are motor/config parameters and ask for confirmation before writing.
- **Motor** - modulation depth, pole pairs, discharge/brake current limits and the under-voltage cutoff - all confirm before writing.
- **Sound** - the start, shutdown, horn and alarm sound selection.
- **Name** - the Bluetooth advertised name.

On the Legacy family only the gear switch and lock/unlock exist; the settings surface for everything else is hidden rather than shown disabled.

### In-app updates

Unlike some sibling ports of this app, the in-app updater was kept: a banner in the Settings menu appears when a newer version is available, downloads the APK to your Downloads folder and opens the Android installer, so you confirm the install yourself.

### What the pages read

- **Dashboard and telemetry** - speed, battery percentage, voltage, current, power, controller and motor temperature, trip and total distance, battery temperature, capacity used/total, fault codes and the firmware/display version, all from the ZYD monitor frames and the one-time controller-info read.
- **Faults** - Trittbrett reports a small set of fault codes. Each one shows its meaning from the official Trittbrett FAQ (trittbrett.eu/faq), documented there for KALLE, EMMA, PAUL, SULTAN and FRITZ; nothing is invented beyond that source.

### Taken out

Everything below was in the app this one was forked from and is gone. None of it applies to a Trittbrett scooter, and shipping a control that quietly does nothing is worse than not shipping it:

- Firmware flashing over Bluetooth, the whole update protocol behind it and every controller firmware file.
- Per-gear profile editing beyond the three fixed gear speed limits Trittbrett actually reports.
- Dual-motor and motor-mode switches. A Trittbrett scooter has one motor.
- Per-cell battery voltages and the BMS detail pages built around them.
- Every model name and setting of the other make.

### Kept

Live dashboard, GPS ride recording with GPX export, offline navigation on Mapsforge maps with BRouter bicycle routing, the ride log with CSV and JSON export, SRT screen streaming, the in-app APK updater, the debug log, the dark and light themes and the English and German interface.

### Identity

The application id is `com.laufbursche.tblbedition` and the app is called **tb-lb-edition**. It needs Android 10 (minSdk 29).
