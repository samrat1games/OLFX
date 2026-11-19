# Upstream provenance

Vendored from https://github.com/daniel-lynch/ovrplugin-openxr-shim at commit
`da7dd08640668eb5ce466b7562e2fe7c4264d68e` (MIT, Copyright (c) 2026 Daniel Lynch).
Only `shim/src`, `shim/include` and `LICENSE` are copied. No Meta binaries, Meta headers,
game code or entitlement circumvention code are included.

OLFX changes:
- `shim/src/stubs.c`: remove the three hand stubs (`ovrp_GetHandState`,
  `ovrp_GetHandState2`, `ovrp_GetHandTrackingEnabled`); implemented by `olfx_hands.c`.
- `shim/src/olfx_hands.c`: OpenXR `XR_EXT_hand_tracking` (+ optional `XR_FB_hand_tracking_aim`)
  bridge into the OVRPlugin `HandStateInternal` ABI.
- `shim/src/olfx_extra_stubs.c`: unsupported exports for the wider OVRPlugin symbol surface
  observed by OLFX, generated from `tools/ovr_shim/names.h`.
- `shim/src/xr_runtime.c` / `xr_runtime.h`: enable/create/sync/destroy the hand bridge.

This is an experimental interoperability path for legally owned VrApi/OVRPlugin titles.
It is not a universal emulator and does not bypass store entitlement checks.
