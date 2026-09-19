# Changelog

Notable changes to Laufbursche Edition (Trittbrett), newest first.

This history starts fresh with the Trittbrett port. The app was forked from a Laufbursche Edition written for a different make of scooter, and none of that release history describes what this app does, so none of it is carried over.

The version series lives in `version.properties`, which both the gradle build and the release workflow read. `versionName` is `<major>.<minor>.<n>` where `n` counts the released versions in this series, so the number rises by one on every release with no manual editing. `versionCode` counts straight through a series change and never goes backwards.

Release notes are built automatically for each release: if this file has a section whose heading matches the released version it is used verbatim, otherwise the commit subjects since the previous release are listed. Either way a fixed Disclaimer and a "phoning home" note are appended (see `.github/release-footer.md`).

To hand-write the notes for a release, add a section headed with its version number at the top of the version list below, for example:

    ## 1.0.1
    - Fixed the light-mode toast readability
    - Corrected the cruise-control help text

If no matching section exists the notes fall back to the commit messages, so keeping this file up to date is optional.

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

## 1.0.7

Real-hardware bug report fixes (Hilde 2.0):

- Fixed the speed lock/unlock drum getting stuck showing the same state regardless of what you tapped: it was being re-derived every telemetry frame from an unrelated per-gear limit the app never writes, instead of only changing on your own tap. It is local memory again now, matching what the register it writes has no readback for.
- Fixed the km/h reading showing a tenth of the real speed on scooters that report the `thousandUnitsEnable` control bit: the extra x100 correction the manufacturer app applies for that bit is now applied here too.
- The Scooter settings "Lock" row is now labelled "Vehicle lock" - it is the immobiliser, a separate feature from the speed unlock, and the shared wording was confusing the two.

## 1.0.18

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
