// OLFX: One Euro filter (Casiez et al.) - adaptive low-pass: smooth when still, near-zero lag when moving fast.
#pragma once
#include <cmath>

struct OneEuro {
    float minCutoff = 1.2f, beta = 0.7f, dCutoff = 1.0f;
    bool init = false; float x = 0, dx = 0;
    static float alpha(float cutoff, float dt) { float tau = 1.f / (6.2831853f * cutoff); return 1.f / (1.f + tau / dt); }
    float filter(float v, float dt) {
        if (dt <= 1e-4f) dt = 1e-4f;
        if (!init) { init = true; x = v; dx = 0; return v; }
        float d = (v - x) / dt;
        dx += alpha(dCutoff, dt) * (d - dx);
        float c = minCutoff + beta * std::fabs(dx);
        x += alpha(c, dt) * (v - x);
        return x;
    }
    void reset() { init = false; }
};

/** Filters a 3-vector (pointing direction or position) component-wise. */
struct OneEuro3 {
    OneEuro f[3];
    void set(float minCutoff, float beta) { for (auto &e : f) { e.minCutoff = minCutoff; e.beta = beta; } }
    void filter(const float in[3], float dt, float out[3]) { for (int i = 0; i < 3; i++) out[i] = f[i].filter(in[i], dt); }
    void reset() { for (auto &e : f) e.reset(); }
};
