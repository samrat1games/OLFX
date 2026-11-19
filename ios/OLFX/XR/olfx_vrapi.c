// Gear VR / Quest (VrApi) games. The shim libolfx_vrapi.so (a drop-in libvrapi.so with all VrApi exports, drawing through
// OpenXR) is loaded by Husk's own linker the first time a game calls any vrapi_* function. Every vrapi_* import of
// the game is bound to a stub (olfx_vrapi_stubs.S) that jumps to the shim's function, so the game's own libvrapi.so
// loader (which wants a system driver that does not exist here) is never used. The shim's xr* calls land in our OpenXR runtime.
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "olfx_vrapi.h"
#include "olfx_xr_internal.h"

typedef struct tl_lib tl_lib;
extern tl_lib *tl_ld_load(const char *name);
extern int tl_ld_init(tl_lib *lib);
extern void *tl_ld_sym(tl_lib *lib, const char *name);
extern void tl_ld_add_search_dir(const char *dir);
extern void tl_ld_interpose(const char *name, void *fn);

#define MAXN 160
void *olfx_vrapi_tab[MAXN];
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static tl_lib *g_shim;
static int g_tried;

static int fail_stub(void) { return -1; }     // ovrError: the shim is not available

void *olfx_vrapi_resolve(int idx)
{
	pthread_mutex_lock(&g_mu);
	if (!g_tried) {
		g_tried = 1;
		g_shim = tl_ld_load("libolfx_vrapi.so");
		if (g_shim) { tl_ld_init(g_shim); OLFX_LOG("VrApi shim loaded (Gear VR / Quest adapter)"); }
		else OLFX_LOG("VrApi shim failed to load; vrapi_* calls will fail");
	}
	void *fn = g_shim ? tl_ld_sym(g_shim, olfx_vrapi_names[idx]) : NULL;
	if (!fn) { OLFX_LOG("VrApi: %s not available", olfx_vrapi_names[idx]); fn = (void *)fail_stub; }
	olfx_vrapi_tab[idx] = fn;
	pthread_mutex_unlock(&g_mu);
	return fn;
}

void olfx_vrapi_install(const char *libdir)
{
	if (olfx_vrapi_count > MAXN) { OLFX_LOG("too many VrApi exports"); return; }
	if (libdir) tl_ld_add_search_dir(libdir);      // libolfx_vrapi.so and a libopenxr_loader.so for games that ship none
	for (int i = 0; i < olfx_vrapi_count; i++) tl_ld_interpose(olfx_vrapi_names[i], olfx_vrapi_stubs[i]);
	OLFX_LOG("VrApi: %d functions routed to the shim (dir %s)", olfx_vrapi_count, libdir ? libdir : "-");
}
