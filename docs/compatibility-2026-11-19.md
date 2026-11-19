# Compatibility validation — 19 November 2026

Android device: Redmi Note 9S, arm64, connected by ADB.

Hand Physics Lab initially crashed before Unity startup in an anonymous executable
mapping. Its UnityPlayerActivity statically loaded libovrplatformloader and then a
bundled libfrda script-injection module. In OpenXR compatibility mode OLFX now
replaces the recognized optional module with a JNI placeholder and preserves the
original at assets/olfx-backup/libfrda.so. The replacement is restricted to APKs
with the corresponding libscript.so / patch_ovrplatformloader configuration.

The next exit was Unity's explicit non-Oculus hardware gate. DexCompat patches only
com.unity.oculus.OculusUnity.getIsOnOculusHardware in compatibility mode. The
transformation was run against the actual game's DEX, checked for idempotence and
SHA-1 correctness, and decompiled to confirm the method body returns true.

OpenXR then failed because the native adapter supplied an Application context.
The adapter now prefers UnityPlayer.currentActivity. GLES session initialization
is deferred until a rendering context is current on the layer-setup thread.

Device result: Hand Physics Lab reached its stereo scene and Continue menu;
a screenshot was captured at /tmp/olfx-session-test.png. The game and camera
service were stopped after the test. The screenshot reports BAD TRACKING because
no controlled real-hand test was performed. Finger tracking quality, gameplay,
other Android games and iOS device behavior are not validated by this result.

Installer now compares APK signing certificates and updates matching packages in
place instead of asking to uninstall them. Subsequent game updates were confirmed
through Android's Update prompt without an uninstall.

iOS: palm-depth estimation uses several spans, output finger lengths are
constrained after smoothing, and a single recently tracked hand retains its slot
through a brief handedness-classification change. These are experimental changes;
physical validation on an iPhone is still required.
