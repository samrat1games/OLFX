# OLFX

Fork of [Monado](https://gitlab.freedesktop.org/monado/monado) with a VR app launcher
(`olfx/`) compiled into the Android runtime app. OLFX keeps its **built-in OpenXR runtime
broker**; there is no broker download/install option in Settings.

## Launcher
- Installed OpenXR / Meta Quest / Pico apps, search, favourites and APK import.
- Original Play-time VR patch warning (patch/reinstall or launch unchanged), unknown-apps permission
  resume with the prepared APK retained, and a short game-image launch animation.
- Per-game runtime choice: Automatic, Monado or OculusXR.
- Settings: Português (Brasil), Русский, English, SBS output, render scale and sensor status.

## Built-in runtime broker and OculusXR
`RuntimeBrokerProvider` answers the Khronos loader URI
`content://org.khronos.openxr.runtime_broker/openxr/1/abi/<abi>/runtimes/active`.
Selection order:
1. the runtime pinned for the calling game;
2. for Meta Quest games, **OculusXR** when an enabled Meta runtime service is detected;
3. the global default chosen in Settings;
4. the Monado runtime built into OLFX.

OculusXR is proprietary Meta software. OLFX selects it only when it is already present on a
compatible device; it does not bundle, download or emulate the Meta runtime. When OculusXR is
present, the optional APK patcher preserves Meta's original loader/OVRPlugin path instead of
interposing the old forwarding shim. Quest titles that require Meta hardware/services still need
that environment.

The broker authority must be unique. If a standalone Khronos broker package is installed with the
same authority, Android can reject one of the providers; OLFX does not install or uninstall that
package automatically.

## SBS, hands and gyroscope
- SBS uses the compositor's two stereo views with identity lens distortion, without screen capture.
  The old text watermark is replaced by the OLFX app's own launcher image at the top-left of each lens half.
- Hand tracking uses the rear camera and requires camera permission plus a game that requests
  OpenXR hand tracking. The Hand Tracking tab includes a live **Test hand tracking** screen with
  skeleton, FPS and inference time; camera/model failures are shown instead of leaving a stuck toggle.
- The Android sensor driver uses gyroscope + accelerometer for 3-DoF head rotation and reports
  tracking as unavailable when the required sensors are missing. It does not provide positional 6-DoF.

## Build
```
./gradlew :src:xrt:targets:openxr_android:assembleOutOfProcessDebug
```
Device-free contract checks:
```
python3 -m unittest discover -s tests/olfx -v
```
Native camera YUV regression (host C compiler):
```
cc -std=c11 -I src/xrt/drivers/android tests/olfx/test_yuv.c -o /tmp/olfx_test_yuv && /tmp/olfx_test_yuv
```
Native OpenXR hand-joint delivery regression (host CMake/Catch2):
```
cmake -S . -B /tmp/olfx-host-tests -G Ninja -DEigen3_DIR=/usr/share/eigen3/cmake -DBUILD_TESTING=ON -DXRT_MODULE_COMPOSITOR=OFF -DXRT_MODULE_IPC=OFF -DXRT_MODULE_OPENXR_STATE_TRACKER=OFF -DXRT_FEATURE_OPENXR=OFF -DXRT_MODULE_MONADO_CLI=OFF -DXRT_BUILD_DRIVER_SIMULATED=OFF
cmake --build /tmp/olfx-host-tests --target tests_olfx_hand_tracking
ctest --test-dir /tmp/olfx-host-tests -R '^tests_olfx_hand_tracking$' --output-on-failure
```
JVM selection/route tests run with:
```
./gradlew :src:xrt:targets:openxr_android:testOutOfProcessDebugUnitTest
```


## VRMobile / OculusXR / hand test

See [docs/VRMobile-Frameloader.md](docs/VRMobile-Frameloader.md) for the Frameloader analysis,
OculusXR discovery path, hand-tracking alignment/test, restored Play flow and SBS OLFX badge behavior.

## v1.5.0 OpenXR hand-joint delivery hardening

- **Native delivery test.** `tests/tests_olfx_hand_tracking.cpp` creates system devices and a space
  overseer, then locates both hands through the same base hand tracker used by `xrCreateHandTrackerEXT`
  / `xrLocateHandJointsEXT`. CI builds and runs it on the host before the Android APK build.
- **Camera clock.** Camera2 timestamps are normalized to `CLOCK_MONOTONIC` using
  `SENSOR_INFO_TIMESTAMP_SOURCE`; MediaPipe VIDEO timestamps remain strictly increasing. This prevents
  REALTIME/BOOTTIME camera samples from looking future-dated or stale to OpenXR after suspend.
- **Foreground prewarm + recovery.** Play starts/refreshes the Monado camera service while OLFX is still
  foreground when hands are enabled. Runtime camera/model failures get bounded retries; the visual test
  and stopped services are not retried. Re-enabling hands in Settings refreshes a running service.
- **OpenXR correctness.** Inactive `xrLocateHandJointsEXT` results zero poses/radii/velocities instead of
  leaving the previous frame visible. Camera hands are advertised only as `UNOBSTRUCTED` data; the EXT
  interaction wrappers are not falsely advertised as controller-derived conforming sources.
- **Natural interaction.** EXT pinch uses palm-relative hysteresis (0.30 close / 0.46 open), matching the
  launcher pinch detector, and `XR_EXT_palm_pose` interaction is enabled. Touch/simple/generic rebinding,
  immediate Play, SBS and FIFO pacing are preserved. Meta's proprietary OculusXR path still cannot receive
  injected OLFX camera hands; device validation is required and universal compatibility is not guaranteed.

## v1.4.0 in-game OpenXR hands and Play launch

- **Controller-only OpenXR games.** OLFX camera hands are wrapped in one `XR_EXT_hand_interaction`
  device per hand when no physical controller is present. A new Oculus Touch → hand-interaction
  rebinding maps trigger/select to pinch and squeeze to a heuristic grasp curl; existing simple/generic
  controller rebindings already target the same profile. This gives Quest/OpenXR titles that only bind
  controller actions a standard OpenXR input path. It does **not** inject hands into Meta's proprietary
  OculusXR runtime, and thumbsticks/ABXY/menu are not fabricated.
- **Hands stay visible.** If the rigid monocular pose fit rejects a frame, a lower-confidence
  landmark fallback publishes smoothed joints instead of silently dropping the hand. The hand test
  reports native publish age, Java-side active hands and fallback-frame count.
- **Camera permission before Play.** When the game resolves to OLFX/Monado and hand tracking is on,
  Play asks for CAMERA from the foreground before launching. A service that started as
  `connectedDevice` is promoted to the camera foreground-service type after the grant.
- **Play starts immediately.** The game activity is started on Play with a custom launch transition;
  the animation no longer gates `startActivity` for ~850 ms. Launch intent selection skips
  non-exported/disabled/permission-protected activities, and a `SecurityException` shows an actionable
  app-info message instead of a raw "permission denied" toast.
- Device validation is still required. Zero bugs/lag and universal compatibility are not guaranteed.

## v1.3.0 hand tracking and clicking

- OpenXR hand tracking delivery is pinned ON in the Android build (`XR_EXT_hand_tracking`,
  `XR_EXT_hand_tracking_data_source`, `XR_EXT_hand_interaction`). The runtime service uses a valid
  Android 14 foreground-service type and still starts the camera pipeline if foregrounding fails.
  The Hand Tracking test now shows an OpenXR delivery diagnostic line.
- Settings → **MODO DE CLICAR**: **CLIQUE NA TELA** or **CLIQUE POR MOVIMENTO DE PINÇA HAND TRACKING**.
  Pinch mode requires enabling the OLFX accessibility service; a thumb+index pinch injects one screen
  tap (hysteresis + dwell + cooldown). The tap position approximates the pinch location in the camera
  image and is not a calibrated gaze ray.
- Every game is installed once as an optimized OLFX version (in-APK marker); Play does not re-patch an
  already-optimized app. Optimization is single-flight and preflights disk space.
- Device/runtime validation is still required for alignment, latency and per-game compatibility.
  OculusXR needs Meta's proprietary runtime; OLFX does not emulate it or inject camera hands into it.

## v1.6.0 hand-tracking routes

- OpenXR/Monado: rear-camera MediaPipe joints delivered through `XR_EXT_hand_tracking`, with
  `XR_FB_hand_tracking_aim` and `XR_FB_hand_tracking_capsules` for Meta/OpenXR titles.
- Quest Auto: when camera hands are enabled, Auto keeps Monado instead of silently selecting OculusXR.
  Explicit OculusXR remains Meta-native passthrough.
- VrApi/OVRPlugin: optional per-game **OpenXR compatibility (experimental)** replaces OVRPlugin with an
  MIT OpenXR shim and maps hand state from OpenXR joints. It is not guaranteed for every game and requires
  re-optimizing/reinstalling the title. It does not inject hands into Meta's proprietary runtime.
- The hand test now includes a real OpenXR client probe (`xrCreateHandTrackerEXT` /
  `xrLocateHandJointsEXT`) and reports active hands/joint counts. APK markers record loader routing so a
  runtime change cannot leave an old Meta loader installed unnoticed.

## Joy-Con IMU and device profiles (host-tested only)
- `joycon_imu.c`: gyro + accelerometer complementary filter. `JoyBridge.java` feeds it from
  `InputDevice.getSensorManager()` (Android 12+); `xrr_joy_orientation()` returns the fused quaternion.
  It is **not yet used to drive controller poses**, and `joy_dex.h` must be regenerated from
  `tools/joybridge` (needs the Android SDK `d8`) for the Java side to take effect; with the old dex the
  buttons keep working and the IMU entry point is simply skipped.
- `olfx_device_profile.c`: identifies Oculus Go, Pico G2, Neo 3 and Pico 4 from Build strings. Not wired in yet.
- Test: `cc -std=c11 -Ithird_party/ovrplugin-openxr-shim/shim/src tests/olfx/test_joycon_imu.c third_party/ovrplugin-openxr-shim/shim/src/{joycon_imu,olfx_device_profile}.c -lm -o /tmp/t && /tmp/t`
