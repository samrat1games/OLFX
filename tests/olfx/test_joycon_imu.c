// Host regression: cc -std=c11 -I<shim/src> test_joycon_imu.c <shim/src>/joycon_imu.c <shim/src>/olfx_device_profile.c -lm
#include <math.h>
#include <stdio.h>
#include "joycon_imu.h"
#include "olfx_device_profile.h"
static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)
int main(void) {
    olfx_imu s; olfx_imu_init(&s);
    // 1 s of 90 deg/s about device Y (up) while lying flat (accel = +Y): expect ~90 deg yaw.
    int64_t t = 1000000000; float g = 9.81f;
    for (int i = 0; i <= 200; i++, t += 5000000) olfx_imu_update(&s, 0, 1.5707963f, 0, 0, g, 0, t);
    float yaw = 2.0f * atan2f(s.q.y, s.q.w) * 57.29578f;
    CHECK(fabsf(yaw - 90.0f) < 2.0f);
    // Tilted start: gravity along device +X => after init, device X maps to world up.
    olfx_imu_init(&s); t = 1;
    for (int i = 0; i < 400; i++, t += 5000000) olfx_imu_update(&s, 0, 0, 0, g, 0, 0, t);
    float rx = 1 - 2*(s.q.y*s.q.y + s.q.z*s.q.z), ry = 2*(s.q.x*s.q.y + s.q.w*s.q.z);
    CHECK(fabsf(rx) < 0.02f && fabsf(ry - 1.0f) < 0.02f);
    // Gyro bias drift is corrected back toward gravity (tilt error decays).
    olfx_imu_init(&s); t = 1;
    for (int i = 0; i < 2000; i++, t += 5000000) olfx_imu_update(&s, 0.05f, 0, 0, 0, g, 0, t);
    float tilt = 2.0f * fabsf(s.q.x) * 57.29578f;
    printf("tilt=%.2f\n", tilt); CHECK(tilt < 12.0f);
    // bad sample (huge dt) is ignored, quaternion stays unit
    olfx_imu_update(&s, 9, 9, 9, 0, g, 0, t + 5000000000LL);
    CHECK(fabsf(s.q.w*s.q.w + s.q.x*s.q.x + s.q.y*s.q.y + s.q.z*s.q.z - 1) < 1e-4f);
    // device profiles
    CHECK(olfx_device_identify("Oculus", "Pacific", "pacific")->id == OLFX_DEV_OCULUS_GO);
    CHECK(olfx_device_identify("Pico Interactive", "Pico G2 4K", "g24k")->id == OLFX_DEV_PICO_G2);
    CHECK(olfx_device_identify("Pico", "Pico Neo 3", "phoenix")->id == OLFX_DEV_PICO_NEO3);
    CHECK(olfx_device_identify("PICO", "A8110", "sparrow")->id == OLFX_DEV_PICO_4);
    CHECK(olfx_device_identify("Oculus", "Quest 2", "hollywood")->id == OLFX_DEV_GENERIC);
    CHECK(olfx_device_identify(NULL, NULL, NULL)->id == OLFX_DEV_GENERIC);
    CHECK(olfx_device_identify("Oculus", "Pacific", NULL)->has_vrapi == 1);
    {   /* 3-DoF controller: Joy-Con flat (accel +Z), long axis (device +Y) = pointing direction */
        olfx_imu j; olfx_imu_init(&j); int64_t tt = 1;
        for (int i = 0; i < 400; i++, tt += 5000000) olfx_imu_update(&j, 0, 0, 0, 0, 0, g, tt);
        CHECK(olfx_imu_recenter(&j) == 1);
        olfx_quat c = olfx_imu_controller_quat(&j);
        float fx = -2 * (c.x * c.z + c.y * c.w), fy = -2 * (c.y * c.z - c.x * c.w), fz = -(1 - 2 * (c.x * c.x + c.y * c.y));
        CHECK(fabsf(fx) < 0.02f && fabsf(fy) < 0.02f && fabsf(fz - (-1.0f)) < 0.02f);   /* forward = -Z, level */
        /* yaw left 90 deg (rotate about up = device Z) -> forward becomes -X */
        for (int i = 0; i < 200; i++, tt += 5000000) olfx_imu_update(&j, 0, 0, 1.5707963f, 0, 0, g, tt);
        c = olfx_imu_controller_quat(&j);
        fx = -2 * (c.x * c.z + c.y * c.w); fz = -(1 - 2 * (c.x * c.x + c.y * c.y));
        printf("yaw90: f=(%.2f,%.2f)\n", fx, fz);
        CHECK(fabsf(fx - (-1.0f)) < 0.05f && fabsf(fz) < 0.05f);
        /* recenter again, then pitch up 45 deg (rotate about device X) -> forward.y = sin 45 */
        olfx_imu_recenter(&j);
        for (int i = 0; i < 100; i++, tt += 5000000) { float th = 1.5707963f * 0.005f * (float)(i + 1); olfx_imu_update(&j, 1.5707963f, 0, 0, 0, g * sinf(th), g * cosf(th), tt); }
        c = olfx_imu_controller_quat(&j);
        fy = -2 * (c.y * c.z - c.x * c.w);
        printf("pitch45: fy=%.2f\n", fy);
        CHECK(fabsf(fy - 0.7071f) < 0.06f);
        float p[3]; olfx_imu_arm_position(c, 1, p);
        CHECK(p[0] > 0.1f && p[0] < 0.3f && p[1] > -0.5f && p[1] < 0.0f && p[2] > -0.5f && p[2] < 0.2f);
    }
    printf(fails ? "FAILED\n" : "OK\n");
    return fails;
}
