# OLFX for iPhone

- UI: SwiftUI (`ios/OLFX`), project generated with XcodeGen.
- Game runtime: [Husk](https://github.com/Leviidev/Husk) (GPL-2.0-or-later) is **compiled into the OLFX app** — no separate Husk install. `ios/project.yml` pulls Husk's app sources, translation layer, JIT helper and widget extension into the OLFX target (pinned commit in `ios/scripts/build_husk.sh`).
- Play: the Play button hands the stored APK to `IncomingFiles.shared` and shows Husk's `ContentView` full-screen (Translation Layer / Android chooser, then the game).
- Husk's `@main` is stripped from a generated copy (`ios/scripts/gen_husk_glue.sh`); `OLFXApp` boots Husk the same way (`HuskEngine.boot`).
- Build (Mac, Xcode, meson, ninja, pkg-config, qemu-img, xcodegen, Rust `aarch64-apple-ios`):

```sh
ios/scripts/build_husk.sh     # builds Husk natives, generates glue and OLFX.xcodeproj
cd ios && xcodebuild -project OLFX.xcodeproj -scheme OLFX -sdk iphoneos -configuration Release \
  CODE_SIGNING_ALLOWED=NO CODE_SIGN_IDENTITY="" -derivedDataPath build build
```
- JIT still needs Husk's usual setup (built-in StikJIT on iOS 26, or StikDebug/TrollStore); sideload with SideStore/AltStore.
- Licence: linking Husk (and QEMU) makes the iOS app GPL-2.0-or-later; not App Store compatible.
- Not yet done: feeding hand/6DoF poses into Husk's OpenXR layer, MediaPipe engine.

## Android Java on iOS

Husk runs Android DEX through its guest runtime. Use the pinned Husk checkout's
`scripts/build_java_runtime.sh` with an extracted ARM64 Android system and its
matching APEX modules, platform resources and legacy HTTP library. The script's
header lists the inputs. Set `OLFX_JAVA_RUNTIME_DIR` in Xcode if the output is
outside `ios/third_party/Husk/build/java-runtime`. OLFX checks the core files and
packages the Java runtime in the app bundle. Builds without the optional runtime
emit a warning.

Compatibility depends on the Android APIs and native dependencies an app uses,
and on the iOS signing and execution setup. On Android the OS runs the installed
APK's DEX through ART.

## XR bridge (Quest-style games)
`ios/OLFX/XR/` is a small OpenXR runtime inside the app. Husk's linker interposes every `xr*` import (`tl_ld_interpose`), so games that use Unity's OpenXR plugin talk to it instead of an Android runtime broker.
- `olfx_xr.c`: instance, session + events, spaces, frame timing, `XR_EXT_hand_tracking`.
- `olfx_xr_gfx.c`: OpenGL ES swapchains (incl. 2-layer arrays) + a side-by-side stereo compositor into the game's window. Vulkan games are refused (logged).
- `olfx_xr_input.c`: actions. Hands (Apple Vision) give grip/aim poses, pinch = trigger, fist = squeeze; a connected game controller gives thumbsticks/buttons.
- `XRBridge.swift`: ARKit head pose, Vision 2D landmarks -> 26 world joints, GCController -> pad.
- Not done: Vulkan, lens distortion, `libOVRPlugin` (Oculus-plugin Unity games; the ovrp_* signatures are not known), real depth for hands.
- Debug: look for `[olfx-xr]` lines in Husk's log; `unsupported (so far): xrFoo` lists what a game asks for that is missing. If the screen stays black, try swapping in `olfx_gfx_end_frame` (see the comment at its end).

## UI and launching (no Husk screens)
OLFX shows only its own interface (Games / Favorite / Hand-Tracking / Settings). Husk is used as an engine:
`GameRunner.swift` adds an APK to Husk's store (moved, one copy), turns JIT on if needed, and presents Husk's full-screen `TLAttemptView` directly.
Languages (ru, en, pt-BR, pt) are switched in-app (`L10n.swift`). Settings: language, JIT (method, enable, pairing, auto at launch, JIT-less), VR input, LiveContainer help.

## LiveContainer: does not work on iOS 26+ (TXM)
Husk's own source says so: on devices that enforce TXM only a debugger can hand out executable memory, and inside a container app (LiveContainer) the JIT attached by LiveContainer does not answer Husk's request. Symptom: Settings may show a debugger attached, the game says "JIT is not enabled ... no executable memory".
Install OLFX directly with SideStore/AltStore and enable JIT with StikDebug (Settings > Enable JIT).

## Gear VR / Quest (VrApi) games
`ios/OLFX/XRLibs/` holds the VrApi shim from `questandgearandopenxr.zip` (`libolfx_vrapi.so`: a libvrapi.so with all 104 VrApi exports that draws through OpenXR) and a loader stand-in (`libopenxr_loader.so`).
`olfx_vrapi.c` + generated `olfx_vrapi_stubs.S` (`ios/scripts/gen_vrapi_stubs.py`) bind every `vrapi_*` import of a game to the shim, loaded lazily by Husk's linker; the shim's `xr*` calls go to our OpenXR runtime.
Not tested on a device.

### libOVRPlugin on iOS (Oculus XR plugin games)
Same mechanism as VrApi. `olfx_ovrp.c` + generated `olfx_ovrp_stubs.S` (`ios/scripts/gen_ovrp_stubs.py`) bind every `ovrp_*` of a game to
`XRLibs/libolfx_ovrplugin.so` (the OpenXR-backed OVRPlugin shim, same file the Android patcher installs), loaded lazily by Husk's
linker; its `xr*` calls go to our OpenXR runtime. Regenerate the stubs after replacing the shim.

What a device log (HandPhysicsLab) showed, and what the Husk patches in `ios/patches/` fix:
- Husk's interpose table held **8** entries, so only the first xr* names were ever registered. `husk-zz-ovr-dlsym.patch` raises it to 1280.
- Unity reaches `libOVRPlugin` / `libovrplatformloader` through P/Invoke (`dlsym`), not through imports, so interposition has to answer
  there too (the xr* case already did). Same patch.
- `husk-zz-oculus-jni.patch` adds `husk-tl-jni-oculus.c`: `Context.createPackageContext("com.oculus.*")` returns the host context
  (the loader aborted on the null), and the plugin's Java helper answers `OculusUnity.getIsOnOculusHardware() = true` only while the experimental OpenGL ES VR switch is on (a game that believes it is on a Quest draws for the headset and
  never creates a window surface for the phone screen: frames counted, no picture), and `loadLibrary` as a no-op.
- Meta Platform: the game calls `ovr_UnityInitWrapper[Asynchronous]`; these (and `ovr_PlatformInitializeAndroid*`) answer "initialize
  failed" directly, so the real loader never runs and cannot abort. No entitlement, ownership or account state is reported: a game that
  needs the Meta store to confirm ownership fails its own check.
- `husk-oculus-pkgctx.patch` is the same rule for Husk's Java framework (`husk-framework.dex`); it only matters when that dex is built.
- Not tested on a device after these fixes.

## Hand-Tracking tab
Live preview from the ARKit camera with the Vision skeleton, readouts (head pose, pinch, fist, depth), and all tracking settings (smoothing, confidence, hand size, pinch sensitivity, swap hands, LiDAR depth, 6DoF scale, recenter).
