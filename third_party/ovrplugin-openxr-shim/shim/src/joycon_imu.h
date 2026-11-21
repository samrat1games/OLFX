// OLFX: Joy-Con gyroscope + accelerometer fusion (complementary filter). Pure C, no Android deps.
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct { float w, x, y, z; } olfx_quat;
typedef struct {
    olfx_quat q;      // device orientation (device frame -> world, gravity = +Y)
    int64_t last_ns;  // timestamp of last sample, 0 = none yet
    float gain;       // accelerometer tilt-correction gain per second (default 0.5)
    int initialised;
    float yaw_off;    // radians about +Y, set by olfx_imu_recenter
} olfx_imu;
void olfx_imu_init(olfx_imu *s);
// gyro rad/s, accel m/s^2 (Android sensor convention: at rest accel reads +g "up"), timestamp ns.
void olfx_imu_update(olfx_imu *s, float gx, float gy, float gz, float ax, float ay, float az, int64_t ts_ns);
// Controller orientation (OpenXR-style: -Z forward, +Y up) assuming the Joy-Con is held like a pointer (long axis forward):
// device frame -> controller frame is a fixed +90 deg rotation about X, plus the recenter yaw.
olfx_quat olfx_imu_controller_quat(const olfx_imu *s);
// Make the current pointing direction "forward" (-Z). Returns 0 if the Joy-Con points (nearly) straight up/down.
int olfx_imu_recenter(olfx_imu *s);
// Simple 3-DoF arm model: hand position from the controller orientation. side = -1 left, +1 right.
void olfx_imu_arm_position(olfx_quat q, int side, float out[3]);
#ifdef __cplusplus
}
#endif
