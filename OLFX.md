# OLFX

Fork of [Monado](https://gitlab.freedesktop.org/monado/monado) with a minimal VR app launcher
(`olfx/`) that also acts as the **OpenXR runtime broker**.

## Launcher
- List of installed apps, one per row (icon + name), search, filters (OpenXR / Meta Quest / Pico), favourites.
- Tap an app: icon at 40% of screen height, name, package name, **Play**, **Activities** (list / launch / copy
  activity names), per-app OpenXR runtime, favourite, app info, uninstall, version / SDK / size / install date.

## Runtime broker
`RuntimeBrokerProvider` implements the Khronos broker protocol
(`content://org.khronos.openxr.runtime_broker/openxr/1/abi/<abi>/runtimes/active`). For each calling game it picks:
1. the runtime pinned for that game, else
2. **OculusXR** runtime for Meta Quest games / **Pico** runtime for Pico games (if installed), else
3. the global default (Monado, built from `src/xrt/targets/openxr_android`).

Meta Quest / Pico detection: Oculus VR intent category, `com.oculus.*` manifest metadata / `oculus.*` features,
`pvr.*` / `pico.*` metadata, package prefixes. The vendor runtimes are proprietary and are **not** bundled;
they must be present on the device.

Uninstall any standalone Khronos broker app first (provider authorities must be unique).

## Build
```
./gradlew :olfx:assembleDebug
```
