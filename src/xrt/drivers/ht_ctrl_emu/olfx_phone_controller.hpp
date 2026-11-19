// OLFX local-network controller receiver. Inactive unless the user enables it in Settings.
#pragma once
#ifdef __ANDROID__
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <pthread.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>

struct olfx_phone_state { float x, y, z, w, trigger, grip; int64_t received_ns; };
static olfx_phone_state olfx_phones[2] = {};
static pthread_mutex_t olfx_phone_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t olfx_phone_once = PTHREAD_ONCE_INIT;
static int64_t olfx_phone_now() { timespec t{}; clock_gettime(CLOCK_MONOTONIC, &t); return (int64_t)t.tv_sec * 1000000000LL + t.tv_nsec; }
static void *olfx_phone_receiver(void *) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0); if (fd < 0) return nullptr;
    sockaddr_in addr{}; addr.sin_family = AF_INET; addr.sin_port = htons(39093); addr.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(fd, (sockaddr *)&addr, sizeof(addr)) != 0) { close(fd); return nullptr; }
    for (;;) {
        char b[256]; ssize_t n = recv(fd, b, sizeof(b)-1, 0); if (n <= 0) continue; b[n] = 0;
        FILE *f = fopen("/data/user/0/org.olfx.launcher/files/phone_controller_pin", "r");
        if (!f) continue; char configured[16] = {}; bool ok = fgets(configured, sizeof(configured), f) != nullptr; fclose(f); if (!ok) continue;
        int side; char pin[16]; olfx_phone_state s{};
        if (sscanf(b, "OLFX1 %15s %d %f %f %f %f %f %f", pin, &side, &s.x, &s.y, &s.z, &s.w, &s.trigger, &s.grip) != 8) continue;
        configured[strcspn(configured, "\r\n")] = 0;
        if (strcmp(pin, configured) || (side != 0 && side != 1)) continue;
        float q = s.x*s.x+s.y*s.y+s.z*s.z+s.w*s.w;
        if (!std::isfinite(q) || q < .5f || q > 1.5f || !std::isfinite(s.trigger) || !std::isfinite(s.grip)) continue;
        float inv = 1.0f / sqrtf(q); s.x*=inv; s.y*=inv; s.z*=inv; s.w*=inv;
        s.trigger = fmaxf(0, fminf(1, s.trigger)); s.grip = fmaxf(0, fminf(1, s.grip)); s.received_ns = olfx_phone_now();
        pthread_mutex_lock(&olfx_phone_mutex); olfx_phones[side] = s; pthread_mutex_unlock(&olfx_phone_mutex);
    }
}
static void olfx_phone_start_once() { pthread_t thread; if (pthread_create(&thread, nullptr, olfx_phone_receiver, nullptr) == 0) pthread_detach(thread); }
static bool olfx_phone_get(int side, olfx_phone_state *s) {
    pthread_once(&olfx_phone_once, olfx_phone_start_once);
    if (side < 0 || side > 1) return false;
    pthread_mutex_lock(&olfx_phone_mutex); *s = olfx_phones[side]; pthread_mutex_unlock(&olfx_phone_mutex);
    return s->received_ns && olfx_phone_now() - s->received_ns < 500000000LL;
}
#endif
