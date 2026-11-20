#include "olfx_device_profile.h"
#include <ctype.h>
#include <string.h>

static const olfx_device_profile k_generic = {OLFX_DEV_GENERIC, "generic", 3, 0, 0};
static const olfx_device_profile k_go   = {OLFX_DEV_OCULUS_GO, "Oculus Go", 3, 1, 0};
static const olfx_device_profile k_g2   = {OLFX_DEV_PICO_G2, "Pico G2", 3, 0, 1};
static const olfx_device_profile k_neo3 = {OLFX_DEV_PICO_NEO3, "Pico Neo 3", 6, 0, 1};
static const olfx_device_profile k_p4   = {OLFX_DEV_PICO_4, "Pico 4", 6, 0, 1};

static int has(const char *h, const char *n) {
    if (!h) return 0;
    size_t nl = strlen(n);
    for (; *h; h++) { size_t i = 0; while (i < nl && h[i] && tolower((unsigned char)h[i]) == n[i]) i++; if (i == nl) return 1; }
    return 0;
}
const olfx_device_profile *olfx_device_identify(const char *mf, const char *model, const char *dev) {
    // Oculus Go: Build.MODEL "Pacific", manufacturer Oculus
    if ((has(mf, "oculus") || has(mf, "facebook")) && (has(model, "pacific") || has(dev, "pacific"))) return &k_go;
    if (has(model, "oculus go")) return &k_go;
    if (has(mf, "pico") || has(mf, "bytedance") || has(model, "pico") || has(dev, "pico")) {
        if (has(model, "neo 3") || has(model, "neo3") || has(dev, "neo3") || has(dev, "phoenix")) return &k_neo3;
        if (has(model, "pico 4") || has(model, "pico4") || has(dev, "pico4") || has(dev, "sparrow") || has(model, "a8110") || has(model, "a8150")) return &k_p4;
        if (has(model, "g2") || has(dev, "g2") || has(model, "g24k") || has(dev, "g24k")) return &k_g2;
    }
    return &k_generic;
}
