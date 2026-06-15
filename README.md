# BatteryLog

A bare-bones Android app to monitor battery discharge in the background and
produce a full diagnostic report. Built for the AYANEO Pocket FIT shut-off
investigation (degraded 2S pack, suspected PMIC undervoltage cutoff under load).

## What it does

- **Start monitoring** — runs a foreground service (survives in the background;
  uses `specialUse` FGS type so Android 14 imposes no ~6h cap) and samples the
  battery **every 30 s** to a CSV in the app's external files dir
  (`Android/data/com.the412banner.batterylog(.debug)/files/logs/`). No root needed.
  Holds a partial wakelock so sampling continues with the screen off.
- **Generate report** — reads the latest CSV and writes a human-readable
  `*_report.txt` next to it (and shows it on screen). Computes:
  - Measured usable capacity from the `charge_counter` delta → **SoH vs the
    4139 mAh design and the 2430 mAh reported-full**.
  - Voltage start/end/**min (and at what %)**/max — the UVLO-sag tell.
  - Avg & peak discharge current, temperature min/max/avg.
  - Discharge rate (%/hr), projected 100→0 runtime, a per-10% voltage curve.
  - An **Anomalies** section: early cutoff, voltage sag, high temp.
- **Share report** — share the report text via any app.
- **Install boot module (Magisk)** — *optional, needs root.* Installs a small
  Magisk module that auto-starts a shell sampler at **every boot** (same CSV
  columns), so logging resumes after a reboot and ambushes the next random
  shut-off. **Remove boot module** uninstalls it.

## Usage for the discharge test

1. Sideload the APK (CI artifact `BatteryLog-debug`).
2. At 100%, open the app → **Start monitoring** → disconnect the charger.
3. Let it drain to 0% (it auto-stops at 0).
4. Recharge, open the app → **Generate report**.

## Build

CI only (GitHub Actions → `BatteryLog-debug` artifact). No local Gradle needed.
