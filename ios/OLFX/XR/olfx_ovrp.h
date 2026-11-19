#ifndef OLFX_OVRP_H
#define OLFX_OVRP_H
extern const int olfx_ovrp_count;
extern const char *const olfx_ovrp_names[];
extern void *const olfx_ovrp_stubs[];
void olfx_ovrp_install(const char *libdir);
void olfx_ovrp_set_gles(int on);
#endif
