# BatteryLog — Progress Log

Newest entry first. Times are US Eastern.

## Resume point
- Branch **feat/redesign-2.0** (off main 0b7367d2). Work in progress: Compose rewrite + graphs + updater + fixes. Nothing released; Latest is still v1.6.0.
- Releases: dev builds = Actions artifacts / `-preN` prereleases only; stable only when the user says so. Install on a device only after the user's explicit yes.

## Lessons learned (don't repeat these)
- (none yet)

## 2026-11-16 ~18:30 EST — 2.0 redesign started (feat/redesign-2.0)
**Why:** user: "update the app and its UI, graphs and add any simple useful features that are currently missing … add an in-app updater … UI needs a nice overhaul to be more professional, fluid and informative … make the graphs a lot more fluid and somewhat fancy", then "go, do all of it" to the proposed plan.
**Plan (as proposed):**
1. Rebuild the UI in Jetpack Compose (Material 3, light/dark/black, own icon). Same app ID (`com.the412banner.batterylog.debug`) + AOSP testkey → installs over 1.6.0, logs kept.
2. Graphs: smooth curves + gradient fill, animated range changes, press-and-drag readout, pinch zoom, separate charts for %, power (W), current, voltage, temperature, charging periods shaded.
3. Home: animated battery ring, live W/mA/V/°C, time left / to full, "since last charge".
4. In-app updater (ported from Hint Plus: GitHub releases, SHA-256 + package check, PackageInstaller, 12 h check + notification, test-build channel).
5. Charge alarm + high-temp + low-battery alerts; quick settings tile; log tools (share, delete, auto-clean); settings (interval, °C/°F, theme); report as a clean page.
6. Fixes found 11-16 on the Elite (1S, 8400 mAh): SoH used the Pocket FIT 4139 mAh constant; "voltage sag" hard-coded for a 2S pack (false alarm on 1S); charge-speed slider treated a µA current node as a 0..max level (0 could stop charging); History parsed every log on the UI thread; boot sampler + app logged the same time twice.
**Built:** —
**CI:** —
**Device test:** —
