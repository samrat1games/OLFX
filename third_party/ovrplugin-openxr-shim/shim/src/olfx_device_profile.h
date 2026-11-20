// OLFX: standalone-headset identification (Oculus Go / Pico G2, Neo 3, 4) from Android Build strings.
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
typedef enum { OLFX_DEV_GENERIC = 0, OLFX_DEV_OCULUS_GO, OLFX_DEV_PICO_G2, OLFX_DEV_PICO_NEO3, OLFX_DEV_PICO_4 } olfx_device_id;
typedef struct { olfx_device_id id; const char *name; int dof; int has_vrapi; int pico_sdk; } olfx_device_profile;
// Case-insensitive match on Build.MANUFACTURER / MODEL / DEVICE; any pointer may be NULL.
const olfx_device_profile *olfx_device_identify(const char *manufacturer, const char *model, const char *device);
#ifdef __cplusplus
}
#endif
