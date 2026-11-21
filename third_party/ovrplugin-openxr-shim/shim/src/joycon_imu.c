// OLFX: Joy-Con IMU fusion. See joycon_imu.h.
#include "joycon_imu.h"
#include <math.h>

static olfx_quat qmul(olfx_quat a, olfx_quat b) {
    olfx_quat r = {a.w*b.w - a.x*b.x - a.y*b.y - a.z*b.z, a.w*b.x + a.x*b.w + a.y*b.z - a.z*b.y,
                   a.w*b.y - a.x*b.z + a.y*b.w + a.z*b.x, a.w*b.z + a.x*b.y - a.y*b.x + a.z*b.w};
    return r;
}
static void qnorm(olfx_quat *q) {
    float n = sqrtf(q->w*q->w + q->x*q->x + q->y*q->y + q->z*q->z);
    if (n < 1e-9f) { q->w = 1; q->x = q->y = q->z = 0; return; }
    q->w /= n; q->x /= n; q->y /= n; q->z /= n;
}
// rotate vector v by quaternion q
static void qrot(olfx_quat q, const float v[3], float o[3]) {
    olfx_quat p = {0, v[0], v[1], v[2]}, c = {q.w, -q.x, -q.y, -q.z};
    olfx_quat r = qmul(qmul(q, p), c);
    o[0] = r.x; o[1] = r.y; o[2] = r.z;
}
void olfx_imu_init(olfx_imu *s) {
    s->q.w = 1; s->q.x = s->q.y = s->q.z = 0;
    s->last_ns = 0; s->gain = 0.5f; s->initialised = 0; s->yaw_off = 0;
}
void olfx_imu_update(olfx_imu *s, float gx, float gy, float gz, float ax, float ay, float az, int64_t ts) {
    float an = sqrtf(ax*ax + ay*ay + az*az);
    int have_acc = an > 1.0f && an < 40.0f;
    if (!s->initialised && have_acc) {
        // align measured "up" (+accel) in device frame to world +Y
        float u[3] = {ax/an, ay/an, az/an}, w[3] = {0, 1, 0};
        float c[3] = {u[1]*w[2]-u[2]*w[1], u[2]*w[0]-u[0]*w[2], u[0]*w[1]-u[1]*w[0]};
        float d = u[0]*w[0] + u[1]*w[1] + u[2]*w[2];
        olfx_quat q = {1 + d, c[0], c[1], c[2]};
        if (d < -0.9999f) { q.w = 0; q.x = 1; q.y = q.z = 0; }
        qnorm(&q); s->q = q; s->initialised = 1;
    }
    if (!s->last_ns) { s->last_ns = ts; return; }
    float dt = (float)(ts - s->last_ns) * 1e-9f;
    s->last_ns = ts;
    if (dt <= 0 || dt > 0.25f) return;               // dropped / out-of-order sample
    float wn = sqrtf(gx*gx + gy*gy + gz*gz);
    if (wn > 1e-6f) {                                // q = q * dq(body-frame rate)
        float h = 0.5f * wn * dt, sn = sinf(h) / wn;
        olfx_quat dq = {cosf(h), gx*sn, gy*sn, gz*sn};
        s->q = qmul(s->q, dq);
    }
    if (have_acc) {                                  // tilt correction toward gravity
        float a[3] = {ax/an, ay/an, az/an}, up[3];
        qrot(s->q, a, up);                           // measured up in world; should be (0,1,0)
        float ex = -up[2], ez = up[0];               // axis = up x (0,1,0)
        float k = s->gain * dt * 0.5f;
        olfx_quat corr = {1, ex*k, 0, ez*k};
        s->q = qmul(corr, s->q);
    }
    qnorm(&s->q);
}

olfx_quat olfx_imu_controller_quat(const olfx_imu *s) {
    const float h = 0.70710678f;                       // +90 deg about X
    olfx_quat fix = {h, h, 0, 0};
    olfx_quat q = qmul(s->q, fix);
    float c = cosf(s->yaw_off * 0.5f), sn = sinf(s->yaw_off * 0.5f);
    olfx_quat yaw = {c, 0, sn, 0};
    q = qmul(yaw, q);
    qnorm(&q);
    return q;
}

int olfx_imu_recenter(olfx_imu *s) {
    float saved = s->yaw_off; s->yaw_off = 0;
    olfx_quat q = olfx_imu_controller_quat(s);
    const float fwd[3] = {0, 0, -1}; float f[3];
    qrot(q, fwd, f);
    if (f[0] * f[0] + f[2] * f[2] < 0.05f) { s->yaw_off = saved; return 0; }
    s->yaw_off = -atan2f(-f[0], -f[2]);
    return 1;
}

void olfx_imu_arm_position(olfx_quat q, int side, float out[3]) {
    const float fwd[3] = {0, 0, -0.30f}; float r[3];
    qrot(q, fwd, r);
    out[0] = 0.18f * (float)side + r[0];
    out[1] = -0.35f + r[1];
    out[2] = -0.05f + r[2];
}
