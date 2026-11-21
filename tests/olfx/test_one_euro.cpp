// g++ -std=c++17 -I tools/olfx_scene tests/olfx/test_one_euro.cpp -o /tmp/t && /tmp/t
#include "one_euro.h"
#include <cstdio>
#include <cstdlib>
static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)
static float noise(float s) { return ((rand() / (float)RAND_MAX) - 0.5f) * 2.f * s; }
int main() {
    srand(7);
    // 1) hand held still with 0.004 jitter (~0.23 deg): output jitter must drop clearly
    OneEuro f; float inSq = 0, outSq = 0; int n = 0;
    for (int i = 0; i < 600; i++) { float o = f.filter(0.5f + noise(0.004f), 1.f / 60.f); if (i > 60) { inSq += (0.5f + 0 - 0.5f) * 0; float e = o - 0.5f; outSq += e * e; n++; } }
    float outRms = sqrtf(outSq / n); printf("still jitter rms out=%.5f (in ~0.0023)\n", outRms);
    CHECK(outRms < 0.0012f);
    // 2) fast sweep: 2 units/s ramp must be followed with little lag (< 40 ms equivalent)
    OneEuro g; float lag = 0; int m = 0;
    for (int i = 0; i < 180; i++) { float t = i / 60.f, truth = 2.f * t; float o = g.filter(truth + noise(0.004f), 1.f / 60.f); if (i > 90) { lag += (truth - o) / 2.f; m++; } }
    float lagS = lag / m; printf("fast-motion lag = %.1f ms\n", lagS * 1000.f);
    CHECK(lagS < 0.040f);
    // 3) step response: after a jump the filter converges within 0.5 s
    OneEuro h; for (int i = 0; i < 60; i++) h.filter(0.f, 1.f / 60.f);
    float v = 0; for (int i = 0; i < 30; i++) v = h.filter(1.f, 1.f / 60.f);
    printf("step after 0.5 s: %.3f\n", v); CHECK(v > 0.95f);
    printf(fails ? "FAILED\n" : "OK\n"); return fails;
}
