# VRMobile / OculusXR and Frameloader analysis

Reference requested: `https://github.com/travelerdev/frameloder` (404). The intended project is
[`travelerdev/frameloader`](https://github.com/travelerdev/frameloader), inspected at commit
`69952688fa7622746b47b07a06b53a690022ae74` (MIT, Copyright 2026 Traveler Dev Ltd).

Frameloader is an Electron **sideloader** for Valve Steam Frame. It pairs over the SteamOS devkit
protocol, uploads APK/Windows/Linux payloads over SSH/SFTP, registers Steam shortcuts and selects
Lepton/Proton/SLR. It does **not** contain a VR frame renderer, frame-pacing loop or OculusXR
compositor. Therefore OLFX does not port SSH/Lepton/Proton code; it adapts only management patterns.

What OLFX adapts from its architecture:

- **Preflight before work:** runtime discovery validates ABI and standard OpenXR metadata; camera
  hand tracking checks permission, negotiates a supported YUV size and fails with a visible reason
  instead of silently producing black frames.
- **Serialized, bounded work:** hand inference runs on one handler thread, uses
  `acquireLatestImage()` and a 30 Hz cap, so stale frames are dropped instead of queued. Model
  initialization is also off the UI thread.
- **Cancellation/cleanup:** camera/session/reader close deterministically; the MediaPipe model is
  closed on its owning thread after in-flight inference; the visual test releases the camera on pause
  and returns it to an active runtime service.
- **Capability-aware selection:** OculusXR is selected only when a Meta runtime is actually exposed
  (service metadata or the standard Android system broker). Missing runtimes do not silently fall back.

## OculusXR / VRMobile path

- Built-in broker remains at `org.khronos.openxr.runtime_broker` (no separate broker install flow).
- Discovery also reads `org.khronos.openxr.system_runtime_broker` when present, with
  `org.khronos.openxr.permission.OPENXR_SYSTEM`, and caches results briefly with package-change
  invalidation. The provider preserves `native_lib_dir` and `base_path` separately.
- Per-game runtime pins and Quest Auto preference remain. OculusXR is **not bundled or emulated**;
  it requires the proprietary Meta runtime on compatible hardware.
- Presentation uses FIFO vsync pacing, a bounded preferred swapchain image count, and
  `VK_GOOGLE_display_timing` when the Android Vulkan driver exposes it (with fake-timing fallback).
  This is not a guarantee of zero lag or universal compatibility; device testing is required.

## Hand tracking

- The likely black-frame root cause was the native YUV bounds check: Android NV21 U/V planes commonly
  report `rowStride * chromaHeight - 1` bytes. OLFX now validates the last accessed byte per plane,
  accepts independent U/V row/pixel strides, returns a conversion failure to Java, and has a host C
  regression test (`tests/olfx/test_yuv.c`).
- Camera rotation passed to MediaPipe is clockwise, but MediaPipe returns normalized landmarks in
  the ORIGINAL unrotated image coordinate system. OLFX rotates those points once and uses the same
  upright points for the preview skeleton, track matching and perspective fit. A display listener
  recomputes geometry when a game rotates from launcher portrait to landscape. Focal length uses
  camera calibration/intrinsics or sensor metadata with crop/aspect handling instead of a fixed scalar.
- MediaPipe handedness assumes a mirrored selfie image, so the non-mirrored rear camera swaps the
  reported labels; the user swap setting remains. Two-hand slots prefer confident smoothed chirality
  and fall back to image position. Monocular depth-reflection ambiguity is scored against MediaPipe's
  relative depth ordering, then locked after an initial vote to avoid per-frame hand flipping.
- CPU delegate is the stable runtime default to avoid GPU queue contention with the compositor.
- OLFX camera joints are delivered through the built-in Monado hand-tracking device. When a game is
  routed to OculusXR, hand tracking is whatever the proprietary Meta runtime exposes on compatible
  hardware; OLFX does not inject camera joints into OculusXR. Games must request OpenXR hand tracking.
- Camera frame timestamps are passed to native code for bounded prediction (45 ms maximum) of both
  joint positions and orientations. Joint relations are transformed by the head pose exactly once;
  `hand_pose` remains identity so Monado does not apply the wrist transform twice.
- The Hand Tracking tab has **Test hand tracking**: live preview, skeleton, processed-frame FPS,
  inference time and explicit start/stop. Fatal camera/model errors reset the toggle and show a
  localized reason. The test runs in the Monado process and uses the same pipeline as games.

## SBS badge

The old `OLFX - OPEN FREE` text overlay is removed. In SBS mode, OLFX draws its OWN launcher app image
(the adaptive OLFX icon, not the launched game's icon) at the top-left of each eye/lens half. The game
package is still recorded on launch/broker query for runtime selection. Because Android environment
variables are per-process, client-hosted `MonadoView` surfaces receive the active SBS flag explicitly
through `IMonado.getOlfxDisplayConfig()`; service-hosted overlay surfaces use the service environment.

## Play, install permission and launch animation

The original Play-time VR check is restored: an unpatched Quest/Pico/OpenXR title offers the
Optimization patch plus reinstall, launch unchanged, or cancel. Patching warns about signature change
and possible save/OBB loss before uninstalling anything. `REQUEST_INSTALL_PACKAGES` remains in both
manifests; when Android requires the unknown-apps grant, OLFX keeps the prepared APK, opens the
package-specific settings page and resumes installation after the user returns with permission.
A short game-image launch transition (icon overshoot, pulse rings and progress) runs only after launch
preflight and outside the XR render loop; it is cancelled if the launcher is paused or Back is pressed.

## v1.5.0: OpenXR hand-joint delivery hardening

- **Executable native path test.** `tests_olfx_hand_tracking` is a Catch2 host test that builds a head +
  camera-hand device pair, finalizes static system roles, creates the real base space overseer, and calls
  `xrt_system_devices_create_hand_tracker()` / `xrt_hand_tracker_locate()` for left and right hands. It
  checks all 26 joints in the requested base space and clean inactive delivery. This is the native core
  behind `xrCreateHandTrackerEXT` / `xrLocateHandJointsEXT`, not a compile-flag/string assertion.
- **Timestamp source.** `OlfxHands` reads `SENSOR_INFO_TIMESTAMP_SOURCE` and converts REALTIME camera
  timestamps to monotonic before MediaPipe and `nativeSetHand`. `HandFrameClock` is covered by JVM tests.
- **Lifecycle.** `HandRuntime.startIfEnabled()` prewarms the camera FGS from the launcher before Play and
  when hands are re-enabled. `OlfxHands` schedules up to four bounded runtime recoveries after camera/model
  failure, resets the counter on successful hand publication, and never retries the visual test.
- **OpenXR state.** Inactive locate results zero joint poses/radii/velocities. The legacy builder keeps
  camera hands as unobstructed sources and leaves conforming roles empty because the EXT wrappers expose no
  `HT_CONFORMING` inputs. EXT pinch uses palm-relative hysteresis; palm-pose interaction is enabled.
- **Preserved behavior.** Touch/simple/generic controller rebinding, immediate Play/launch animation,
  permission-denial handling, SBS badge/compositor config and FIFO pacing remain. OculusXR is still a
  proprietary selection path, not an injection target for OLFX camera hands.

## v1.4.0: in-game hand interaction, immediate Play, launch permission handling

- **OpenXR interaction for controller-only titles.** `target_builder_legacy` now creates two
  non-owning `cemu_devices_create_ext_hand_interaction()` devices from the OLFX hand tracker when no
  left/right controller exists. They expose `XRT_DEVICE_EXT_HAND_INTERACTION` with pinch/aim-activate/
  grasp values and ready flags, grip/aim/pinch/poke/palm poses, and forward `XR_EXT_hand_tracking`.
  `rebindings/50-touch_to_hand_interaction.json` maps Oculus Touch trigger/squeeze/poses to that
  profile; simple/generic controller mappings already existed. The bindings generator validates the
  merged rebinding set during the native build. Buttons, thumbsticks and haptics are deliberately not
  emulated.
- **Pose fallback.** `OlfxHands.publish()` first tries the rigid PnP fit; on rejection it uses a bounded
  depth estimate from image landmarks plus the model's metric span, smooths the wrist, and still calls
  `nativeSetHand`. This is lower accuracy than the full fit but prevents "no hands in any game" when the
  fit is temporarily untrustworthy. Counters are visible in the hand test.
- **Foreground camera lifecycle.** `DetailActivity` requests CAMERA before launching a Monado-selected
  game with hands enabled. `CameraPermissionActivity` calls `MonadoService.refreshForeground()` after a
  grant so Android 14 revalidates the FGS type as `camera`.
- **Immediate Play + Permission Denial.** `AppScanner.playIntent()` only returns exported/enabled
  activities whose permission OLFX holds (VR → XR → launcher/leanback fallback). `DetailActivity`
  starts the activity immediately with `ActivityOptions.makeCustomAnimation`; the overlay is a
  transition, not a launch gate. `SecurityException` is caught separately and points to the game's app
  info / reinstall path. OLFX does not bypass Android's export or permission rules.
- **Limits.** Meta's proprietary OculusXR/VrApi path still cannot receive injected OLFX camera hands.
  Quest titles must use a standard OpenXR/Monado path for this to apply. Accessibility pinch taps remain
  Android-UI only; in-game clicks now go through OpenXR actions where the title supports them.

## v1.3.0: pinch click, OpenXR hand delivery, install-once

- **Pinch → click.** `PinchGesture` is a pure hysteresis/dwell/cooldown state machine (thumb tip 4 vs
  index tip 8, normalized by palm size). `OlfxHands.maybePinch` runs on the upright 2D landmarks, so a
  click does not depend on the monocular 3D pose fit. The user-enabled `PinchClickService`
  (AccessibilityService, `canPerformGestures`, `:monado` process) injects one tap per deliberate pinch
  via `dispatchGesture`. Android requires explicit user enablement; OLFX only opens accessibility
  settings. The tap point mirrors the upright camera pinch midpoint onto the display — an
  approximation, not a calibrated gaze ray.
- **MODO DE CLICAR.** Settings exposes exactly two modes: `CLIQUE NA TELA` (normal touch) and
  `CLIQUE POR MOVIMENTO DE PINÇA HAND TRACKING`. The choice is stored in `Prefs.clickMode` and shared
  through the private `SettingsProvider` route `/olfx/click`; the accessibility service observes it.
- **OpenXR hand delivery.** The Android build pins `XRT_FEATURE_OPENXR_HAND_TRACKING_EXT`,
  `XRT_FEATURE_OPENXR_HAND_TRACKING_DATA_SOURCE_EXT` and `XRT_FEATURE_OPENXR_INTERACTION_EXT_HAND` ON.
  `MonadoService` no longer starts the invalid `FOREGROUND_SERVICE_TYPE_MANIFEST` mask; it selects
  `camera` when CAMERA is granted, otherwise `connectedDevice`, and a foregrounding failure no longer
  skips `OlfxHands.start`. `nativeHandTrackingStatus` + the in-app test report whether the extensions
  are compiled and whether the native device has active hands. CI checks the shipped libraries for
  `XR_EXT_hand_tracking`, `xrCreateHandTrackerEXT` and the JNI hand symbols.
- **Install once.** Play requires every listed title to carry the in-APK `assets/olfx-patch` marker;
  unpatched titles get a single "Optimize and install" dialog (no launch-anyway path). Imported APKs
  are always optimized before install. `OptimizationGate` serializes patch work across the launcher;
  `ApkPatcher` preflights free space and uses a device-aware Unity job-worker count. The marker is
  read from the installed APK, so optimized titles launch directly and are not re-patched.
- **OculusXR / frameloader.** Still capability-aware selection + preflight, never a renderer port.
  Launch now aborts when no OpenXR runtime resolves. Meta's proprietary runtime is required for
  OculusXR; OLFX camera joints are not injected into OculusXR.

## v1.6.0: VrApi/OculusXR compatibility route, Meta hand extensions, real OpenXR probe

- **Quest Auto no longer bypasses camera hands.** `RuntimePolicy` receives `cameraHands`; when a Quest
  title is on Auto and OLFX hands are enabled, the built-in Monado/OpenXR runtime is preferred over
  OculusXR. Explicit OculusXR still passes through to Meta's native runtime. The APK marker now records
  `olfx-loader` and `olfx-compat`, so changing the route after optimization prompts a safe re-patch instead
  of silently keeping the old Meta loader.
- **Meta/OpenXR hand extensions.** The runtime advertises and fills `XR_FB_hand_tracking_aim` and
  `XR_FB_hand_tracking_capsules` output chains from the same 26 camera joints. Aim is a conservative
  wrist-frame fallback plus pinch strengths; capsules are inscribed segment volumes. Inactive frames zero
  both chains. This helps OculusXR/OpenXR Unity titles that request Meta's hand output structures.
- **Experimental VrApi/OVRPlugin route.** A per-game `OpenXR compatibility` choice replaces the game's
  `libOVRPlugin.so` with an MIT-licensed OpenXR-backed shim (vendored from
  `daniel-lynch/ovrplugin-openxr-shim`, commit `da7dd08`) and adds/replaces the Khronos loader. OLFX adds
  `ovrp_GetHandTrackingEnabled` / `ovrp_GetHandState` backed by `xrCreateHandTrackerEXT` /
  `xrLocateHandJointsEXT` into the 512-byte Unity `HandStateInternal` ABI. This is opt-in, experimental,
  and not a universal VrApi emulator; it does not bypass entitlements and may fail on titles with different
  OVRPlugin revisions. Normal Auto/Monado/OculusXR paths do not install it.
- **Platform guard (compat route only).** On a phone, Meta's `libovrplatformloader.so` asks the Activity for
  `createPackageContext("com.oculus.*")`, gets `NameNotFoundException` and `abort()`s. The patcher moves the
  original loader to `libovrplatformloader_real.so` and installs `libovrplatformloader_guard_arm64.so`
  (`tools/ovr_guard/`): it forwards all 1069 exports and wraps the three `ovr_PlatformInitializeAndroid*`
  entry points. They receive `org.olfx.ctx.OlfxContext` (embedded dex, `tools/olfx_ctx/`) instead of the raw
  Activity, so the package lookup no longer throws; if the loader still `abort()`s, the SIGABRT is caught for
  that call only and the game gets an ordinary failed-initialize result. The guard never reports
  entitlement, ownership or account state, so titles that require the store to confirm ownership still fail
  their own check. Rebuild with `tools/ovr_guard/build.sh <the game's libovrplatformloader.so>` (needs the
  NDK; the dex first with `tools/olfx_ctx/build.sh`). Not yet validated on a device: the numeric
  failure code (`ovrPlatformInitialize_Unknown`, -7) and whether the loader reaches its normal error path
  after the context fix are `[VERIFY-ON-HW]`.
- **Stub policy.** OVRPlugin exports nobody implements are generated from `tools/ovr_shim/policy.tsv` by
  `tools/ovr_shim/gen_policy.py` (default `ovrpFailure_Unsupported`; out-parameters are never written).
  `gen_policy.py --suggest <logcat>` lists the stubs a game actually called.
- **Real delivery probe.** The hand test has a `Test OpenXR delivery` button. It extracts the bundled
  Khronos loader, initializes it with the Android context, creates a headless OpenXR client through the
  broker, creates both hand trackers and calls `xrLocateHandJointsEXT` for ~1 s. The result reports actual
  XrResult codes, active hands and valid joint counts instead of compile-time flags.
- **Limits.** OculusXR passthrough still cannot receive injected OLFX camera hands; use Monado/OpenXR or
  the experimental compatibility route. The OVRPlugin shim's skeleton rotations are an approximation of
  Meta's bind pose, and device validation is still required for each title.
