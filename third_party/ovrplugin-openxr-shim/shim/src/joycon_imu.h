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
} olfx_imu;
void olfx_imu_init(olfx_imu *s);
// gyro rad/s, accel m/s^2 (Android sensor convention: at rest accel reads +g "up"), timestamp ns.
void olfx_imu_update(olfx_imu *s, float gx, float gy, float gz, float ax, float ay, float az, int64_t ts_ns);
#ifdef __cplusplus
}
#endif
