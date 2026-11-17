# BatteryLog — Progress Log

Newest entry first. Times are US Eastern.

## Resume point
- Branch **feat/redesign-2.0** (off main 0b7367d2). CI-green build 1611739d (CI 43984381077) being installed on the Elite (user: "install when done"). Nothing released; Latest is still v1.6.0. Next: device test by the user, fix findings, then v2.0.0-pre1 prerelease on the user's word.
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
**Built:** f49ec812 (full rewrite: Compose UI, charts, updater, alerts, tile, fixes; ~4.5k lines) — CI-only, no local SDK.
**CI:** 43983706747 (f49ec812) FAILED in compile: 2× `p.firstUptime` (field is `firstUptimeMs`). Fix 62da847a (+ notification Stop now saves mode Off, README 2.0, pre1 notes draft) → CI 43984108246 running.
**CI 43984108246 (62da847a) FAILED:** test compile only (ScreenshotTest Int/Double mix); app code compiled. Fix 1611739d → **CI 43984381077 GREEN** (headSha 1611739d): LogicTest 7/7, ScreenshotTest 10/10, APK BatteryLog-v1.6.0-1611739d.apk signed v1/v2/v3 testkey a40da80a, package com.the412banner.batterylog.debug vc8, target 36. Screenshots reviewed (Now dark/light/charging, History level/power/temp, Run, Health, Settings, Updates): all render.
**Backups before install (11-16 ~19:20 EST):** /sdcard/Download/BatteryLog-backup-20261116/ = copy of /sdcard/BatteryLog (12 logs, md5 list identical) + BatteryLog-v1.6.0-installed.apk (sha256 27f42733… = v1.6.0 release, cert a40da80a). Rollback = `pm install -r -d` that APK (same key).
**User ~19:10 EST:** "install when done" → install this build on the Elite once CI is green and screenshots look right (stage in /sdcard/Download with hash check first, logcat check after).
**Device test:** — (not installed; install only after the user's yes).
**Decisions (mine, changeable):** release build (R8, not debuggable) instead of the old debug build, same app ID `com.the412banner.batterylog.debug`; debug builds get `.dev` suffix; version stays 1.6.0/vc8 until the release cut; alerts run only while the service runs (Auto or Recording); tile tap opens the app; auto-delete default Never.
