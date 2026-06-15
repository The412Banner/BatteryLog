# BatteryLog

An Android app to monitor battery discharge in the background, surface
whole-life battery health, estimate runtime/charge time, and (with root)
control charge speed/limit. Built for the **AYANEO Pocket FIT** random-shutoff
investigation — a degraded 2S pack (~60% SoH) with a suspected PMIC
under-voltage (UVLO) cutoff under gaming load.

- **Package:** `com.the412banner.batterylog` (debug builds: `…​.debug`)
- **UI:** Kotlin, classic Android Views (no Compose), Material 3 DayNight
- **Min/Target SDK:** 29 / 34
- **Root:** not required for monitoring/reports; required for the boot module
  and charge control.

---

## Features

### Monitoring
Samples the battery **every 30 s** to a CSV in the app's external files dir
(`Android/data/com.the412banner.batterylog(.debug)/files/logs/`). Runs as a
foreground service of type `specialUse` (deliberately **not** `dataSync`, which
carries Android 14's ~6 h/day cap that would kill a long overnight drain). Holds
a `PARTIAL_WAKE_LOCK` so sampling continues with the screen off / during doze,
and `fsync()`s every row so the tail survives a hard shut-off.

Two ways to run it:

- **Auto mode** *(recommended)* — arm it once. The service stays resident and
  watches the charger: the instant you **unplug** it opens a fresh
  *discharge-only* log; when you **plug back in** it finalises that log and waits
  for the next unplug. It reacts inside the already-running service rather than
  cold-starting from a broadcast, which sidesteps Android 14's restriction on
  starting a foreground service from a background event.
- **Manual** — explicit **Start / Stop monitoring** buttons.

Either way it auto-stops cleanly when the battery reaches 0%.

### Estimates (live, on the main screen)
- **Time remaining** at the current %.
- **Full-charge runtime** (100 → 0%).
- **Time to 100%** while charging.

Each blends two sources: the **fuel gauge's own instant estimate**
(`time_to_empty_avg` / `time_to_full_now` / `time_to_full_avg`) and a
**learned average %/hr** computed from your recorded discharge and charge runs.
The learned figures reflect *your* real usage and sharpen after a few cycles;
the card shows how many runs it has learned from.

### Diagnostic report
**Generate report** reads the latest CSV and writes a human-readable
`*_report.txt` next to it (also shown on screen). It computes:

- Measured usable capacity from the `charge_counter` delta, extrapolated to a
  full 0→100% → **State of Health vs the 4139 mAh design and 2430 mAh
  reported-full** figures.
- Voltage start / end / **min (and at what %)** / max — the UVLO-sag tell.
- Average & peak discharge current; temperature min / max / avg.
- Discharge rate (%/hr), projected 100→0 runtime, and a per-10% voltage curve.
- An **Anomalies** section: early cutoff (ended well above 0% while not
  charging), voltage sag (≤ ~6.2 V above 5%), and high temperature (≥ 48 °C).

**Share report** sends the report text via any app.

### History & cycles
A dedicated screen with:

- A **Lifetime / Battery Health** card read straight from the kernel fuel gauge
  (sysfs, with a root fallback): **charge cycles since the battery was
  manufactured**, learned-full vs design capacity, **State of Health**, capacity
  lost, open-circuit / max voltage, technology and model. These persist since
  manufacture — independent of when the app was installed.
- A battery/voltage **graph** plus a tappable list of every recorded run, each
  with its own summary (direction, Δ%, mAh consumed, voltage min, temp range,
  rate).

### Charge control *(root)*
Reads and (with root) writes the kernel charge-control nodes:

- **Charge speed** — `charge_control_limit` (0 = full speed; higher = lower
  current / cooler charging), bounded by `charge_control_limit_max`.
- **Charge limit** — stop charging at 100 / 90 / 80% via
  `charge_control_start_threshold` / `charge_control_end_threshold`.

Nodes are world-readable (so current state shows without root); writes go
through `su` and are **read back to confirm they stuck**. Enforcement ultimately
depends on the charger firmware — some devices expose the interface but ignore
it.

### Boot persistence (Magisk module, optional, root)
**Install boot module** drops a small Magisk module at
`/data/adb/modules/batterylog_boot/`. Its `service.sh` (late_start) launches a
self-contained shell `sampler.sh` at **every boot**, writing the *same* CSV
columns (with `boot` in the `plugged` field) into the app's log dir — so logging
resumes after a reboot and ambushes the next random shut-off. The same report
parser handles those logs. **Remove boot module** uninstalls it.

A **live indicator** at the bottom of the main screen verifies the module is
installed *and* its sampler is actually running:
🟢 running · 🔴 installed but not running · 🟡 disabled · ⚪ not installed / no root.

---

## CSV format

One header line then one row per 30 s sample:

```
epoch_ms,iso_time,uptime_ms,capacity_pct,status,plugged,
voltage_mV,current_uA,charge_counter_uAh,energy_counter_nWh,temp_decideg,health
```

The `plugged` field is `ac` / `usb` / `wireless` / `unplugged` for app-recorded
rows and `boot` for rows written by the Magisk boot sampler.

---

## Usage — the discharge test

1. Install the APK (see below).
2. At 100%, open the app and tap **Auto** (or **Start monitoring**), then
   unplug the charger.
3. Let it drain to 0% (it auto-stops), or just game until it cuts off — the boot
   module captures the run-up either way.
4. Recharge, open the app, tap **Generate report**, and check the **Anomalies**
   section and the voltage-at-% curve.

---

## Install

Releases ship a debug-signed APK on the
[Releases](https://github.com/The412Banner/BatteryLog/releases) page; CI also
uploads it as the `BatteryLog-debug` artifact.

> **First install of v1.2.0+ over an older build needs a one-time uninstall.**
> The signing key changed to a stable committed debug keystore in v1.2.0.
> From then on, updates install over the top and **preserve your collected
> logs**.

---

## Build

GitHub Actions builds the debug APK on every push that touches `app/**`
(`./gradlew assembleDebug`, JDK 17) and uploads the `BatteryLog-debug` artifact.
No local Gradle/SDK is required. The debug build is signed with the committed
`app/debug.keystore` so signatures are stable across builds.
