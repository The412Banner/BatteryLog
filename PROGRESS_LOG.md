# BatteryLog — Progress Log

Newest entry first. Times are US Eastern.

## Resume point
- **feat/hud-auto-overlay (11-21):** 6d409f7a auto overlay (CI 44942433196 green) + 2aae29b0 Share sensor report (CI 44942919188 green). Staged /sdcard/Download/BatteryLog-hud-report-2aae29b0.apk on FIT + Elite (sha 80788b8f host == FIT == Elite, signer a40da80a). Install needs user yes. NOT device-tested.
- **v2.2.0 STABLE = Latest** (11-21 13:40:49 EST, tag -> c0466f804458edaf202830e82bbe89ece80a1a6c = release run 44936374758 headSha; HUD overlay + game picker + Shizuku mode). Asset BatteryLog-v2.2.0-c0466f80.apk 3,669,815 B sha256 da20a49d… (= .sha256, AOSP testkey a40da80a). FIT + Elite INSTALLED the release APK 14:14 EST (user yes; pm install -r, base.apk sha da20a49d = asset, vc 11 / 2.2.0, no crash). HUD in a game and Shizuku mode NOT device-tested.
- **feat/hud-overlay (11-21):** 42d83a41 ("Add a game" picker, CI 44873722932 green) INSTALLED FIT + Elite 13:20 EST (user: "yes install it on both"; base.apk sha 082b44fb on both, no crash). HUD not tried in a game yet. Shizuku mode not tried (Shizuku not on FIT/Elite).
- **v2.1.0 STABLE = Latest** (11-20 08:55 EST, tag -> d72fd40e = release run 44747786224; per-app monitoring). Pocket FIT runs 208cc13b (same code, versionName 2.0.0) -> its updater should offer 2.1.0 (not yet tried).
- **v2.0.0 STABLE = Latest** (published 2026-11-16 19:30:20 EST, tag → 21cc13320fa02bdbbd69157d269441b4e2e86a16 = release run 43987268286 headSha); assets BatteryLog-v2.0.0-21cc1332.apk (sha256 b7917a08…, = .sha256, AOSP testkey a40da80a, vc 9) + .sha256; notes = release-notes/v2.0.0.md. main = 21cc1332 (+ log commits); backup refs/backup/20261116/main-before-redesign-2.0 = 0b7367d2.
- **In-app updater DEVICE-PROVEN on the Elite (11-16 19:32 EST):** 1611739d (1.6.0) → v2.0.0 via Settings → Updates: found the release, downloaded, SHA-256 verified, installed. Installed base.apk sha256 b7917a08… = release asset, vc 9 / 2.0.0, installer of record = BatteryLog itself; crash buffer empty. Elite runs v2.0.0. Rollback APKs: /sdcard/Download/BatteryLog-backup-20261116/.
- Releases: dev builds = Actions artifacts / `-preN` prereleases only; stable only when the user says so. Install on a device only after the user's explicit yes.

## Lessons learned (don't repeat these)
- (none yet)

## 2026-11-21 — HUD: allow the overlay through root or Shizuku (`feat/hud-auto-overlay`, IN PROGRESS)
**Why:** user's friend on an Honor Magic 8 Pro (BatteryLog installed from the APK) found "display over other apps" blocked as a restricted setting (Android 15+ restricts it for apps installed from a file; fix by hand = App info → ⋮ → Allow restricted settings). User: "yes add the auto overlay permissionz".
**Change (6d409f7a):** `AppSampler.grantOverlay` = `appops set <pkg> SYSTEM_ALERT_WINDOW allow` through the current shell. `AppMonitorService`: tries it once per service start and on `requestOverlay()` when the HUD is on and overlays aren't allowed; waits use `nap()` (Channel wake) so a request is handled at once. Settings HUD card: switch-on and the "Allow display over other apps" button call `allowOverlay()` = if Shizuku ready or root → requestOverlay + wait up to 10 s for `canDrawOverlays`, else Android's overlay settings page; card re-checks the permission every 1 s while the HUD is on and not allowed.
**Friend's Honor HUD screenshot (14:38 EST, seen 14:40):** HUD runs over Discord, but GPU % and GPU MHz show "—" (kgsl files likely unreadable from the shell), CPU 105 °C (friend: not real; a stray sensor or HAL value wins the hottest-of-group pick), Case 25 °C / Bat 25 °C, 0.0 W (possible current unit issue on Honor). Root vs Shizuku on that phone unknown. Needs a raw sensor dump from the device to fix; sensor-report button offered to the user.
**CI:** 44942433196 (6d409f7a) green.
**Sensor report (2aae29b0, user: "yes add the sensor report button"):** Settings → Per-app monitoring → "Share sensor report": runs assets/sensor_report.sh through root, else Shizuku (own user service tag/process "report" so the monitor's shell keeps running; ShizukuShell got a name param), else the app's own sh (`RootHelper.runAsApp`); script = id, device props, every thermal zone type+temp, dumpsys thermalservice temperature lines, kgsl-3d0 files, /sys/kernel/gpu, devfreq, power_supply/battery. Report adds "what BatteryLog reads" (zone or HAL source per group, `SensorReport.interpret`, unit test) + raw BatteryManager values; written to cache, shared as a text file and copied to the clipboard. Script tried by hand as root on FIT + Elite and as uid 2000 on the Elite (magisk context): all sections OK. CI 44942919188 green.
**Staged:** BatteryLog-hud-report-2aae29b0.apk on FIT + Elite, sha 80788b8f (host == FIT == Elite). **Not tested:** on a device.

## 2026-11-21 — v2.2.0 stable release (feat/hud-overlay -> main, DONE)
- User: "merge and make a new stable" (11-21 ~13:3x EST), after the picker build 42d83a41 was installed on FIT + Elite. User was told the HUD was not tried in a game and Shizuku mode not tried; decision recorded.
- main had moved (fastlane 7c35f212, GPL-3.0 82dd3d5/24a5de8a) -> merge --no-ff d60b95b4 (only conflict PROGRESS_LOG, both kept); backup refs/backup/20261121/main-before-hud-overlay = 24a5de8a.
- Release commit df08da91: versionCode 11 / 2.2.0, release-notes/v2.2.0.md, README (Root line, Shizuku paragraph, HUD section, Credits: Shizuku-API RikkaApps Apache-2.0).
- Pushed main c0466f80 (log commit on top of df08da91). Release run 44936374758 (workflow_dispatch release=true tag=v2.2.0 prerelease=false, headSha c0466f80 == pushed): all steps green (logic tests, build, v1+v2+v3 sign, screenshots, publish). Push build 44936354996 ran on the same commit.
- Verified: v2.2.0 Latest, not draft/prerelease, tag -> c0466f804458edaf202830e82bbe89ece80a1a6c (== built commit); assets BatteryLog-v2.2.0-c0466f80.apk 3,669,815 B sha256 da20a49d52ea1d1a44c1ead748a0dac4b55cc6add9d7c07052cb0f037d7ebcca (downloaded, == .sha256) + .sha256; signer a40da80a (same key as all releases); release body == release-notes/v2.2.0.md; unauthenticated releases/latest = v2.2.0 (what the updater reads).
- Install 14:14 EST (user: "yes install 2.2.0 on both"): release APK staged in /sdcard/Download on FIT + Elite (sha da20a49d host == FIT == Elite), pm install -r on both; installed base.apk sha da20a49d, versionCode 11 / 2.2.0, crash buffer empty, no FATAL; FIT 2 services up (battery + per-app), Elite battery service up (per-app off there).
- Not done: HUD game test; Shizuku test; branches feat/shizuku-mode, feat/hud-overlay, rel/v2.2.0 (local) kept (ask before deleting).

## 2026-11-21 — HUD overlay over games (`feat/hud-overlay`, stacked on `feat/shizuku-mode`, IN PROGRESS)
**Why:** user: "add a simple customizable hud overlay for apps to display metrics toggled on/off for specific games ... so it stays visible". Plan given in chat (overlay permission, metric list, per-game auto show, position/size/opacity).
**Change (febb7028):** `hud/HudConfig.kt` (HudMetric enum: FPS, CPU %, GPU %, GPU MHz, CPU/GPU/case/battery °, battery %, watts, clock; HudConfig: enabled, corner, x/y, size, opacity, oneLine, locked, apps map pkg → metrics; HudStore in prefs "hud" as JSON), `hud/HudText.kt` (pure parts + warm/hot levels, unit-tested), `hud/HudOverlay.kt` (TYPE_APPLICATION_OVERLAY TextView, rounded dark background, colours, drag when unlocked, FLAG_NOT_TOUCHABLE + alpha 0.8 when locked so touches pass through on Android 12+). FPS: `AppSampler.fps(pkg)` = `dumpsys SurfaceFlinger --list` → layer (SurfaceView BLAST first, else app window; `AppParse.sfLayerFor`) → `--latency` actual-present times → frames in the last second (`AppParse.sfFps`, 0 when static/stale), re-picks the layer after 10 s of nothing. `AppMonitorService.updateHud` after each tick: shows over the app on screen when it has HUD metrics; loop runs every 1 s while shown (recording too). UI: Settings card "HUD overlay" (switch turns on per-app monitoring + asks overlay permission; corner, size, background %, one line, lock position); app page in Apps: "HUD" card (switch + metric chips). Manifest SYSTEM_ALERT_WINDOW. Tests: surfaceFlingerLayerAndFps, hudTextParts.
**Checked live before writing:** FIT `dumpsys SurfaceFlinger --list` names + `--latency "<window layer>"` = refresh period line + 128 rows (zeros when idle).
**Not tested:** everything on a device; FPS on a real game layer; Samsung shell access to SurfaceFlinger (shell has DUMP).
**Install 00:31 EST:** febb7028 (CI 44870962353 green) on FIT + Elite via pm install -r; installed base.apk sha e931b193 on both; crash buffer 0 BatteryLog lines; app running.
**Picker (42d83a41):** Settings HUD card lists HUD games (tap → metric chips, ✕ remove) + "Add a game" dialog (launcher apps via queryIntentActivities, search, games first by ApplicationInfo.CATEGORY_GAME / FLAG_IS_GAME); chips shared (ui/hud/HudUi.kt). CI 44873722932 green.
**Install 13:20 EST:** 42d83a41 on FIT + Elite via pm install -r (user: "yes install it on both"); staged sha 082b44fb checked on both first; installed base.apk sha 082b44fb on both; crash buffer empty, no FATAL; FIT: both services back (per-app on), Elite: battery log service back (per-app off there, as before). Rollback = febb7028 APK in Download or v2.1.0 release.

## 2026-11-21 — Per-app monitoring through Shizuku, no root (`feat/shizuku-mode`, IN PROGRESS)
**Why:** user asked whether BatteryLog can read the case (skin) temperature on a Galaxy Fold 8 Ultra without root. Checked main 48aa3aa: the battery log reads only battery temp (BatteryLogService.kt:237); CPU/GPU/case temps come from the per-app monitor, which needs a root shell (apps/AppSampler.kt:27-29, "Root access is needed" AppMonitorService.kt:81). User: "yes add the shizuku mode to batterylog".
**Change:** `PrivShell` interface; `RootShell` implements it; new `ShizukuShell` = Shizuku user service (`ShellUserService`, AIDL `IShellService`) that runs scripts with `sh` in Shizuku's process (shell uid via Wireless debugging, root uid in root mode). `AppSampler` picks root first, then Shizuku (or only Shizuku with the new setting "Use Shizuku instead of root"); drops and re-picks the shell after a failure. Temperatures: when the sensor files give no CPU/GPU/case value (Samsung hides them from shell), they come from `dumpsys thermalservice` ("Current temperatures from HAL", types 0 CPU / 1 GPU / 2 battery / 3 skin; `AppParse.halTemps`, unit test). Usage access (`appops set … GET_USAGE_STATS allow`) goes through the same shell. Apps tab: "Recording every N s · Shizuku/root", error "Needs root or Shizuku", button "Allow Shizuku" when Shizuku runs but BatteryLog isn't allowed. Settings subtitle "Needs root or Shizuku". Manifest: `rikka.shizuku.ShizukuProvider`. ProGuard keeps the user service. Library: Shizuku-API 13.1.5 (RikkaApps, Apache-2.0).
**Commits:** 495fb96f (Shizuku mode), 7a416455 (setting). **CI:** 44869980617 (495fb96f) running.
**Not tested:** everything on a device; what a shell-uid Shizuku may read on Samsung (ps/proc, kgsl, thermal files) — dumpsys paths should work (shell has DUMP).

## 2026-11-21 — GPL-3.0 license added (main)
- Why: no license file; Orion Store marks repos without one "Not Open Source". User picked GPL-3.0.
- Built: LICENSE (GitHub GPL-3.0 text) + README "License" section. Branch feat/gpl-license fast-forwarded to main; backup ref refs/backup/20261121/main-before-gpl-license = 7c35f212. Same day: fastlane icon + 8 screenshots added for store listings (feat/fastlane-metadata).
- Open/Next: Orion submission (user).

## 2026-11-20 — v2.1.0 stable release (feat/per-app-monitor -> main)
- User: "release a new stable" (08:4x EST, after per-app monitoring was installed and used on the Pocket FIT).
- Backup refs/backup/20261120/main-before-per-app-monitor = 3d87f73b; main fast-forwarded to feat/per-app-monitor;
  release commit d72fd40e (versionCode 10 / 2.1.0, release-notes/v2.1.0.md, README per-app section). Code on main ==
  device-installed 208cc13b (diff = README, version, notes only).
- Release run 44747786224 (workflow_dispatch release=true tag=v2.1.0 prerelease=false, headSha d72fd40e): all steps green
  (logic tests, build, v1+v2+v3, screenshots, publish). Release v2.1.0 Latest, tag -> d72fd40ecc9a1017c8ccd0496cfc31e77aa4082f,
  asset BatteryLog-v2.1.0-d72fd40e.apk 3,288,727 B sha256 86d86df5291563e7b7760954119c72079bf663e5447c2e5485c6036b18f4c838
  (+ .sha256); downloaded asset re-verified; unauthenticated releases API returns v2.1.0 (what the updater reads).
- Not done: README screenshots of the Apps screens (ScreenshotTest has no Apps screens yet); feat branch kept (ask before deleting).

## 2026-11-20 — Per-app monitoring (root) (feat/per-app-monitor, CI)
- Why: user asked for per-app views like the device-wide ones (power, temperature, usage) plus a toggle to monitor
  each app's watts, GPU and CPU load/temps, time and memory, all metrics viewable behind a toggle, same animated graphs.
- Verified first (Pocket FIT, root): ps -o PID,UID,RSS,TIME+,NAME; kgsl gpu_busy_percentage / gpuclk / proc/<pid>/kernel
  (GPU memory per process); thermal zones cpu*/cpuss*/gpuss*/ddr/battery; batterystats --checkin ",pwi,uid," (Android's
  per-app mAh estimate); 8 cores. App had no per-app data before (device-wide samples only).
- Built 9fe23849: apps/ (AppParse, RootShell = one long-lived su, AppSampler, AppStore = timeline-<day>.csv +
  apps-<day>.csv in BatteryLog/apps, AppMonitorService = specialUse FGS, foreground app via UsageStats events with
  GET_USAGE_STATS granted through appops), Apps tab (period + sort, live status, list with icons), app detail
  (summary, animated TimeCharts sharing one zoom state), "Show all metrics" toggle = min/avg/max table of every metric
  + raw samples; Settings card Per-app monitoring (2/5/10/30 s); BootReceiver resumes it; LogicTest parser tests.
- 11-20 08:2x user feedback: two notifications -> one shared notification (ServiceNotification, both services on
  ID_SERVICE, stop = DETACH + refreshOrCancel) 539d0867; app names/icons need QUERY_ALL_PACKAGES (Android 11+ hides
  others); History "Used 205 %" confusing -> "2.1 batteries" + percent below d1d2389e (CI 44743927560 GREEN, user:
  install when ready -> INSTALLED 08:31 EST, hash OK, crash log clean). Next: app page = one big History-style chart with metric chips 208cc13b (CI 44744871764 GREEN, sha df3d43e9…; user: yes install -> INSTALLED 08:42 EST, hash OK, crash log clean).
- Honest limits shown to the user: per-app watts = whole-device power while the app is on screen; GPU load device-wide
  (credited to the app on screen); temperatures are device sensors; GPU memory and CPU time are truly per app.
- CI 44741830834 FAILED (a `/*` inside a KDoc opened a nested comment); fix 6745640d -> CI 44742025471 GREEN (logic tests incl.
  new parser tests, build, v1+v2+v3, screenshots). APK sha256 5e699e4c713d3b35bfbd3d0811c842ced8c918345d78da0bb18275aa178ba3a4,
  staged /sdcard/Download/BatteryLog-per-app-6745640d.apk (hash host == device). User said "yes install it" -> INSTALLED on the Pocket FIT 11-20 08:16 EST (installed hash == APK, crash log clean). NOT device-tested yet.

## 2026-11-16 ~19:20 EST — v2.0.0 stable release cut (feat/redesign-2.0 → main)
**Why:** user (19:17 EST): "release as stable 2.0.0", then "also update the repo read me with app screenshots included".
**Device state before the cut:** 1611739d installed on the Elite 19:09:59; the user opened it 19:15:23 (Displayed +98 ms); crash buffer empty, no FATAL/ANR through 19:16:52. Proof level of the redesign: CI-green + installed + opened by the user without a crash; features not walked through on the device by me.
**Release-cut commit:** versionCode 8→9, versionName 1.6.0→2.0.0; release-notes/v2.0.0.md (was the pre1 draft); README gets a 3×3 screenshot grid (docs/screenshots/*.png, cropped from CI 43984381077 ScreenshotTest renders, sample data, labelled as such). Release notes carry no images (the in-app Updates screen shows notes as text).
**Done so far:** CI 43986574675 GREEN on 21cc1332 → backup ref refs/backup/20261116/main-before-redesign-2.0 = 0b7367d2 (pushed) → main fast-forwarded to 21cc1332 (tree == tested build) → push build 43987258095 + release run 43987268286 (workflow_dispatch, headSha 21cc1332, tag v2.0.0, prerelease=false) running.
**User ~19:30 EST:** "update it through the app when it's released" → after the release: open BatteryLog on the Elite, Settings → Updates → Download & install (announce takeover, save first, Termux back after).
**RELEASED 19:30:20 EST:** v2.0.0 Latest, not prerelease; tag == 21cc1332 == built commit; asset sha256 b7917a088def… matches .sha256; signer a40da80a; notes == file (trailing newline only).
**In-app update (19:31–19:33 EST, user: "update it through the app when it's released"):** announced takeover; ListAgents: no peers. topResumedActivity was **Discord** (not Termux) at 19:31:40, I opened BatteryLog anyway (user-requested) and checked the front app before each input. `appops set … REQUEST_INSTALL_PACKAGES allow` (was default; needed for the updater, left on). `am start … --es route updates` → Updates screen listed "BatteryLog v2.0.0 · 7:30 PM · 3.1 MB" + notes (screenshot OK). 19:32:25 I tapped "Download & install"; then **the user tapped Install** themselves, Android's confirm opened 19:32:31 and was accepted 19:32:33 (user), "installation completed" 19:32:33.442. Verified: vc9 / 2.0.0, base.apk sha256 b7917a08… = asset, installer = com.the412banner.batterylog.debug, crash buffer 0. User then opened BatteryLog from the launcher (19:32:36) and went back to Discord → I did NOT force Termux to the front (user had the device back; rule: no input once the user takes the device back). Temp files .bl-ui.xml/.bl-shot.png removed.
**Proof level v2.0.0:** CI-green, released, installed via its own updater on the Elite, opened by the user twice without a crash. Not walked through feature by feature on the device (alerts, tile, charge limit write, boot-module install, share) — open items.

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
