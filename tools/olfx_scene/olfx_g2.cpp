// OLFX: Pico G2 (Pico Native SDK) backend. The SDK's VRActivity owns the render loop and calls onFrameBegin/onDrawEye on the
// render thread with the eye framebuffer bound; this library draws the shared living-room scene (scene_core.h) from there.
// Input: gaze pointer (centre of the view) + any confirm key. The G2 controller API is not used (unverified).
#include "scene_core.h"

static SceneCore g_core; static bool g_ready = false;
static float g_quat[4] = {0, 0, 0, 1}, g_pos[3] = {0, 0, 0}, g_ipd = 0.064f, g_fov = 100.f; static int g_ew = 1024, g_eh = 1024;
static std::atomic<bool> g_pressed{false};
static M4 g_head; static float g_tm[16]; static bool g_hit = false; static M4 g_hitModel; static bool g_prevDown = false;
static jfloatArray g_tmArr;

static M4 fromQuat(float x, float y, float z, float w) {
    M4 r = ident();
    r.m[0] = 1 - 2 * (y * y + z * z); r.m[1] = 2 * (x * y + z * w); r.m[2] = 2 * (x * z - y * w);
    r.m[4] = 2 * (x * y - z * w); r.m[5] = 1 - 2 * (x * x + z * z); r.m[6] = 2 * (y * z + x * w);
    r.m[8] = 2 * (x * z + y * w); r.m[9] = 2 * (y * z - x * w); r.m[10] = 1 - 2 * (x * x + y * y);
    return r;
}
static M4 perspective(float fovDeg, float n, float f) {
    float t = tanf(fovDeg * 0.5f * 3.14159265f / 180.f); M4 p{};
    p.m[0] = 1 / t; p.m[5] = 1 / t; p.m[10] = -(f + n) / (f - n); p.m[11] = -1; p.m[14] = -2 * f * n / (f - n); return p;
}

extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeInit(JNIEnv *env, jclass, jobject assets) {
    bindHost(env);
    g_core.init(env, AAssetManager_fromJava(env, assets));
    g_tmArr = (jfloatArray)env->NewGlobalRef(env->NewFloatArray(16));
    g_ready = true;
}
extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeFrameBegin(JNIEnv *env, jclass, jfloatArray quat, jfloatArray pos, jfloat ipd, jfloat fov, jint ew, jint eh) {
    if (!g_ready) return;
    env->GetFloatArrayRegion(quat, 0, 4, g_quat); if (pos) env->GetFloatArrayRegion(pos, 0, 3, g_pos);
    g_ipd = ipd > 0.02f ? ipd : 0.064f; g_fov = fov > 3.f ? fov : fov * 57.29578f; if (g_fov < 40.f || g_fov > 140.f) g_fov = 100.f; g_ew = ew; g_eh = eh;
    g_head = fromQuat(g_quat[0], g_quat[1], g_quat[2], g_quat[3]);
    // gaze ray: from the head (1.6 m above the floor) along its forward (-z)
    float ox = g_pos[0], oy = 1.6f + g_pos[1], oz = g_pos[2], u, v; M4 hm;
    g_hit = panelHit(ox, oy, oz, -g_head.m[8], -g_head.m[9], -g_head.m[10], &u, &v, &hm);
    bool down = g_pressed.load();
    if (g_hit) { g_hitModel = hm; int a = down ? (g_prevDown ? 1 : 0) : (g_prevDown ? 2 : -1); if (a >= 0) callPointer(u, 1.0f - v, a); g_prevDown = down; }
    else if (g_prevDown) { callPointer(0, 0, 3); g_prevDown = false; }
    env->CallStaticVoidMethod(g_host, m_panelUpdate, g_tmArr); env->GetFloatArrayRegion(g_tmArr, 0, 16, g_tm);
}
extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeDrawEye(JNIEnv *, jclass, jint eye) {
    if (!g_ready) return;
    glViewport(0, 0, g_ew, g_eh);
    M4 headWorld = mul(translate(g_pos[0], 1.6f + g_pos[1], g_pos[2]), g_head);
    M4 eyeWorld = mul(headWorld, translate((eye == 0 ? -0.5f : 0.5f) * g_ipd, 0, 0));
    M4 vpm = mul(perspective(g_fov, 0.05f, 50.f), rigidInverse(eyeWorld));
    g_core.drawScene(vpm, g_tm, g_hit, g_hitModel);
}
extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeSetPressed(JNIEnv *, jclass, jboolean down) { g_pressed = down; }
extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeShutdown(JNIEnv *, jclass) { g_ready = false; }
