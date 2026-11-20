// OLFX: CC0 living-room OpenXR scene for Pico headsets. The launcher UI is drawn by Java into a SurfaceTexture and shown
// as a floating panel; the controller ray is turned into touch events. Exits when a game is launched (stop flag).
// OpenXR is loaded with dlopen() from the bundled Khronos loader so nothing links against it.
#include <unistd.h>
#include "scene_core.h"
#include <dlfcn.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

static M4 fromPose(const XrPosef &p) {
    float x = p.orientation.x, y = p.orientation.y, z = p.orientation.z, w = p.orientation.w;
    M4 r = ident();
    r.m[0] = 1 - 2 * (y * y + z * z); r.m[1] = 2 * (x * y + z * w); r.m[2] = 2 * (x * z - y * w);
    r.m[4] = 2 * (x * y - z * w); r.m[5] = 1 - 2 * (x * x + z * z); r.m[6] = 2 * (y * z + x * w);
    r.m[8] = 2 * (x * z + y * w); r.m[9] = 2 * (y * z - x * w); r.m[10] = 1 - 2 * (x * x + y * y);
    r.m[12] = p.position.x; r.m[13] = p.position.y; r.m[14] = p.position.z;
    return r;
}
static M4 projection(const XrFovf &f, float n, float fa) {
    float l = tanf(f.angleLeft), r = tanf(f.angleRight), u = tanf(f.angleUp), d = tanf(f.angleDown);
    M4 p{};
    p.m[0] = 2 / (r - l); p.m[5] = 2 / (u - d); p.m[8] = (r + l) / (r - l); p.m[9] = (u + d) / (u - d);
    p.m[10] = -(fa + n) / (fa - n); p.m[11] = -1; p.m[14] = -2 * fa * n / (fa - n);
    return p;
}


// ---------------- OpenXR via dlopen ----------------
static PFN_xrGetInstanceProcAddr xrGIPA;
#define XR_FUNCS(X) X(xrCreateInstance) X(xrDestroyInstance) X(xrGetSystem) X(xrCreateSession) X(xrDestroySession) \
    X(xrEnumerateReferenceSpaces) X(xrCreateReferenceSpace) X(xrDestroySpace) X(xrEnumerateSwapchainFormats) X(xrCreateSwapchain) \
    X(xrDestroySwapchain) X(xrEnumerateSwapchainImages) X(xrAcquireSwapchainImage) X(xrWaitSwapchainImage) X(xrReleaseSwapchainImage) \
    X(xrBeginSession) X(xrEndSession) X(xrRequestExitSession) X(xrWaitFrame) X(xrBeginFrame) X(xrEndFrame) X(xrLocateViews) \
    X(xrEnumerateViewConfigurationViews) X(xrPollEvent) X(xrStringToPath) X(xrCreateActionSet) X(xrCreateAction) X(xrSuggestInteractionProfileBindings) \
    X(xrAttachSessionActionSets) X(xrCreateActionSpace) X(xrSyncActions) X(xrGetActionStateFloat) X(xrGetActionStateBoolean) X(xrLocateSpace) \
    X(xrEnumerateInstanceExtensionProperties) X(xrGetOpenGLESGraphicsRequirementsKHR) X(xrDestroyActionSet)
#define DECL(n) static PFN_##n n;
XR_FUNCS(DECL)
static bool loadFuncs(XrInstance inst) {
    bool ok = true;
#define LOADF(n) if (XR_FAILED(xrGIPA(inst, #n, (PFN_xrVoidFunction *)&n)) || !n) { LOGW("missing %s", #n); ok = false; }
    XR_FUNCS(LOADF)
    return ok;
}

struct Eye { XrSwapchain sc = XR_NULL_HANDLE; int w = 0, h = 0; std::vector<XrSwapchainImageOpenGLESKHR> imgs; GLuint depth = 0; };

static void run(JNIEnv *env, jobject activity, jobject assetMgr, const char *loaderPath) {
    AAssetManager *am = AAssetManager_fromJava(env, assetMgr);
    void *lib = dlopen(loaderPath, RTLD_NOW | RTLD_LOCAL);
    if (!lib) { LOGW("cannot load OpenXR loader: %s", dlerror()); return; }
    xrGIPA = (PFN_xrGetInstanceProcAddr)dlsym(lib, "xrGetInstanceProcAddr");
    if (!xrGIPA) { LOGW("no xrGetInstanceProcAddr"); return; }
    PFN_xrInitializeLoaderKHR initLoader = nullptr;
    xrGIPA(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction *)&initLoader);
    if (initLoader) {
        XrLoaderInitInfoAndroidKHR li{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR}; li.applicationVM = g_vm; li.applicationContext = activity;
        initLoader((XrLoaderInitInfoBaseHeaderKHR *)&li);
    }
    PFN_xrEnumerateInstanceExtensionProperties enumExt = nullptr; xrGIPA(XR_NULL_HANDLE, "xrEnumerateInstanceExtensionProperties", (PFN_xrVoidFunction *)&enumExt);
    PFN_xrCreateInstance createInst = nullptr; xrGIPA(XR_NULL_HANDLE, "xrCreateInstance", (PFN_xrVoidFunction *)&createInst);
    if (!enumExt || !createInst) { LOGW("loader missing entry points"); return; }
    uint32_t ne = 0; enumExt(nullptr, 0, &ne, nullptr);
    std::vector<XrExtensionProperties> exts(ne, {XR_TYPE_EXTENSION_PROPERTIES}); enumExt(nullptr, ne, &ne, exts.data());
    auto has = [&](const char *n) { for (auto &e : exts) if (!strcmp(e.extensionName, n)) return true; return false; };
    std::vector<const char *> want;
    if (!has(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME)) { LOGW("runtime lacks XR_KHR_opengl_es_enable"); return; }
    want.push_back(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME);
    if (has(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME)) want.push_back(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME);
    bool bd = has("XR_BD_controller_interaction"); if (bd) want.push_back("XR_BD_controller_interaction");
    XrInstanceCreateInfoAndroidKHR ai{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR}; ai.applicationVM = g_vm; ai.applicationActivity = activity;
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = (want.size() > 1 && !strcmp(want[1], XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME)) ? &ai : nullptr;
    strcpy(ci.applicationInfo.applicationName, "OLFX Scene"); ci.applicationInfo.applicationVersion = 1;
    strcpy(ci.applicationInfo.engineName, "olfx"); ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    ci.enabledExtensionCount = (uint32_t)want.size(); ci.enabledExtensionNames = want.data();
    XrInstance inst = XR_NULL_HANDLE; XrResult r = createInst(&ci, &inst);
    if (XR_FAILED(r)) { LOGW("xrCreateInstance failed %d", (int)r); return; }
    if (!loadFuncs(inst)) { return; }
    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO}; sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId sys; if (XR_FAILED(xrGetSystem(inst, &sgi, &sys))) { LOGW("no HMD system"); return; }
    XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR}; xrGetOpenGLESGraphicsRequirementsKHR(inst, sys, &req);

    // EGL (surfaceless-style: tiny pbuffer)
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(dpy, nullptr, nullptr);
    const EGLint cfgA[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig cfg; EGLint nc = 0; eglChooseConfig(dpy, cfgA, &cfg, 1, &nc);
    const EGLint ctxA[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxA);
    const EGLint pbA[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE}; EGLSurface pb = eglCreatePbufferSurface(dpy, cfg, pbA);
    if (nc < 1 || ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, pb, pb, ctx)) { LOGW("EGL setup failed"); return; }

    XrGraphicsBindingOpenGLESAndroidKHR gb{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR}; gb.display = dpy; gb.config = cfg; gb.context = ctx;
    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO}; sci.next = &gb; sci.systemId = sys;
    XrSession session; if (XR_FAILED(r = xrCreateSession(inst, &sci, &session))) { LOGW("xrCreateSession failed %d", (int)r); return; }

    // spaces
    XrSpace space = XR_NULL_HANDLE; float floorOffset = 0;
    {   XrReferenceSpaceCreateInfo rs{XR_TYPE_REFERENCE_SPACE_CREATE_INFO}; rs.poseInReferenceSpace.orientation.w = 1;
        rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
        if (XR_FAILED(xrCreateReferenceSpace(session, &rs, &space))) {
            rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL; xrCreateReferenceSpace(session, &rs, &space); floorOffset = 1.6f; }
    }
    // views / swapchains
    uint32_t nv = 0; xrEnumerateViewConfigurationViews(inst, sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &nv, nullptr);
    std::vector<XrViewConfigurationView> vcv(nv, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
    xrEnumerateViewConfigurationViews(inst, sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, nv, &nv, vcv.data());
    uint32_t nf = 0; xrEnumerateSwapchainFormats(session, 0, &nf, nullptr); std::vector<int64_t> fmts(nf); xrEnumerateSwapchainFormats(session, nf, &nf, fmts.data());
    int64_t fmt = fmts.empty() ? GL_RGBA8 : fmts[0];
    for (int64_t f : fmts) if (f == GL_RGBA8) fmt = f;
    std::vector<Eye> eyes(nv);
    for (uint32_t i = 0; i < nv; i++) {
        XrSwapchainCreateInfo sc{XR_TYPE_SWAPCHAIN_CREATE_INFO}; sc.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        sc.format = fmt; sc.sampleCount = 1; sc.width = vcv[i].recommendedImageRectWidth; sc.height = vcv[i].recommendedImageRectHeight; sc.faceCount = 1; sc.arraySize = 1; sc.mipCount = 1;
        xrCreateSwapchain(session, &sc, &eyes[i].sc); eyes[i].w = sc.width; eyes[i].h = sc.height;
        uint32_t n = 0; xrEnumerateSwapchainImages(eyes[i].sc, 0, &n, nullptr);
        eyes[i].imgs.assign(n, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        xrEnumerateSwapchainImages(eyes[i].sc, n, &n, (XrSwapchainImageBaseHeader *)eyes[i].imgs.data());
        glGenRenderbuffers(1, &eyes[i].depth); glBindRenderbuffer(GL_RENDERBUFFER, eyes[i].depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, eyes[i].w, eyes[i].h);
    }
    // input actions
    XrActionSet set; { XrActionSetCreateInfo a{XR_TYPE_ACTION_SET_CREATE_INFO}; strcpy(a.actionSetName, "main"); strcpy(a.localizedActionSetName, "Main"); xrCreateActionSet(inst, &a, &set); }
    XrPath hands[2]; xrStringToPath(inst, "/user/hand/left", &hands[0]); xrStringToPath(inst, "/user/hand/right", &hands[1]);
    auto mk = [&](const char *nm, XrActionType t, bool sub) { XrAction a; XrActionCreateInfo c{XR_TYPE_ACTION_CREATE_INFO}; strcpy(c.actionName, nm); strcpy(c.localizedActionName, nm);
        c.actionType = t; if (sub) { c.countSubactionPaths = 2; c.subactionPaths = hands; } xrCreateAction(set, &c, &a); return a; };
    XrAction aAim = mk("aim", XR_ACTION_TYPE_POSE_INPUT, true), aTrig = mk("trigger", XR_ACTION_TYPE_FLOAT_INPUT, true), aSel = mk("select", XR_ACTION_TYPE_BOOLEAN_INPUT, true);
    auto suggest = [&](const char *prof, bool useSelect) {
        XrPath p; xrStringToPath(inst, prof, &p); std::vector<XrActionSuggestedBinding> b; const char *side[2] = {"left", "right"};
        for (int h = 0; h < 2; h++) {
            XrPath pa, pt; std::string base = std::string("/user/hand/") + side[h] + "/input/";
            xrStringToPath(inst, (base + "aim/pose").c_str(), &pa); b.push_back({aAim, pa});
            xrStringToPath(inst, (base + (useSelect ? "select/click" : "trigger/value")).c_str(), &pt); b.push_back({useSelect ? aSel : aTrig, pt});
        }
        XrInteractionProfileSuggestedBinding s{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING}; s.interactionProfile = p; s.suggestedBindings = b.data(); s.countSuggestedBindings = (uint32_t)b.size();
        XrResult rr = xrSuggestInteractionProfileBindings(inst, &s); LOGI("bindings %s -> %d", prof, (int)rr);
    };
    suggest("/interaction_profiles/khr/simple_controller", true);
    if (bd) { suggest("/interaction_profiles/bytedance/pico4_controller", false); suggest("/interaction_profiles/bytedance/pico_neo3_controller", false); }
    XrSessionActionSetsAttachInfo att{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO}; att.countActionSets = 1; att.actionSets = &set; xrAttachSessionActionSets(session, &att);
    XrSpace aimSpace[2]; for (int h = 0; h < 2; h++) { XrActionSpaceCreateInfo a{XR_TYPE_ACTION_SPACE_CREATE_INFO}; a.action = aAim; a.subactionPath = hands[h]; a.poseInActionSpace.orientation.w = 1; xrCreateActionSpace(session, &a, &aimSpace[h]); }

    SceneCore core; core.init(env, am);
    jfloatArray tmArr = env->NewFloatArray(16);

    GLuint fbo; glGenFramebuffers(1, &fbo);
    bool running = false, exiting = false; XrSessionState state = XR_SESSION_STATE_UNKNOWN; bool prevDown[2] = {false, false}; int activeHand = 1;
    while (!g_stop.load() || state != XR_SESSION_STATE_UNKNOWN) {
        if (g_stop.load() && !exiting && running) { xrRequestExitSession(session); exiting = true; }
        if (g_stop.load() && !running) break;
        XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
        while (xrPollEvent(inst, &ev) == XR_SUCCESS) {
            if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                state = ((XrEventDataSessionStateChanged *)&ev)->state; LOGI("session state %d", (int)state);
                if (state == XR_SESSION_STATE_READY) { XrSessionBeginInfo b{XR_TYPE_SESSION_BEGIN_INFO}; b.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO; xrBeginSession(session, &b); running = true; }
                else if (state == XR_SESSION_STATE_STOPPING) { xrEndSession(session); running = false; }
                else if (state == XR_SESSION_STATE_EXITING || state == XR_SESSION_STATE_LOSS_PENDING) { g_stop = true; running = false; }
            }
            ev = {XR_TYPE_EVENT_DATA_BUFFER};
        }
        if (g_stop.load() && !running) break;
        if (!running) { usleep(10000); continue; }
        XrFrameState fs{XR_TYPE_FRAME_STATE}; xrWaitFrame(session, nullptr, &fs); XrFrameBeginInfo fb{XR_TYPE_FRAME_BEGIN_INFO}; xrBeginFrame(session, &fb);
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION}; std::vector<XrCompositionLayerProjectionView> pv(nv, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW});
        const XrCompositionLayerBaseHeader *layers[1]; uint32_t layerCount = 0;
        if (fs.shouldRender) {
            // input -> pointer
            XrActiveActionSet as{set, XR_NULL_PATH}; XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO}; si.countActiveActionSets = 1; si.activeActionSets = &as; xrSyncActions(session, &si);
            float hitU = -1, hitV = -1; bool down = false; M4 hitModel = ident(); bool haveHit = false;
            for (int h = 0; h < 2; h++) {
                XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO}; gi.subactionPath = hands[h];
                gi.action = aTrig; XrActionStateFloat tf{XR_TYPE_ACTION_STATE_FLOAT}; xrGetActionStateFloat(session, &gi, &tf);
                gi.action = aSel; XrActionStateBoolean sb{XR_TYPE_ACTION_STATE_BOOLEAN}; xrGetActionStateBoolean(session, &gi, &sb);
                bool pressed = (tf.isActive && tf.currentState > 0.6f) || (sb.isActive && sb.currentState);
                XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION}; xrLocateSpace(aimSpace[h], space, fs.predictedDisplayTime, &loc);
                if (!(loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) || !(loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) continue;
                float oy = loc.pose.position.y + floorOffset, ox = loc.pose.position.x, oz = loc.pose.position.z; M4 pm = fromPose(loc.pose);
                float u, v; M4 hm;
                if (!panelHit(ox, oy, oz, -pm.m[8], -pm.m[9], -pm.m[10], &u, &v, &hm)) continue;
                if (!haveHit || pressed) { hitU = u; hitV = v; down = pressed; activeHand = h; haveHit = true; hitModel = hm; }
            }
            if (haveHit) { int a = down ? (prevDown[activeHand] ? 1 : 0) : (prevDown[activeHand] ? 2 : -1); if (a >= 0) callPointer(hitU, 1.0f - hitV, a); prevDown[activeHand] = down; }
            else if (prevDown[activeHand]) { callPointer(0, 0, 3); prevDown[activeHand] = false; }
            // panel texture
            env->CallStaticVoidMethod(g_host, m_panelUpdate, tmArr); float tm4[16]; env->GetFloatArrayRegion(tmArr, 0, 16, tm4);
            // views
            std::vector<XrView> views(nv, {XR_TYPE_VIEW}); XrViewState vs{XR_TYPE_VIEW_STATE}; XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
            li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO; li.displayTime = fs.predictedDisplayTime; li.space = space; uint32_t cnt = 0;
            if (XR_SUCCEEDED(xrLocateViews(session, &li, &vs, nv, &cnt, views.data())) && cnt == nv) {
                for (uint32_t e = 0; e < nv; e++) {
                    uint32_t idx; XrSwapchainImageAcquireInfo ai2{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO}; xrAcquireSwapchainImage(eyes[e].sc, &ai2, &idx);
                    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO}; wi.timeout = XR_INFINITE_DURATION; xrWaitSwapchainImage(eyes[e].sc, &wi);
                    glBindFramebuffer(GL_FRAMEBUFFER, fbo); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, eyes[e].imgs[idx].image, 0);
                    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, eyes[e].depth);
                    glViewport(0, 0, eyes[e].w, eyes[e].h);
                    XrPosef vp = views[e].pose; vp.position.y += floorOffset;
                    M4 vpm = mul(projection(views[e].fov, 0.05f, 50.f), rigidInverse(fromPose(vp)));
                    core.drawScene(vpm, tm4, haveHit, hitModel);
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    XrSwapchainImageReleaseInfo rl{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO}; xrReleaseSwapchainImage(eyes[e].sc, &rl);
                    pv[e].pose = views[e].pose; pv[e].fov = views[e].fov; pv[e].subImage.swapchain = eyes[e].sc; pv[e].subImage.imageRect = {{0, 0}, {eyes[e].w, eyes[e].h}};
                }
                layer.space = space; layer.viewCount = nv; layer.views = pv.data(); layers[0] = (XrCompositionLayerBaseHeader *)&layer; layerCount = 1;
            }
        }
        XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO}; ei.displayTime = fs.predictedDisplayTime; ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE; ei.layerCount = layerCount; ei.layers = layerCount ? layers : nullptr;
        xrEndFrame(session, &ei);
    }
    LOGI("scene loop ended");
    for (auto &e : eyes) xrDestroySwapchain(e.sc);
    for (int h = 0; h < 2; h++) xrDestroySpace(aimSpace[h]);
    xrDestroyActionSet(set); xrDestroySpace(space); xrDestroySession(session); xrDestroyInstance(inst);
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); eglDestroySurface(dpy, pb); eglDestroyContext(dpy, ctx);
}

extern "C" JNIEXPORT void JNICALL Java_org_olfx_launcher_SceneHost_nativeRun(JNIEnv *env, jclass, jobject activity, jobject assets, jstring loader) {
    bindHost(env);
    const char *lp = env->GetStringUTFChars(loader, nullptr);
    run(env, activity, assets, lp);
    env->ReleaseStringUTFChars(loader, lp);
}
extern "C" JNIEXPORT void JNICALL Java_org_olfx_launcher_SceneHost_nativeStop(JNIEnv *, jclass) { g_stop = true; }
