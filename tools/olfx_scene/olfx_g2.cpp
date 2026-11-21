// OLFX: Pico G2 (Pico Native SDK) backend. The SDK's VRActivity owns the render loop and calls onFrameBegin/onDrawEye on the
// render thread with the eye framebuffer bound; this library draws the shared living-room scene (scene_core.h) from there.
// Input: gaze pointer (centre of the view) + any confirm key. The G2 controller API is not used (unverified).
#include "scene_core.h"
#include "one_euro.h"
#include <time.h>

static SceneCore g_core; static bool g_ready = false;
static float g_quat[4] = {0, 0, 0, 1}, g_pos[3] = {0, 0, 0}, g_ipd = 0.064f, g_fov = 100.f; static int g_ew = 1024, g_eh = 1024;
static std::atomic<bool> g_pressed{false};
static M4 g_head; static float g_tm[16]; static bool g_hit = false; static M4 g_hitModel; static bool g_prevDown = false;
static jfloatArray g_tmArr;
// controller (Pico arm-model pointer pose, tracking space): quaternion {x,y,z,w} + position; filtered direction/origin
static bool g_ctrlValid = false; static float g_cq[4] = {0, 0, 0, 1}, g_cp[3] = {0.2f, -0.4f, -0.2f}; static bool g_ctrlPressed = false;
static OneEuro3 g_dirF, g_posF; static bool g_filtInit = false; static float g_ctrlDir[3] = {0, 0, -1}, g_ctrlPos[3] = {0.2f, 1.2f, -0.2f};
static M4 g_ctrlBasis; static bool g_drawCtrl = false; static float g_rayLen = 3.0f;

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
extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeController(JNIEnv *env, jclass, jfloatArray rot, jfloatArray pos, jboolean pressed, jboolean valid) {
    g_ctrlValid = valid; g_ctrlPressed = pressed;
    if (valid) { env->GetFloatArrayRegion(rot, 0, 4, g_cq); env->GetFloatArrayRegion(pos, 0, 3, g_cp); }
    else g_filtInit = false;
}
extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeFrameBegin(JNIEnv *env, jclass, jfloatArray quat, jfloatArray pos, jfloat ipd, jfloat fov, jint ew, jint eh) {
    if (!g_ready) return;
    env->GetFloatArrayRegion(quat, 0, 4, g_quat); if (pos) env->GetFloatArrayRegion(pos, 0, 3, g_pos);
    g_ipd = ipd > 0.02f ? ipd : 0.064f; g_fov = fov > 3.f ? fov : fov * 57.29578f; if (g_fov < 40.f || g_fov > 140.f) g_fov = 100.f; g_ew = ew; g_eh = eh;
    g_head = fromQuat(g_quat[0], g_quat[1], g_quat[2], g_quat[3]);
    float ox, oy, oz, dx, dy, dz;
    static double lastT = 0; struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); double now = ts.tv_sec + ts.tv_nsec * 1e-9;
    float dt = lastT > 0 ? (float)(now - lastT) : 1.f / 60.f; lastT = now; if (dt > 0.1f) dt = 0.1f;
    if (g_ctrlValid) {
        // controller pointer from the Pico arm model; filter direction + origin (adaptive: steady when still, no lag when fast)
        M4 cm = fromQuat(g_cq[0], g_cq[1], g_cq[2], g_cq[3]);
        float d[3] = {-cm.m[8], -cm.m[9], -cm.m[10]}, p[3] = {g_cp[0], 1.6f + g_cp[1], g_cp[2]};
        if (!g_filtInit) { g_dirF.set(1.2f, 0.7f); g_posF.set(1.5f, 0.3f); g_dirF.reset(); g_posF.reset(); g_filtInit = true; }
        g_dirF.filter(d, dt, g_ctrlDir); g_posF.filter(p, dt, g_ctrlPos);
        float n = sqrtf(g_ctrlDir[0] * g_ctrlDir[0] + g_ctrlDir[1] * g_ctrlDir[1] + g_ctrlDir[2] * g_ctrlDir[2]); if (n < 1e-4f) n = 1;
        for (int i = 0; i < 3; i++) g_ctrlDir[i] /= n;
        ox = g_ctrlPos[0]; oy = g_ctrlPos[1]; oz = g_ctrlPos[2]; dx = g_ctrlDir[0]; dy = g_ctrlDir[1]; dz = g_ctrlDir[2];
        // basis for drawing: z = -dir, x = up x z
        float zx = -dx, zy = -dy, zz = -dz, ux = 0, uy = 1, uz = 0;
        if (fabsf(zy) > 0.98f) { ux = 1; uy = 0; }
        float xx = uy * zz - uz * zy, xy = uz * zx - ux * zz, xz = ux * zy - uy * zx; float xn = sqrtf(xx * xx + xy * xy + xz * xz); xx /= xn; xy /= xn; xz /= xn;
        float yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
        g_ctrlBasis = ident(); g_ctrlBasis.m[0] = xx; g_ctrlBasis.m[1] = xy; g_ctrlBasis.m[2] = xz; g_ctrlBasis.m[4] = yx; g_ctrlBasis.m[5] = yy; g_ctrlBasis.m[6] = yz;
        g_ctrlBasis.m[8] = zx; g_ctrlBasis.m[9] = zy; g_ctrlBasis.m[10] = zz; g_ctrlBasis.m[12] = ox; g_ctrlBasis.m[13] = oy; g_ctrlBasis.m[14] = oz;
        g_drawCtrl = true;
    } else {
        // no controller: head-gaze pointer
        ox = g_pos[0]; oy = 1.6f + g_pos[1]; oz = g_pos[2]; dx = -g_head.m[8]; dy = -g_head.m[9]; dz = -g_head.m[10]; g_drawCtrl = false;
    }
    float u, v; M4 hm;
    g_hit = panelHit(ox, oy, oz, dx, dy, dz, &u, &v, &hm);
    g_rayLen = g_hit ? (PANEL_Z - oz) / (dz < -1e-4f ? dz : -1e-4f) : 3.0f;
    bool down = g_ctrlValid ? g_ctrlPressed : g_pressed.load();
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
    if (g_drawCtrl) {   // controller body + laser (same pose that drives the pointer)
        const float body[4] = {0.15f, 0.55f, 0.95f, 1}, laser[4] = {1.f, 0.35f, 0.1f, 1};
        g_core.drawMesh(vpm, mul(g_ctrlBasis, mul(translate(0, 0, -0.07f), scale(0.035f, 0.025f, 0.14f))), g_core.box, g_core.pSolid, body);
        float L = g_rayLen > 0.2f && g_rayLen < 6.f ? g_rayLen : 3.0f;
        g_core.drawMesh(vpm, mul(g_ctrlBasis, mul(translate(0, 0, -(0.14f + L * 0.5f)), scale(0.004f, 0.004f, L))), g_core.box, g_core.pSolid, laser);
    }
}
extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeSetPressed(JNIEnv *, jclass, jboolean down) { g_pressed = down; }
extern "C" JNIEXPORT void JNICALL Java_org_olfx_g2_G2Host_nativeShutdown(JNIEnv *, jclass) { g_ready = false; }
