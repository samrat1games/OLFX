// OLFX: Oculus Go (Mobile SDK VrApi) backend of the living-room launcher scene. Same room/panel as the Pico OpenXR
// backend (scene_core.h). Written against VrApi 1.9.0 headers (VrApi.h "Android VR life cycle" sample).
#include "scene_core.h"
#include <android/native_window_jni.h>
#include "VrApi.h"
#include "VrApi_Helpers.h"
#include "VrApi_Input.h"
#include "VrApi_SystemUtils.h"

static M4 fromOvr(const ovrMatrix4f &o) { M4 r; for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) r.m[c * 4 + rr] = o.M[rr][c]; return r; }

static void run(JNIEnv *env, jobject activity, jobject surface, jobject assetMgr) {
    AAssetManager *am = AAssetManager_fromJava(env, assetMgr);
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) { LOGW("no native window"); return; }
    ovrJava java; java.Vm = g_vm; java.Env = env; java.ActivityObject = activity;
    const ovrInitParms initParms = vrapi_DefaultInitParms(&java);
    if (vrapi_Initialize(&initParms) != VRAPI_INITIALIZE_SUCCESS) { LOGW("vrapi_Initialize failed"); ANativeWindow_release(window); return; }

    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(dpy, nullptr, nullptr);
    const EGLint cfgA[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 0, EGL_NONE};
    EGLConfig cfg; EGLint nc = 0; eglChooseConfig(dpy, cfgA, &cfg, 1, &nc);
    const EGLint ctxA[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxA);
    const EGLint pbA[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE}; EGLSurface pb = eglCreatePbufferSurface(dpy, cfg, pbA);
    if (nc < 1 || ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, pb, pb, ctx)) { LOGW("EGL setup failed"); vrapi_Shutdown(); ANativeWindow_release(window); return; }

    const int ew = vrapi_GetSystemPropertyInt(&java, VRAPI_SYS_PROP_SUGGESTED_EYE_TEXTURE_WIDTH);
    const int eh = vrapi_GetSystemPropertyInt(&java, VRAPI_SYS_PROP_SUGGESTED_EYE_TEXTURE_HEIGHT);
    ovrTextureSwapChain *chain[VRAPI_FRAME_LAYER_EYE_MAX]; GLuint depth[VRAPI_FRAME_LAYER_EYE_MAX];
    for (int e = 0; e < VRAPI_FRAME_LAYER_EYE_MAX; e++) {
        chain[e] = vrapi_CreateTextureSwapChain(VRAPI_TEXTURE_TYPE_2D, VRAPI_TEXTURE_FORMAT_8888, ew, eh, 1, true);
        glGenRenderbuffers(1, &depth[e]); glBindRenderbuffer(GL_RENDERBUFFER, depth[e]);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, ew, eh);
    }
    SceneCore core; core.init(env, am);
    jfloatArray tmArr = env->NewFloatArray(16);
    GLuint fbo; glGenFramebuffers(1, &fbo);

    ovrModeParms mp = vrapi_DefaultModeParms(&java);
    mp.Flags |= VRAPI_MODE_FLAG_NATIVE_WINDOW;
    mp.Flags &= ~VRAPI_MODE_FLAG_RESET_WINDOW_FULLSCREEN;
    mp.Display = (size_t)dpy; mp.WindowSurface = (size_t)window; mp.ShareContext = (size_t)ctx;
    ovrMobile *ovr = vrapi_EnterVrMode(&mp);
    if (!ovr) { LOGW("vrapi_EnterVrMode failed"); }
    else {
        vrapi_SetTrackingTransform(ovr, vrapi_GetTrackingTransform(ovr, VRAPI_TRACKING_TRANSFORM_SYSTEM_CENTER_EYE_LEVEL));
        const float EYE_HEIGHT = 1.6f;       // stage floor is 1.6 m below the eye-level origin
        ovrDeviceID remote = ovrDeviceIdType_Invalid; bool prevDown = false;
        for (long long frame = 1; !g_stop.load(); frame++) {
            const double predicted = vrapi_GetPredictedDisplayTime(ovr, frame);
            const ovrTracking2 tracking = vrapi_GetPredictedTracking2(ovr, predicted);

            // Gear VR / Go remote: 3-DoF pose + trigger/touchpad click -> pointer on the panel
            if (remote == ovrDeviceIdType_Invalid) {
                for (uint32_t i = 0;; i++) {
                    ovrInputCapabilityHeader caps;
                    if (vrapi_EnumerateInputDevices(ovr, i, &caps) < 0) break;
                    if (caps.Type == ovrControllerType_TrackedRemote) { remote = caps.DeviceID; break; }
                }
            }
            bool haveHit = false; M4 hitModel = ident(); float hu = 0, hv = 0, down = false;
            if (remote != ovrDeviceIdType_Invalid) {
                ovrInputStateTrackedRemote st; st.Header.ControllerType = ovrControllerType_TrackedRemote;
                ovrTracking rt;
                if (vrapi_GetCurrentInputState(ovr, remote, &st.Header) >= 0 && vrapi_GetInputTrackingState(ovr, remote, predicted, &rt) >= 0) {
                    const ovrQuatf q = rt.HeadPose.Pose.Orientation;
                    float x = q.x, y = q.y, z = q.z, w = q.w;
                    float dx = -(2 * (x * z + y * w)), dy = -(2 * (y * z - x * w)), dz = -(1 - 2 * (x * x + y * y));
                    // 3-DoF remote: the hand rests at a fixed offset from the eye (right, below, in front)
                    float ox = 0.22f, oy = EYE_HEIGHT - 0.35f, oz = -0.15f; float u, v; M4 hm;
                    down = (st.Buttons & (ovrButton_A | ovrButton_Enter)) != 0;
                    if (panelHit(ox, oy, oz, dx, dy, dz, &u, &v, &hm)) { haveHit = true; hu = u; hv = v; hitModel = hm; }
                }
            }
            if (haveHit) { int a = down ? (prevDown ? 1 : 0) : (prevDown ? 2 : -1); if (a >= 0) callPointer(hu, 1.0f - hv, a); prevDown = down; }
            else if (prevDown) { callPointer(0, 0, 3); prevDown = false; }

            JNIEnv *e2 = env; e2->CallStaticVoidMethod(g_host, m_panelUpdate, tmArr); float tm4[16]; e2->GetFloatArrayRegion(tmArr, 0, 16, tm4);

            ovrLayerProjection2 layer = vrapi_DefaultLayerProjection2();
            layer.HeadPose = tracking.HeadPose;
            for (int e = 0; e < VRAPI_FRAME_LAYER_EYE_MAX; e++) {
                const int idx = (int)(frame % vrapi_GetTextureSwapChainLength(chain[e]));
                const unsigned int tex = vrapi_GetTextureSwapChainHandle(chain[e], idx);
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth[e]);
                glViewport(0, 0, ew, eh);
                M4 vpm = mul(fromOvr(tracking.Eye[e].ProjectionMatrix), mul(fromOvr(tracking.Eye[e].ViewMatrix), translate(0, -EYE_HEIGHT, 0)));
                core.drawScene(vpm, tm4, haveHit, hitModel);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                layer.Textures[e].ColorSwapChain = chain[e];
                layer.Textures[e].SwapChainIndex = idx;
                layer.Textures[e].TexCoordsFromTanAngles = ovrMatrix4f_TanAngleMatrixFromProjection(&tracking.Eye[e].ProjectionMatrix);
            }
            glFinish();
            const ovrLayerHeader2 *layers[] = {&layer.Header};
            ovrSubmitFrameDescription2 fd = {};
            fd.FrameIndex = frame; fd.DisplayTime = predicted; fd.LayerCount = 1; fd.Layers = layers;
            vrapi_SubmitFrame2(ovr, &fd);
        }
        vrapi_LeaveVrMode(ovr);
    }
    for (int e = 0; e < VRAPI_FRAME_LAYER_EYE_MAX; e++) vrapi_DestroyTextureSwapChain(chain[e]);
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); eglDestroySurface(dpy, pb); eglDestroyContext(dpy, ctx);
    vrapi_Shutdown(); ANativeWindow_release(window);
    LOGI("go scene ended");
}

extern "C" JNIEXPORT void JNICALL Java_org_olfx_launcher_SceneHost_nativeRunGo(JNIEnv *env, jclass, jobject activity, jobject surface, jobject assets) {
    bindHost(env);
    run(env, activity, surface, assets);
}
extern "C" JNIEXPORT void JNICALL Java_org_olfx_launcher_SceneHost_nativeStopGo(JNIEnv *, jclass) { g_stop = true; }
