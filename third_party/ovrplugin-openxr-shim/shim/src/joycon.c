// OLFX: Joy-Con / gamepad buttons and sticks -> Oculus Touch controller state, merged with camera hand tracking.
// A tiny dex (joy_dex.h, source in tools/joybridge) is loaded in-memory into the game process; it wraps the game
// window's callback, passes every event through unchanged and reports gamepad input to the natives below.
#include <stdint.h>
#include <string.h>
#include "xr_runtime.h"
#include "joycon_imu.h"

#ifdef __ANDROID__
#include <jni.h>
#include "log.h"
#include "joy_dex.h"

extern void *xrr_android_get_vm(void);
extern void *xrr_android_activity(void);

#define OVR_A 0x1u
#define OVR_B 0x2u
#define OVR_X 0x100u
#define OVR_Y 0x200u
#define OVR_START 0x100000u
#define OVR_LTHUMB 0x400u
#define OVR_RTHUMB 0x4u

static volatile uint32_t s_keys[3];
static volatile float s_ax[3][6];
static volatile int s_seen;
static olfx_imu s_imu[3];
static volatile int s_imu_seen[3];
static volatile int s_imu_init;

static void JNICALL native_imu(JNIEnv *env, jclass c, jint role, jfloat gx, jfloat gy, jfloat gz, jfloat ax, jfloat ay,
                               jfloat az, jlong ts) {
    (void)env; (void)c;
    if (role < 0 || role > 2) return;
    if (!s_imu_init) { for (int i = 0; i < 3; i++) olfx_imu_init(&s_imu[i]); s_imu_init = 1; }
    olfx_imu_update(&s_imu[role], gx, gy, gz, ax, ay, az, ts);
    s_imu_seen[role] = 1;
}

/* Fused Joy-Con orientation (role 1 = left, 2 = right, 0 = paired pad). Returns 0 until IMU samples arrive. */
int xrr_joy_orientation(int role, float q[4]) {
    if (role < 0 || role > 2 || !s_imu_seen[role]) return 0;
    q[0] = s_imu[role].q.w; q[1] = s_imu[role].q.x; q[2] = s_imu[role].q.y; q[3] = s_imu[role].q.z;
    return 1;
}

static int keybit(int code) {
    switch (code) {
        case 96: return 0;  case 97: return 1;  case 99: return 2;  case 100: return 3;   // A B X Y
        case 102: return 4; case 103: return 5; case 104: return 6;  case 105: return 7;  // L1 R1 L2 R2
        case 106: return 8; case 107: return 9; case 108: return 10; case 109: return 11; // TL TR START SELECT
        case 19: return 12; case 20: return 13; case 21: return 14;  case 22: return 15;  // dpad U D L R
        default: return -1;
    }
}

static void JNICALL native_key(JNIEnv *env, jclass c, jint role, jint code, jboolean down) {
    (void)env; (void)c;
    int b = keybit(code);
    if (b < 0 || role < 0 || role > 2) return;
    if (down) __atomic_or_fetch(&s_keys[role], 1u << b, __ATOMIC_RELAXED);
    else __atomic_and_fetch(&s_keys[role], ~(1u << b), __ATOMIC_RELAXED);
    s_seen = 1;
}
static void JNICALL native_axes(JNIEnv *env, jclass c, jint role, jfloat lx, jfloat ly, jfloat rx, jfloat ry,
                                jfloat lt, jfloat rt) {
    (void)env; (void)c;
    if (role < 0 || role > 2) return;
    s_ax[role][0] = lx; s_ax[role][1] = ly; s_ax[role][2] = rx; s_ax[role][3] = ry; s_ax[role][4] = lt; s_ax[role][5] = rt;
    s_seen = 1;
}

static int s_installed;
void xrr_joy_poll(void) {
    static int tries, tick;
    if (s_installed || tries > 40 || (tick++ % 90) != 0) return;
    JavaVM *vm = (JavaVM *)xrr_android_get_vm();
    jobject act = (jobject)xrr_android_activity();
    if (!vm || !act) return;
    tries++;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return;
    }
#define CHK if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); XRRLOG("joycon: JNI failure at line %d", __LINE__); return; }
    jclass actCls = (*env)->GetObjectClass(env, act);
    jmethodID getCl = (*env)->GetMethodID(env, actCls, "getClassLoader", "()Ljava/lang/ClassLoader;"); CHK
    jobject parent = (*env)->CallObjectMethod(env, act, getCl); CHK
    jclass imc = (*env)->FindClass(env, "dalvik/system/InMemoryDexClassLoader"); CHK
    jmethodID ctor = (*env)->GetMethodID(env, imc, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V"); CHK
    jobject buf = (*env)->NewDirectByteBuffer(env, (void *)olfx_joy_dex, (jlong)sizeof(olfx_joy_dex)); CHK
    jobject loader = (*env)->NewObject(env, imc, ctor, buf, parent); CHK
    jclass clCls = (*env)->GetObjectClass(env, loader);
    jmethodID loadClass = (*env)->GetMethodID(env, clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;"); CHK
    jstring nm = (*env)->NewStringUTF(env, "org.olfx.JoyBridge");
    jclass bridge = (jclass)(*env)->CallObjectMethod(env, loader, loadClass, nm); CHK
    JNINativeMethod nms[2] = {
        {"nativeKey", "(IIZ)V", (void *)native_key},
        {"nativeAxes", "(IFFFFFF)V", (void *)native_axes},
    };
    if ((*env)->RegisterNatives(env, bridge, nms, 2) != 0) { (*env)->ExceptionClear(env); XRRLOG("joycon: RegisterNatives failed"); return; }
    {   // IMU entry point exists only in a dex built from the current JoyBridge.java; an older dex just lacks it.
        JNINativeMethod imu = {"nativeImu", "(IFFFFFFJ)V", (void *)native_imu};
        if ((*env)->RegisterNatives(env, bridge, &imu, 1) != 0) { (*env)->ExceptionClear(env); XRRLOG("joycon: no IMU entry point (old dex)"); }
    }
    jmethodID inst = (*env)->GetStaticMethodID(env, bridge, "install", "(Landroid/app/Activity;)V"); CHK
    (*env)->CallStaticVoidMethod(env, bridge, inst, act); CHK
    s_installed = 1;
    XRRLOG("joycon: gamepad bridge installed");
}

static inline int K(int r, int bit) { return (s_keys[r] >> bit) & 1; }
static float dz(float v) { return (v > -0.12f && v < 0.12f) ? 0.0f : v; }

void xrr_joy_merge(uint32_t *buttons, float *lt, float *rt, float *lg, float *rg, float *lsx, float *lsy, float *rsx, float *rsy) {
    if (!s_seen) return;
    uint32_t b = 0;
    float ltr = 0, rtr = 0, lgr = 0, rgr = 0, lx = 0, ly = 0, rx = 0, ry = 0;
    // paired / Pro-style gamepad
    if (K(0, 0)) b |= OVR_A; if (K(0, 1)) b |= OVR_B; if (K(0, 2)) b |= OVR_X; if (K(0, 3)) b |= OVR_Y;
    if (K(0, 8)) b |= OVR_LTHUMB; if (K(0, 9)) b |= OVR_RTHUMB; if (K(0, 10) || K(0, 11)) b |= OVR_START;
    lgr = K(0, 4); rgr = K(0, 5); ltr = K(0, 6) ? 1.f : s_ax[0][4]; rtr = K(0, 7) ? 1.f : s_ax[0][5];
    lx = dz(s_ax[0][0]); ly = dz(s_ax[0][1]); rx = dz(s_ax[0][2]); ry = dz(s_ax[0][3]);
    // single left Joy-Con (sideways)
    if (K(1, 0) || K(1, 1) || K(1, 13) || K(1, 14)) b |= OVR_X;
    if (K(1, 2) || K(1, 3) || K(1, 12) || K(1, 15)) b |= OVR_Y;
    if (K(1, 8)) b |= OVR_LTHUMB; if (K(1, 10) || K(1, 11)) b |= OVR_START;
    if (K(1, 4)) lgr = 1; if (K(1, 6) || K(1, 7) || K(1, 5)) ltr = 1;
    if (dz(s_ax[1][0]) != 0 || dz(s_ax[1][1]) != 0) { lx = dz(s_ax[1][0]); ly = dz(s_ax[1][1]); }
    // single right Joy-Con (sideways)
    if (K(2, 0) || K(2, 1)) b |= OVR_A;
    if (K(2, 2) || K(2, 3)) b |= OVR_B;
    if (K(2, 9)) b |= OVR_RTHUMB; if (K(2, 10)) b |= OVR_START;
    if (K(2, 5)) rgr = 1; if (K(2, 7) || K(2, 6) || K(2, 4)) rtr = 1;
    if (dz(s_ax[2][0]) != 0 || dz(s_ax[2][1]) != 0) { rx = dz(s_ax[2][0]); ry = dz(s_ax[2][1]); }
    *buttons |= b;
    if (ltr > *lt) *lt = ltr; if (rtr > *rt) *rt = rtr; if (lgr > *lg) *lg = lgr; if (rgr > *rg) *rg = rgr;
    if (lx != 0 || ly != 0) { *lsx = lx; *lsy = ly; }
    if (rx != 0 || ry != 0) { *rsx = rx; *rsy = ry; }
}
#else
void xrr_joy_poll(void) {}
int xrr_joy_orientation(int role, float q[4]) { (void)role; (void)q; return 0; }
void xrr_joy_merge(uint32_t *buttons, float *lt, float *rt, float *lg, float *rg, float *lsx, float *lsy, float *rsx, float *rsy) {
    (void)buttons; (void)lt; (void)rt; (void)lg; (void)rg; (void)lsx; (void)lsy; (void)rsx; (void)rsy;
}
#endif
