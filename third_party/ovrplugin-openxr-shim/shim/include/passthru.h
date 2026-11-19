#ifndef PASSTHRU_H
#define PASSTHRU_H
/* P4 passthru — run the REAL (SONAME-patched) libOVRPlugin inside our app and forward
 * every game-called export to it, so we can LOG native's per-eye poses/FOV/submit and
 * diff them against our clean-but-ghosting values. Gate: debug.re4vr.passthru=1.
 *
 * All-or-nothing: the real lib owns the WHOLE session, so every export the game calls
 * must forward (partial forwarding crashes mid-frame because state lives in the real
 * lib). Fully-prototyped exports (core.c/layers.c) forward via PT_FWD below; the handful
 * of no-arg () stubs the game also hits forward via signature-agnostic asm trampolines
 * in passthru.c. See HANDOFF-2026-06-27-native-parity.md. */

int   pt_active(void);              /* lazily inits on first call; 1 if real lib owns the session */
void *pt_real(const char *name);   /* dlsym from the real lib (NULL if !passthru or not found) */
void  pt_log_call(const char *name, long ret);  /* rate-limited native call census (PTC log) */

/* Forward a fully-prototyped export to the real lib and return its result, caching the
 * resolved pointer per call site. Drop as the FIRST statement of each game-called export
 * in core.c / layers.c. Compiles to nothing reachable when passthru is off. Logs the
 * native return value (rate-limited) so we can diff the full call surface vs our shim. */
/* The per-site cache is written by whichever thread gets here first while another may
 * be mid-call on the same export: publish the pointer BEFORE the got-flag (release) and
 * read the flag with acquire, or a second thread could see got=1 with a still-NULL
 * pointer and fall through to the shim path for one call — the partial-forwarding
 * mixed state that crashes mid-frame. Both threads resolving is fine (same value). */
#define PT_FWD(fn, ...) do {                                                  \
    if (pt_active()) {                                                        \
        static __typeof__(&fn) _pt_p; static int _pt_got;                     \
        if (!__atomic_load_n(&_pt_got, __ATOMIC_ACQUIRE)) {                   \
            __atomic_store_n(&_pt_p, (__typeof__(&fn))pt_real(#fn),           \
                             __ATOMIC_RELAXED);                               \
            __atomic_store_n(&_pt_got, 1, __ATOMIC_RELEASE);                  \
        }                                                                     \
        __typeof__(&fn) _pt_f = _pt_p;                                        \
        if (_pt_f) {                                                          \
            __typeof__(_pt_f(__VA_ARGS__)) _pt_r = _pt_f(__VA_ARGS__);        \
            pt_log_call(#fn, (long)_pt_r);                                    \
            return _pt_r;                                                     \
        }                                                                    \
    }                                                                        \
} while (0)

#endif /* PASSTHRU_H */
