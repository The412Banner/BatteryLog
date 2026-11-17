# BatteryLog — Progress Log

Newest entry first. Times are US Eastern.

## Resume point
- **v2.0.0 STABLE = Latest** (published 2026-11-16 19:30:20 EST, tag → 21cc13320fa02bdbbd69157d269441b4e2e86a16 = release run 43987268286 headSha); assets BatteryLog-v2.0.0-21cc1332.apk (sha256 b7917a08…, = .sha256, AOSP testkey a40da80a, vc 9) + .sha256; notes = release-notes/v2.0.0.md. main = 21cc1332 (+ log commits); backup refs/backup/20261116/main-before-redesign-2.0 = 0b7367d2.
- NOW: updating the Elite 1611739d (reads 1.6.0) → v2.0.0 through the app's Settings → Updates (user asked). Rollback APKs: /sdcard/Download/BatteryLog-backup-20261116/.
- Releases: dev builds = Actions artifacts / `-preN` prereleases only; stable only when the user says so. Install on a device only after the user's explicit yes.

## Lessons learned (don't repeat these)
- (none yet)

## 2026-11-16 ~19:20 EST — v2.0.0 stable release cut (feat/redesign-2.0 → main)
**Why:** user (19:17 EST): "release as stable 2.0.0", then "also update the repo read me with app screenshots included".
**Device state before the cut:** 1611739d installed on the Elite 19:09:59; the user opened it 19:15:23 (Displayed +98 ms); crash buffer empty, no FATAL/ANR through 19:16:52. Proof level of the redesign: CI-green + installed + opened by the user without a crash; features not walked through on the device by me.
**Release-cut commit:** versionCode 8→9, versionName 1.6.0→2.0.0; release-notes/v2.0.0.md (was the pre1 draft); README gets a 3×3 screenshot grid (docs/screenshots/*.png, cropped from CI 43984381077 ScreenshotTest renders, sample data, labelled as such). Release notes carry no images (the in-app Updates screen shows notes as text).
**Done so far:** CI 43986574675 GREEN on 21cc1332 → backup ref refs/backup/20261116/main-before-redesign-2.0 = 0b7367d2 (pushed) → main fast-forwarded to 21cc1332 (tree == tested build) → push build 43987258095 + release run 43987268286 (workflow_dispatch, headSha 21cc1332, tag v2.0.0, prerelease=false) running.
**User ~19:30 EST:** "update it through the app when it's released" → after the release: open BatteryLog on the Elite, Settings → Updates → Download & install (announce takeover, save first, Termux back after).
**RELEASED 19:30:20 EST:** v2.0.0 Latest, not prerelease; tag == 21cc1332 == built commit; asset sha256 b7917a088def… matches .sha256; signer a40da80a; notes == file (trailing newline only).
**Plan:** CI green on feat → backup ref refs/backup/20261116/main-before-redesign-2.0 (= 0b7367d2) → FF main → workflow_dispatch release on main (tag v2.0.0, not prerelease) → verify tag == built commit, assets (APK + .sha256), notes, Latest. Then the installed 1611739d build (reads 1.6.0) can update to v2.0.0 through the in-app updater = first real updater test.

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
**Backups before install (11-16 ~19:05 EST):** /sdcard/Download/BatteryLog-backup-20261116/ = copy of /sdcard/BatteryLog (12 logs, md5 list identical) + BatteryLog-v1.6.0-installed.apk (sha256 27f42733… = v1.6.0 release, cert a40da80a). Rollback = `pm install -r -d` that APK (same key).
**User ~19:00 EST:** "install when done" → install this build on the Elite once CI is green and screenshots look right.
**INSTALLED 11-16 19:09:59 EST on the Elite** (`pm install -r`, Success): staged /sdcard/Download/BatteryLog-redesign-2.0-1611739d.apk sha256 ab8ccc9d… = CI .sha256 = host = device = installed base.apk; replaced v1.6.0 (data kept). Crash buffer empty, no FATAL after install; process started by MY_PACKAGE_REPLACED (pid 25006). NOT opened yet (user on the device). Proof level: CI-green + installed, NOT device-tested.
**Resume point:** user opens BatteryLog on the Elite; check: Now ring/tiles/24 h chart from the 12 boot logs, History ranges + drag/zoom, a log's detail, Health (root prompt possible: Magisk), Settings → Updates (shows Latest v1.6.0 = up to date). Logcat check after. Rollback: `pm install -r -d` /sdcard/Download/BatteryLog-backup-20261116/BatteryLog-v1.6.0-installed.apk.
**Device test:** — (not installed; install only after the user's yes).
**Decisions (mine, changeable):** release build (R8, not debuggable) instead of the old debug build, same app ID `com.the412banner.batterylog.debug`; debug builds get `.dev` suffix; version stays 1.6.0/vc8 until the release cut; alerts run only while the service runs (Auto or Recording); tile tap opens the app; auto-delete default Never.
