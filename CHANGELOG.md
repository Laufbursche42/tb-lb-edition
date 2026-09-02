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
- **Faults** - Trittbrett reports a small set of numeric fault bits. What each one means beyond its raw code is not documented, so the app lists the raw codes and does not invent an explanation.

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
