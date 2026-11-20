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
    printf(fails ? "FAILED\n" : "OK\n");
    return fails;
}
