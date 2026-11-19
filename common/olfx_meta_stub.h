// Local Meta Platform compatibility responses. No store or account verification is performed.
#ifndef OLFX_META_STUB_H
#define OLFX_META_STUB_H
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct olfx_meta_message {
    uint64_t request;
    uint32_t type;
    struct olfx_meta_message *next;
} olfx_meta_message;
static pthread_mutex_t olfx_meta_lock = PTHREAD_MUTEX_INITIALIZER;
static olfx_meta_message *olfx_meta_head, *olfx_meta_tail;
static uint64_t olfx_meta_next = 1;
static uint64_t olfx_meta_request(uint32_t type) {
    olfx_meta_message *m = (olfx_meta_message *)calloc(1, sizeof(*m));
    if (!m) return 0;
    pthread_mutex_lock(&olfx_meta_lock);
    m->request = olfx_meta_next++;
    if (!m->request) m->request = olfx_meta_next++;
    m->type = type;
    if (olfx_meta_tail) olfx_meta_tail->next = m; else olfx_meta_head = m;
    olfx_meta_tail = m;
    uint64_t id = m->request;
    pthread_mutex_unlock(&olfx_meta_lock);
    return id;
}
static uint64_t olfx_meta_init_async(void) { return olfx_meta_request(0x1AD307B4u); }
static uint64_t olfx_meta_entitlement(void) { return olfx_meta_request(0x186B58B1u); }
static void *olfx_meta_pop(void) {
    pthread_mutex_lock(&olfx_meta_lock);
    olfx_meta_message *m = olfx_meta_head;
    if (m) { olfx_meta_head = m->next; if (!olfx_meta_head) olfx_meta_tail = NULL; }
    pthread_mutex_unlock(&olfx_meta_lock);
    return m;
}
static void olfx_meta_free(void *m) { free(m); }
static uint64_t olfx_meta_id(const void *m) { return m ? ((const olfx_meta_message *)m)->request : 0; }
static uint32_t olfx_meta_type(const void *m) { return m ? ((const olfx_meta_message *)m)->type : 0; }
static int olfx_meta_error(const void *m) { (void)m; return 0; }
static void *olfx_meta_init_result(void *m) { return m; }
static int olfx_meta_result(const void *p) { (void)p; return 0; }
#endif
