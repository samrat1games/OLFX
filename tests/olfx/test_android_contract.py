#!/usr/bin/env python3
# Copyright 2026, OLFX contributors
# SPDX-License-Identifier: BSL-1.0
"""Device-free checks for the OLFX Android UI/runtime contract."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
RES = ROOT / "src/xrt/targets/openxr_android/src/main/res"


def strings(locale):
    path = RES / locale / ("strings.xml" if locale == "values" else "olfx_strings.xml")
    root = ET.parse(path).getroot()
    return {node.get("name"): "".join(node.itertext()) for node in root.findall("string")}


class AndroidContract(unittest.TestCase):
    def test_locales_have_same_keys_and_placeholders(self):
        base = strings("values")
        for locale in ("values-pt-rBR", "values-ru"):
            translated = strings(locale)
            self.assertEqual(set(base) - {"service_name"}, set(translated))
            for key, value in base.items():
                if key == "service_name":
                    continue
                self.assertEqual(sorted(re.findall(r"%\d+\$[sd]", value)),
                                 sorted(re.findall(r"%\d+\$[sd]", translated[key])), key)

    def test_all_launcher_string_references_exist(self):
        available = set(strings("values"))
        used = set()
        for path in (ROOT / "olfx/src/main/java").rglob("*.kt"):
            used.update(re.findall(r"R\.string\.(\w+)", path.read_text()))
        self.assertFalse(used - available, used - available)

    def test_builtin_broker_and_private_settings_manifest(self):
        manifest = ET.parse(ROOT / "src/xrt/targets/openxr_android/src/main/AndroidManifest.xml").getroot()
        providers = {p.get(ANDROID + "name"): p for p in manifest.findall("application/provider")}
        broker = providers["org.olfx.launcher.RuntimeBrokerProvider"]
        self.assertEqual(broker.get(ANDROID + "authorities"), "org.khronos.openxr.runtime_broker")
        self.assertEqual(broker.get(ANDROID + "exported"), "true")
        settings = providers["org.olfx.launcher.SettingsProvider"]
        self.assertEqual(settings.get(ANDROID + "authorities"), "${applicationId}.settings")
        self.assertEqual(settings.get(ANDROID + "exported"), "false")
        queries = [p.get(ANDROID + "name") for p in manifest.findall("queries/package")]
        self.assertIn("com.oculus.systemdriver", queries)

    def test_no_broker_install_flow_remains(self):
        for path in (ROOT / "olfx/src/main/java").rglob("*.kt"):
            text = path.read_text()
            self.assertNotIn("BrokerContract", text)
            self.assertNotIn("broker_install", text)
            self.assertNotIn("prepareBroker", text)

    def test_oculusxr_selection_and_passthrough(self):
        runtimes = (ROOT / "olfx/src/main/java/org/olfx/launcher/Runtimes.kt").read_text()
        self.assertIn("OCULUSXR", runtimes)
        self.assertIn("byKind(Kind.OCULUS)?.let { return it }", runtimes)
        patcher = (ROOT / "olfx/src/main/java/org/olfx/launcher/ApkPatcher.kt").read_text()
        self.assertNotIn("ovrShim", patcher)
        self.assertIn("preferOculus", patcher)
        self.assertIn("com.oculus.systemdriver", patcher)

    def test_sbs_settings_reach_native_compositor(self):
        java = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/MonadoImpl.java").read_text()
        native = (ROOT / "src/xrt/drivers/android/android_sensors.c").read_text()
        self.assertIn('XRT_COMPOSITOR_SBS', java)
        self.assertIn('getenv("XRT_COMPOSITOR_SBS")', native)
        self.assertIn('u_compute_distortion_identity', native)

    def test_oculusxr_system_broker_and_cache(self):
        manifest = ET.parse(ROOT / "src/xrt/targets/openxr_android/src/main/AndroidManifest.xml").getroot()
        perms = [p.get(ANDROID + "name") for p in manifest.findall("uses-permission")]
        self.assertIn("org.khronos.openxr.permission.OPENXR_SYSTEM", perms)
        queries = [p.get(ANDROID + "authorities") for p in manifest.findall("queries/provider")]
        self.assertIn("org.khronos.openxr.system_runtime_broker", queries)
        runtimes = (ROOT / "olfx/src/main/java/org/olfx/launcher/Runtimes.kt").read_text()
        self.assertIn("system_runtime_broker", runtimes)
        self.assertIn("fun invalidate()", runtimes)
        self.assertIn("CACHE_TTL_MS", runtimes)

    def test_hand_tracking_test_ui_and_pipeline(self):
        main = (ROOT / "olfx/src/main/java/org/olfx/launcher/MainActivity.kt").read_text()
        self.assertIn("openHandTest()", main)
        self.assertIn("org.freedesktop.monado.ipc.HandTestActivity", main)
        activity = ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/HandTestActivity.java"
        self.assertTrue(activity.exists())
        hands = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/OlfxHands.java").read_text()
        # MediaPipe rotation is clockwise; latest-frame-only; no runtime GPU benchmark contention.
        self.assertIn("setRotationDegrees(rotationClockwise)", hands)
        self.assertIn("acquireLatestImage()", hands)
        self.assertIn("MIN_FRAME_INTERVAL_NS", hands)
        self.assertNotIn("benchmark(", hands)
        self.assertIn("nativeSetHand(int hand, float[] joints, boolean active, long timestampNs)", hands)
        self.assertIn("openAsync()", hands)
        self.assertIn("initializeOnThread()", hands)
        self.assertIn("static native boolean nativeYuvToRgba", hands)
        self.assertIn("lastAcceptedNs", hands)
        self.assertIn("frameFailures", hands)
        self.assertNotIn("notifyError(", hands)
        native = (ROOT / "src/xrt/drivers/android/olfx_hands.c").read_text()
        self.assertIn("jlong timestamp_ns", native)
        self.assertIn("olfx_yuv420_to_rgba", native)
        self.assertTrue((ROOT / "src/xrt/drivers/android/olfx_yuv.h").exists())
        self.assertTrue((ROOT / "tests/olfx/test_yuv.c").exists())
        test_activity = activity.read_text()
        self.assertIn("resumeRuntimeIfActive()", test_activity)
        self.assertIn("errorMessage(message)", test_activity)
        manifest = ET.parse(ROOT / "src/xrt/targets/openxr_android/src/main/AndroidManifest.xml").getroot()
        acts = [a.get(ANDROID + "name") for a in manifest.findall("application/activity")]
        self.assertIn("org.freedesktop.monado.ipc.HandTestActivity", acts)

    def test_sbs_olfx_app_badge_replaces_text_watermark(self):
        aux = ROOT / "src/xrt/auxiliary/android/src/main/java/org/freedesktop/monado/auxiliary"
        self.assertFalse((aux / "OlfxWatermark.java").exists())
        badge = (aux / "GameBadgeOverlay.java").read_text()
        self.assertIn("OLFX_GAME_PACKAGE", badge)
        self.assertIn("getApplicationIcon(context.getPackageName())", badge)
        self.assertNotIn("getApplicationIcon(pkg)", badge)
        self.assertIn("eyeW = w / 2f", badge)
        view = (aux / "MonadoView.java").read_text()
        self.assertIn("GameBadgeOverlay.shouldShow(context, gamePackage)", view)
        self.assertIn("attachToActivity(", view)
        self.assertNotIn("OlfxWatermark", view)
        self.assertNotIn("OLFX - OPEN FREE", badge + view)
        impl = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/MonadoImpl.java").read_text()
        self.assertIn("OLFX_GAME_PACKAGE", impl)
        self.assertIn("/olfx/game/current", impl)
        self.assertIn("getOlfxDisplayConfig()", impl)
        aidl = (ROOT / "src/xrt/ipc/android/src/main/aidl/org/freedesktop/monado/ipc/IMonado.aidl").read_text()
        self.assertIn("Bundle getOlfxDisplayConfig();", aidl)
        client = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/Client.java").read_text()
        self.assertIn("monado.getOlfxDisplayConfig()", client)
        self.assertIn("MonadoView.attachToActivity(activity, sbs, activity.getPackageName())", client)


    def test_optimized_install_once_and_install_permission_resume(self):
        detail = (ROOT / "olfx/src/main/java/org/olfx/launcher/DetailActivity.kt").read_text()
        main = (ROOT / "olfx/src/main/java/org/olfx/launcher/MainActivity.kt").read_text()
        # Every listed title must be optimized once; the installed-APK marker prevents re-patching.
        self.assertIn("val marker = ApkPatch.marker(this, entry.pkg)", detail)
        self.assertIn("!ApkPatch.matchesRuntime(this, entry.pkg, marker)", detail)
        self.assertIn("R.string.optimize_and_install", detail)
        self.assertNotIn("R.string.launch_anyway", detail)
        self.assertIn("OptimizationGate.tryAcquire(entry.pkg)", detail)
        self.assertIn("OptimizationGate.release(entry.pkg)", detail)
        self.assertIn("val runtime = Runtimes.resolve(this, entry.pkg)", detail)
        self.assertIn("if (runtime == null)", detail)
        self.assertIn("OptimizationGate.tryAcquire(pkg)", main)
        self.assertNotIn("private fun chooseMode", main)
        self.assertNotIn("R.string.install_unchanged", main)
        self.assertIn("ACTION_MANAGE_UNKNOWN_APP_SOURCES", detail)
        self.assertIn("awaitingInstallPermission && packageManager.canRequestPackageInstalls()", detail)
        self.assertIn("awaitingInstallPermission && packageManager.canRequestPackageInstalls()", main)
        patcher = (ROOT / "olfx/src/main/java/org/olfx/launcher/ApkPatcher.kt").read_text()
        self.assertIn("android.os.StatFs", patcher)
        self.assertIn("availableProcessors()", patcher)
        self.assertIn('m["olfx-patched"] = mode', patcher)
        for manifest in (ROOT / "olfx/src/main/AndroidManifest.xml", ROOT / "src/xrt/targets/openxr_android/src/main/AndroidManifest.xml"):
            perms = [p.get(ANDROID + "name") for p in ET.parse(manifest).getroot().findall("uses-permission")]
            self.assertIn("android.permission.REQUEST_INSTALL_PACKAGES", perms)

    def test_click_mode_settings_and_pinch_accessibility(self):
        settings = (ROOT / "olfx/src/main/java/org/olfx/launcher/SettingsPage.kt").read_text()
        provider = (ROOT / "olfx/src/main/java/org/olfx/launcher/SettingsProvider.kt").read_text()
        model = (ROOT / "olfx/src/main/java/org/olfx/launcher/Model.kt").read_text()
        self.assertIn("R.string.click_mode_title", settings)
        self.assertIn("R.string.click_mode_screen", settings)
        self.assertIn("R.string.click_mode_pinch", settings)
        self.assertIn("ClickMode.PINCH", settings)
        self.assertIn("ENABLED_ACCESSIBILITY_SERVICES", settings)
        self.assertIn("org.freedesktop.monado.ipc.PinchClickService", settings)
        self.assertIn("SettingsRoute.CLICK", provider)
        self.assertIn('listOf("olfx", "click")', provider)
        self.assertIn("var clickMode", model)
        self.assertIn("private fun notifyClick()", model)
        hands = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/OlfxHands.java").read_text()
        self.assertIn("interface PinchListener", hands)
        self.assertIn("maybePinch(t, all.get(det[d]), now)", hands)
        self.assertTrue((ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/PinchGesture.java").exists())
        service = ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/PinchClickService.java"
        self.assertTrue(service.exists())
        self.assertIn("dispatchGesture", service.read_text())
        manifest = ET.parse(ROOT / "src/xrt/ipc/android/src/main/AndroidManifest.xml").getroot()
        svc = [x for x in manifest.findall("application/service") if x.get(ANDROID + "name") == "org.freedesktop.monado.ipc.PinchClickService"]
        self.assertEqual(len(svc), 1)
        self.assertEqual(svc[0].get(ANDROID + "permission"), "android.permission.BIND_ACCESSIBILITY_SERVICE")
        self.assertEqual(svc[0].get(ANDROID + "process"), ":monado")
        xml = (ROOT / "src/xrt/ipc/android/src/main/res/xml/olfx_pinch_click_service.xml").read_text()
        self.assertIn('android:canPerformGestures="true"', xml)
        pt = (ROOT / "src/xrt/targets/openxr_android/src/main/res/values-pt-rBR/olfx_strings.xml").read_text()
        self.assertIn("MODO DE CLICAR", pt)
        self.assertIn("CLIQUE NA TELA", pt)
        self.assertIn("CLIQUE POR MOVIMENTO DE PINÇA HAND TRACKING", pt)

    def test_openxr_hand_tracking_delivery_and_foreground_service(self):
        gradle = (ROOT / "src/xrt/targets/openxr_android/build.gradle").read_text()
        self.assertIn("-DXRT_FEATURE_OPENXR_HAND_TRACKING_EXT=ON", gradle)
        self.assertIn("-DXRT_FEATURE_OPENXR_HAND_TRACKING_DATA_SOURCE_EXT=ON", gradle)
        self.assertIn("-DXRT_FEATURE_OPENXR_INTERACTION_EXT_HAND=ON", gradle)
        native = (ROOT / "src/xrt/drivers/android/olfx_hands.c").read_text()
        self.assertIn("Java_org_freedesktop_monado_ipc_OlfxHands_nativeHandTrackingStatus", native)
        self.assertIn("XRT_FEATURE_OPENXR_HAND_TRACKING_EXT", native)
        service = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/MonadoService.kt").read_text()
        self.assertNotIn("FOREGROUND_SERVICE_TYPE_MANIFEST", service)
        self.assertIn("FOREGROUND_SERVICE_TYPE_CAMERA", service)
        self.assertIn("FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE", service)
        self.assertIn("OlfxHands.start(this)", service)
        manifest = ET.parse(ROOT / "src/xrt/ipc/android/src/main/AndroidManifest.xml").getroot()
        perms = [p.get(ANDROID + "name") for p in manifest.findall("uses-permission")]
        self.assertIn("android.permission.FOREGROUND_SERVICE_CAMERA", perms)
        self.assertIn("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE", perms)
        monado = [x for x in manifest.findall("application/service") if x.get(ANDROID + "name") == "org.freedesktop.monado.ipc.MonadoService"][0]
        self.assertEqual(monado.get(ANDROID + "foregroundServiceType"), "connectedDevice|camera")
        test = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/HandTestActivity.java").read_text()
        self.assertIn("openXrStatus()", test)
        self.assertIn("nativeHandTrackingStatus()", test)

    def test_launch_animation_uses_game_image_outside_render_loop(self):
        detail = (ROOT / "olfx/src/main/java/org/olfx/launcher/DetailActivity.kt").read_text()
        animation = (ROOT / "olfx/src/main/java/org/olfx/launcher/LaunchAnimation.kt").read_text()
        self.assertIn("LaunchAnimation.show(this, entry)", detail)
        self.assertIn("AppScanner.playIntent(this, entry)", detail)
        self.assertIn("setImageDrawable(entry.icon)", animation)
        self.assertIn("AnimatorSet", animation)
        self.assertIn("onPause()", detail)
        self.assertNotIn("MonadoView", animation)
        self.assertIn("ActivityOptions.makeCustomAnimation", detail)
        self.assertIn("startActivity(i, options.toBundle())", detail)

    def test_play_permission_denial_and_camera_prompt_are_actionable(self):
        detail = (ROOT / "olfx/src/main/java/org/olfx/launcher/DetailActivity.kt").read_text()
        model = (ROOT / "olfx/src/main/java/org/olfx/launcher/Model.kt").read_text()
        self.assertIn("catch (ex: SecurityException)", detail)
        self.assertIn("R.string.launch_permission_denied", detail)
        self.assertIn("requestPermissions(arrayOf(Manifest.permission.CAMERA)", detail)
        self.assertIn("launchGame(cameraPrompted = true)", detail)
        self.assertIn("!ai.exported || !ai.enabled", model)
        self.assertIn("checkPermission(permission, ctx.packageName)", model)
        self.assertIn("Intent.CATEGORY_LEANBACK_LAUNCHER", model)
        base = strings("values")
        self.assertIn("launch_permission_denied", base)
        self.assertIn("camera_needed_for_hands", base)

    def test_controller_only_games_get_hand_interaction_rebinding(self):
        rebinding = ROOT / "src/xrt/auxiliary/bindings/rebindings/50-touch_to_hand_interaction.json"
        self.assertTrue(rebinding.exists())
        text = rebinding.read_text()
        self.assertIn("touch_to_olfx_hand_interaction", text)
        self.assertIn("/interaction_profiles/oculus/touch_controller", text)
        self.assertIn("/interaction_profiles/ext/hand_interaction_ext", text)
        self.assertIn("/input/pinch_ext/value", text)
        self.assertIn("/input/grasp_ext/value", text)
        cemu = (ROOT / "src/xrt/drivers/ht_ctrl_emu/ht_ctrl_emu.cpp").read_text()
        self.assertIn("cemu_devices_create_ext_hand_interaction", cemu)
        self.assertIn("XRT_DEVICE_EXT_HAND_INTERACTION", cemu)
        self.assertIn("XRT_INPUT_HAND_PINCH_VALUE", cemu)
        self.assertIn("XRT_INPUT_HAND_GRASP_VALUE", cemu)
        legacy = (ROOT / "src/xrt/targets/common/target_builder_legacy.c").read_text()
        self.assertIn("cemu_devices_create_ext_hand_interaction(head, hands, interaction)", legacy)
        self.assertIn("conforming_left_ht = NULL", legacy)
        self.assertIn("conforming_right_ht = NULL", legacy)
        self.assertIn("decide_ext_pinch", cemu)
        self.assertIn("0.46f : 0.30f", cemu)
        gradle = (ROOT / "src/xrt/targets/openxr_android/build.gradle").read_text()
        self.assertIn("-DXRT_FEATURE_OPENXR_INTERACTION_EXT_PALM_POSE=ON", gradle)
        self.assertIn("flavor.versionCode = 16", gradle)

    def test_camera_grant_promotes_fgs_and_pose_fallback_keeps_hands_visible(self):
        permission = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/CameraPermissionActivity.java").read_text()
        service = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/MonadoService.kt").read_text()
        hands = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/OlfxHands.java").read_text()
        self.assertIn("MonadoService.refreshForeground(this)", permission)
        self.assertIn("fun refreshForeground(context: android.content.Context)", service)
        self.assertIn("fallbackPoseLandmarks", hands)
        self.assertIn("q = fallbackPoseLandmarks(t, all.get(det[d]), world.get(det[d]))", hands)
        self.assertIn("lastPublishAgeMs()", hands)
        self.assertIn("HandFrameClock.normalizeTimestampNs", hands)
        self.assertIn("SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME", hands)
        self.assertIn("scheduleRuntimeRetry", hands)
        detail = (ROOT / "olfx/src/main/java/org/olfx/launcher/DetailActivity.kt").read_text()
        hand_runtime = (ROOT / "olfx/src/main/java/org/olfx/launcher/HandRuntime.kt").read_text()
        self.assertIn("HandRuntime.startIfEnabled(this)", detail)
        self.assertIn("startForegroundService", hand_runtime)
        self.assertTrue((ROOT / "olfx/src/test/java/org/freedesktop/monado/ipc/HandFrameClockTest.kt").exists())
        self.assertTrue((ROOT / "tests/tests_olfx_hand_tracking.cpp").exists())


    def test_multi_runtime_hand_routes_and_ovr_compat(self):
        runtimes = (ROOT / "olfx/src/main/java/org/olfx/launcher/Runtimes.kt").read_text()
        self.assertIn("cameraHands: Boolean = false", runtimes)
        self.assertIn("if (cameraHands)", runtimes)
        self.assertIn("RuntimeChoice.OPENXR_COMPAT -> runtimes.firstOrNull { it.pkg == ownPackage }", runtimes)
        policy_test = (ROOT / "olfx/src/test/java/org/olfx/launcher/RuntimePolicyTest.kt").read_text()
        self.assertIn("questAutoWithCameraHandsUsesMonado", policy_test)
        self.assertIn("explicitOculusOverridesCameraHands", policy_test)
        patcher = (ROOT / "olfx/src/main/java/org/olfx/launcher/ApkPatcher.kt").read_text()
        self.assertIn("RuntimeChoice.OPENXR_COMPAT", patcher)
        self.assertIn("libOVRPlugin.so", patcher)
        self.assertIn("olfx-loader=", (ROOT / "olfx/src/main/java/org/olfx/launcher/Model.kt").read_text())
        gradle = (ROOT / "src/xrt/targets/openxr_android/build.gradle").read_text()
        self.assertIn("ovrplugin_compat", gradle)
        self.assertIn("-DXRT_FEATURE_OPENXR_HAND_TRACKING_AIM_FB=ON", gradle)
        self.assertIn("-DXRT_FEATURE_OPENXR_HAND_TRACKING_CAPSULES_FB=ON", gradle)
        ext = (ROOT / "src/xrt/state_trackers/oxr/extension_support/oxr_extension_support.py").read_text()
        self.assertIn("XR_FB_hand_tracking_aim", ext)
        self.assertIn("XR_FB_hand_tracking_capsules", ext)
        oxr = (ROOT / "src/xrt/state_trackers/oxr/oxr_hand_tracking.c").read_text()
        self.assertIn("XrHandTrackingAimStateFB", oxr)
        self.assertIn("XrHandTrackingCapsulesStateFB", oxr)
        shim = ROOT / "third_party/ovrplugin-openxr-shim"
        self.assertTrue((shim / "LICENSE").exists())
        self.assertTrue((ROOT / "olfx/src/main/assets/licenses/ovrplugin-openxr-shim.txt").exists())
        self.assertIn("da7dd08640668eb5ce466b7562e2fe7c4264d68e", (shim / "UPSTREAM.md").read_text())
        hands = (shim / "shim/src/olfx_hands.c").read_text()
        self.assertIn("sizeof(OlfxHandState) == 512", hands)
        self.assertIn("xrCreateHandTrackerEXT", hands)
        self.assertIn("xrLocateHandJointsEXT", hands)
        self.assertIn("ovrp_GetHandState", hands)
        probe = (ROOT / "src/xrt/auxiliary/android/olfx_openxr_probe.c").read_text()
        self.assertIn("xrCreateHandTrackerEXT", probe)
        self.assertIn("xrLocateHandJointsEXT", probe)
        self.assertIn("XR_MND_HEADLESS_EXTENSION_NAME", probe)
        self.assertTrue((ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/OlfxOpenXrProbe.java").exists())

    def test_ovrp_stub_policy_is_generated_and_context_dex_is_shipped(self):
        import subprocess, sys
        r = subprocess.run([sys.executable, "-I", str(ROOT / "tools/ovr_shim/gen_policy.py"), "--check"],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        dex = (ROOT / "olfx/src/main/assets/ovr/olfx_ctx.dex").read_bytes()
        self.assertTrue(dex.startswith(b"dex\n"))
        self.assertIn(b"createPackageContext", dex)
        guard = (ROOT / "olfx/src/main/assets/ovr/libovrplatformloader_guard_arm64.so").read_bytes()
        self.assertTrue(guard.startswith(b"\x7fELF"))
        for sym in (b"ovr_PlatformInitializeAndroid", b"ovr_PlatformInitializeAndroidAsynchronous",
                    b"libovrplatformloader_real.so"):
            self.assertIn(sym, guard)
        patcher = (ROOT / "olfx/src/main/java/org/olfx/launcher/ApkPatcher.kt").read_text()
        self.assertIn("libovrplatformloader_real.so", patcher)
        self.assertIn("libovrplatformloader_guard_arm64.so", patcher)
        # the guard must not fake store state
        src = (ROOT / "tools/ovr_guard/guard.c").read_text()
        forwarding = src.split("uint64_t ovr_Entitlement_GetIsViewerEntitled(void)", 1)[1].split("\n}", 1)[0]
        self.assertIn('dlsym(g_real, "ovr_Entitlement_GetIsViewerEntitled")', forwarding)
        self.assertIn("CALL_CONTAINED(r = real(), aborted)", forwarding)
        self.assertEqual(set(re.findall(r"\breturn ([^;]+);", forwarding)), {"0", "r"})

    def test_hand_rotation_and_display_geometry_are_shared(self):
        hands = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/OlfxHands.java").read_text()
        geometry = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/HandImageGeometry.java").read_text()
        test = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/HandTestActivity.java").read_text()
        self.assertIn("uprightLandmarks(result)", hands)
        self.assertIn("publish(result, upright, imageTimestampNs)", hands)
        self.assertIn("onHandFrame(frame, upright, took, fpsEma)", hands)
        self.assertIn("registerDisplayListener(displayListener, handler)", hands)
        self.assertIn("HandImageGeometry.rotateX", hands)
        self.assertIn("HandImageGeometry.focalPixels", hands)
        self.assertIn("ORIGINAL, unrotated image coordinate system", geometry)
        self.assertIn("SCREEN_ORIENTATION_SENSOR_LANDSCAPE", test)
        self.assertIn("preview.setFrame(frame, landmarks)", test)
        self.assertTrue((ROOT / "olfx/src/test/java/org/freedesktop/monado/ipc/HandImageGeometryTest.kt").exists())

    def test_hand_prediction_is_bounded_and_transforms_once(self):
        native = (ROOT / "src/xrt/drivers/android/olfx_hands.c").read_text()
        self.assertIn("OLFX_MAX_LOOKAHEAD_NS (45 * 1000 * 1000)", native)
        self.assertIn("math_quat_slerp(&prev, &cur, t, &predicted)", native)
        self.assertIn("out_value->hand_pose.pose = (struct xrt_pose)XRT_POSE_IDENTITY", native)
        self.assertIn("math_pose_transform(&head, &local, &world)", native)

    def test_vrmobile_frame_pacing_is_bounded_and_display_timed(self):
        window = (ROOT / "src/xrt/compositor/main/comp_window_android.c").read_text()
        self.assertIn("COMP_TARGET_USE_DISPLAY_IF_AVAILABLE", window)
        impl = (ROOT / "src/xrt/ipc/android/src/main/java/org/freedesktop/monado/ipc/MonadoImpl.java").read_text()
        self.assertIn('XRT_COMPOSITOR_PRESENT_MODE", "fifo"', impl)
        self.assertIn('XRT_COMPOSITOR_PREFERRED_IMAGE_COUNT", "2"', impl)


if __name__ == "__main__":
    unittest.main()
