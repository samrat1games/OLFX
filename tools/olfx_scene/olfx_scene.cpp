// OLFX: CC0 living-room OpenXR scene for Pico headsets. The launcher UI is drawn by Java into a SurfaceTexture and shown
// as a floating panel; the controller ray is turned into touch events. Exits when a game is launched (stop flag).
// OpenXR is loaded with dlopen() from the bundled Khronos loader so nothing links against it.
#include <unistd.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_JPEG
#include "stb_image.h"

#define TAG "OlfxScene"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static std::atomic<bool> g_stop{false};
static JavaVM *g_vm;
static jclass g_host;
static jmethodID m_panelCreate, m_panelUpdate, m_pointer;

// ---------------- math (column-major 4x4) ----------------
struct M4 { float m[16]; };
static M4 ident() { M4 r{}; r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1; return r; }
static M4 mul(const M4 &a, const M4 &b) {
    M4 r{};
    for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) {
        float s = 0; for (int k = 0; k < 4; k++) s += a.m[k * 4 + rr] * b.m[c * 4 + k]; r.m[c * 4 + rr] = s; }
    return r;
}
static M4 translate(float x, float y, float z) { M4 r = ident(); r.m[12] = x; r.m[13] = y; r.m[14] = z; return r; }
static M4 scale(float x, float y, float z) { M4 r = ident(); r.m[0] = x; r.m[5] = y; r.m[10] = z; return r; }
static M4 rotY(float a) { M4 r = ident(); r.m[0] = cosf(a); r.m[2] = -sinf(a); r.m[8] = sinf(a); r.m[10] = cosf(a); return r; }
static M4 fromPose(const XrPosef &p) {
    float x = p.orientation.x, y = p.orientation.y, z = p.orientation.z, w = p.orientation.w;
    M4 r = ident();
    r.m[0] = 1 - 2 * (y * y + z * z); r.m[1] = 2 * (x * y + z * w); r.m[2] = 2 * (x * z - y * w);
    r.m[4] = 2 * (x * y - z * w); r.m[5] = 1 - 2 * (x * x + z * z); r.m[6] = 2 * (y * z + x * w);
    r.m[8] = 2 * (x * z + y * w); r.m[9] = 2 * (y * z - x * w); r.m[10] = 1 - 2 * (x * x + y * y);
    r.m[12] = p.position.x; r.m[13] = p.position.y; r.m[14] = p.position.z;
    return r;
}
static M4 rigidInverse(const M4 &a) {
    M4 r = ident();
    for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r.m[j * 4 + i] = a.m[i * 4 + j];
    for (int i = 0; i < 3; i++) r.m[12 + i] = -(r.m[i] * a.m[12] + r.m[4 + i] * a.m[13] + r.m[8 + i] * a.m[14]);
    return r;
}
static M4 projection(const XrFovf &f, float n, float fa) {
    float l = tanf(f.angleLeft), r = tanf(f.angleRight), u = tanf(f.angleUp), d = tanf(f.angleDown);
    M4 p{};
    p.m[0] = 2 / (r - l); p.m[5] = 2 / (u - d); p.m[8] = (r + l) / (r - l); p.m[9] = (u + d) / (u - d);
    p.m[10] = -(fa + n) / (fa - n); p.m[11] = -1; p.m[14] = -2 * fa * n / (fa - n);
    return p;
}

// ---------------- GL ----------------
static GLuint compile(GLenum t, const char *src) {
    GLuint s = glCreateShader(t); glShaderSource(s, 1, &src, nullptr); glCompileShader(s);
    GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[512]; glGetShaderInfoLog(s, 512, nullptr, log); LOGW("shader: %s", log); }
    return s;
}
static GLuint program(const char *vs, const char *fs) {
    GLuint p = glCreateProgram(); glAttachShader(p, compile(GL_VERTEX_SHADER, vs)); glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(p); return p;
}
static const char *VS = "#version 300 es\nlayout(location=0) in vec3 p; layout(location=1) in vec3 n; layout(location=2) in vec2 t;\n"
    "uniform mat4 mvp; uniform mat4 model; out vec3 vn; out vec2 vt;\n"
    "void main(){ gl_Position = mvp*vec4(p,1.0); vn = mat3(model)*n; vt = t; }\n";
static const char *FS_TEX = "#version 300 es\nprecision mediump float; in vec3 vn; in vec2 vt; uniform sampler2D tex; out vec4 o;\n"
    "void main(){ float l = 0.45 + 0.55*max(dot(normalize(vn), normalize(vec3(0.3,1.0,0.4))),0.0); o = vec4(texture(tex,vt).rgb*l,1.0); }\n";
static const char *FS_SOLID = "#version 300 es\nprecision mediump float; in vec3 vn; in vec2 vt; uniform vec4 color; out vec4 o;\n"
    "void main(){ float l = 0.55 + 0.45*max(dot(normalize(vn), normalize(vec3(0.3,1.0,0.4))),0.0); o = vec4(color.rgb*l,color.a); }\n";
static const char *FS_OES = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float; in vec3 vn; in vec2 vt;\n"
    "uniform samplerExternalOES tex; uniform mat4 tm; out vec4 o;\n"
    "void main(){ vec2 uv = (tm*vec4(vt,0.0,1.0)).xy; o = vec4(texture(tex,uv).rgb,1.0); }\n";

struct Mesh { GLuint vao = 0, vbo = 0, ibo = 0; GLsizei count = 0; GLuint tex = 0; bool ok = false; };

static std::vector<unsigned char> readAsset(AAssetManager *am, const std::string &name) {
    std::vector<unsigned char> d;
    AAsset *a = AAssetManager_open(am, name.c_str(), AASSET_MODE_BUFFER);
    if (!a) return d;
    d.resize(AAsset_getLength(a)); AAsset_read(a, d.data(), d.size()); AAsset_close(a);
    return d;
}
static void upload(Mesh &m, const float *v, size_t nv, const uint32_t *idx, size_t ni) {
    glGenVertexArrays(1, &m.vao); glBindVertexArray(m.vao);
    glGenBuffers(1, &m.vbo); glBindBuffer(GL_ARRAY_BUFFER, m.vbo); glBufferData(GL_ARRAY_BUFFER, nv * 32, v, GL_STATIC_DRAW);
    glGenBuffers(1, &m.ibo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, m.ibo); glBufferData(GL_ELEMENT_ARRAY_BUFFER, ni * 4, idx, GL_STATIC_DRAW);
    for (int i = 0; i < 3; i++) { glEnableVertexAttribArray(i); }
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 32, (void *)0);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 32, (void *)12);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 32, (void *)24);
    m.count = (GLsizei)ni; m.ok = true;
}
static Mesh loadProp(AAssetManager *am, const std::string &name) {
    Mesh m; auto d = readAsset(am, "scene/" + name + ".mesh");
    if (d.size() < 12 || memcmp(d.data(), "OLM1", 4) != 0) { LOGW("missing mesh %s", name.c_str()); return m; }
    uint32_t nv, ni; memcpy(&nv, d.data() + 4, 4); memcpy(&ni, d.data() + 8, 4);
    if (d.size() != 12 + (size_t)nv * 32 + (size_t)ni * 4) { LOGW("bad mesh %s", name.c_str()); return m; }
    const float *v = (const float *)(d.data() + 12); const uint32_t *idx = (const uint32_t *)(d.data() + 12 + (size_t)nv * 32);
    upload(m, v, nv, idx, ni);
    auto j = readAsset(am, "scene/" + name + ".jpg"); int w, h, c;
    unsigned char *px = j.empty() ? nullptr : stbi_load_from_memory(j.data(), (int)j.size(), &w, &h, &c, 3);
    glGenTextures(1, &m.tex); glBindTexture(GL_TEXTURE_2D, m.tex);
    if (px) { glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE, px); glGenerateMipmap(GL_TEXTURE_2D); stbi_image_free(px); }
    else { unsigned char g[3] = {150, 140, 130}; glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, 1, 1, 0, GL_RGB, GL_UNSIGNED_BYTE, g); }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    return m;
}
// unit cube / quad helpers (pos, normal, uv)
static Mesh makeBox() {
    static const float n[6][3] = {{0,0,1},{0,0,-1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
    std::vector<float> v; std::vector<uint32_t> idx;
    for (int f = 0; f < 6; f++) {
        float ax[3] = {n[f][0], n[f][1], n[f][2]}; float u[3], w[3];
        if (fabsf(ax[1]) > 0.5f) { u[0] = 1; u[1] = 0; u[2] = 0; } else { u[0] = 0; u[1] = 1; u[2] = 0; }
        w[0] = ax[1]*u[2]-ax[2]*u[1]; w[1] = ax[2]*u[0]-ax[0]*u[2]; w[2] = ax[0]*u[1]-ax[1]*u[0];
        uint32_t b = (uint32_t)(v.size() / 8);
        for (int i = 0; i < 4; i++) {
            float su = (i == 0 || i == 3) ? -0.5f : 0.5f, sw = (i < 2) ? -0.5f : 0.5f;
            for (int k = 0; k < 3; k++) v.push_back(ax[k] * 0.5f + u[k] * su + w[k] * sw);
            v.push_back(ax[0]); v.push_back(ax[1]); v.push_back(ax[2]); v.push_back(su + 0.5f); v.push_back(sw + 0.5f);
        }
        uint32_t q[6] = {0,1,2,0,2,3}; for (uint32_t i : q) idx.push_back(b + i);
        // make both windings visible: backface culling is disabled
    }
    Mesh m; upload(m, v.data(), v.size() / 8, idx.data(), idx.size()); return m;
}
static Mesh makeQuad() { // XY plane, facing +z, uv (0,0) bottom-left
    float v[] = {-0.5f,-0.5f,0, 0,0,1, 0,0,  0.5f,-0.5f,0, 0,0,1, 1,0,  0.5f,0.5f,0, 0,0,1, 1,1,  -0.5f,0.5f,0, 0,0,1, 0,1};
    uint32_t i[] = {0,1,2,0,2,3}; Mesh m; upload(m, v, 4, i, 6); return m;
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

static void callPointer(float u, float v, int action) {
    JNIEnv *env = nullptr; if (g_vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) return;
    env->CallStaticVoidMethod(g_host, m_pointer, u, v, action);
}

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

    // GL resources
    GLuint pTex = program(VS, FS_TEX), pSolid = program(VS, FS_SOLID), pOes = program(VS, FS_OES);
    Mesh box = makeBox(), quad = makeQuad();
    const char *names[4] = {"Sofa_01", "CoffeeTable_01", "ArmChair_01", "Shelf_01"}; Mesh props[4];
    for (int i = 0; i < 4; i++) props[i] = loadProp(am, names[i]);
    GLuint panelTex; glGenTextures(1, &panelTex); glBindTexture(GL_TEXTURE_EXTERNAL_OES, panelTex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    env->CallStaticVoidMethod(g_host, m_panelCreate, (jint)panelTex);
    jfloatArray tmArr = env->NewFloatArray(16);
    const float PW = 1.6f, PH = 0.9f; const float PX = 0, PY = 1.45f, PZ = -1.6f;   // panel centre in stage space

    GLuint fbo; glGenFramebuffers(1, &fbo);
    bool running = false, exiting = false; XrSessionState state = XR_SESSION_STATE_UNKNOWN; bool prevDown[2] = {false, false}; int activeHand = 1;
    auto draw = [&](const M4 &vp, const M4 &model, const Mesh &m, GLuint prog, const float *color) {
        if (!m.ok) return; glUseProgram(prog);
        M4 mvp = mul(vp, model); glUniformMatrix4fv(glGetUniformLocation(prog, "mvp"), 1, GL_FALSE, mvp.m); glUniformMatrix4fv(glGetUniformLocation(prog, "model"), 1, GL_FALSE, model.m);
        if (color) glUniform4fv(glGetUniformLocation(prog, "color"), 1, color);
        glBindVertexArray(m.vao); glDrawElements(GL_TRIANGLES, m.count, GL_UNSIGNED_INT, nullptr);
    };
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
                float dx = -pm.m[8], dy = -pm.m[9], dz = -pm.m[10];
                if (dz >= -1e-4f) continue; float t = (PZ - oz) / dz; if (t <= 0) continue;
                float hx = ox + dx * t, hy = oy + dy * t; float u = (hx - (PX - PW / 2)) / PW, v = (hy - (PY - PH / 2)) / PH;
                if (u < 0 || u > 1 || v < 0 || v > 1) continue;
                if (!haveHit || pressed) { hitU = u; hitV = v; down = pressed; activeHand = h; haveHit = true; hitModel = mul(translate(hx, hy - floorOffset, PZ + 0.01f), scale(0.025f, 0.025f, 1)); }
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
                    glViewport(0, 0, eyes[e].w, eyes[e].h); glClearColor(0.10f, 0.09f, 0.08f, 1); glEnable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
                    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                    XrPosef vp = views[e].pose; vp.position.y += floorOffset;
                    M4 vpm = mul(projection(views[e].fov, 0.05f, 50.f), rigidInverse(fromPose(vp)));
                    // room: floor, ceiling, 4 walls (unit box scaled)
                    const float wall[4] = {0.78f, 0.72f, 0.62f, 1}, floorC[4] = {0.40f, 0.29f, 0.20f, 1}, ceilC[4] = {0.9f, 0.88f, 0.84f, 1};
                    draw(vpm, mul(translate(0, -0.05f, -0.4f), scale(5.4f, 0.1f, 5.6f)), box, pSolid, floorC);
                    draw(vpm, mul(translate(0, 2.75f, -0.4f), scale(5.4f, 0.1f, 5.6f)), box, pSolid, ceilC);
                    draw(vpm, mul(translate(0, 1.35f, -3.2f), scale(5.4f, 2.7f, 0.1f)), box, pSolid, wall);
                    draw(vpm, mul(translate(0, 1.35f, 2.4f), scale(5.4f, 2.7f, 0.1f)), box, pSolid, wall);
                    draw(vpm, mul(translate(-2.65f, 1.35f, -0.4f), scale(0.1f, 2.7f, 5.6f)), box, pSolid, wall);
                    draw(vpm, mul(translate(2.65f, 1.35f, -0.4f), scale(0.1f, 2.7f, 5.6f)), box, pSolid, wall);
                    glUseProgram(pTex); glActiveTexture(GL_TEXTURE0); glUniform1i(glGetUniformLocation(pTex, "tex"), 0);
                    struct P { int i; float x, z, ry, s; } place[4] = {{0, -2.35f, -1.5f, 1.5708f, 1.f}, {1, -1.2f, -1.5f, 1.5708f, 0.7f}, {2, 2.2f, -1.2f, -1.5708f, 1.f}, {3, 1.4f, -3.05f, 0.f, 1.f}};
                    for (auto &p : place) { glBindTexture(GL_TEXTURE_2D, props[p.i].tex); draw(vpm, mul(translate(p.x, 0, p.z), mul(rotY(p.ry), scale(p.s, p.s, p.s))), props[p.i], pTex, nullptr); }
                    // panel
                    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_EXTERNAL_OES, panelTex); glUseProgram(pOes);
                    glUniform1i(glGetUniformLocation(pOes, "tex"), 0); glUniformMatrix4fv(glGetUniformLocation(pOes, "tm"), 1, GL_FALSE, tm4);
                    draw(vpm, mul(translate(PX, PY, PZ), scale(PW, PH, 1)), quad, pOes, nullptr);
                    if (haveHit) { const float dotC[4] = {1.f, 0.35f, 0.1f, 1}; M4 hm = hitModel; hm.m[13] += floorOffset - floorOffset; draw(vpm, hm, quad, pSolid, dotC); }
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
    env->GetJavaVM(&g_vm);
    jclass c = env->FindClass("org/olfx/launcher/SceneHost"); g_host = (jclass)env->NewGlobalRef(c);
    m_panelCreate = env->GetStaticMethodID(g_host, "panelCreate", "(I)V");
    m_panelUpdate = env->GetStaticMethodID(g_host, "panelUpdate", "([F)V");
    m_pointer = env->GetStaticMethodID(g_host, "onPointer", "(FFI)V");
    g_stop = false;
    const char *lp = env->GetStringUTFChars(loader, nullptr);
    run(env, activity, assets, lp);
    env->ReleaseStringUTFChars(loader, lp);
}
extern "C" JNIEXPORT void JNICALL Java_org_olfx_launcher_SceneHost_nativeStop(JNIEnv *, jclass) { g_stop = true; }
