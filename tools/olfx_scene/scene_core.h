// OLFX: shared GLES scene core (living room, CC0 props, floating launcher panel) used by the OpenXR (Pico) and VrApi (Go) backends.
#pragma once
#include <unistd.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_JPEG
#include "stb_image.h"

#define TAG "OlfxScene"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static std::atomic<bool> g_stop{false};
static JavaVM *g_vm;
static jclass g_host;
static jmethodID m_panelCreate, m_panelUpdate, m_pointer;

static void bindHost(JNIEnv *env) {
    env->GetJavaVM(&g_vm);
    jclass c = env->FindClass("org/olfx/launcher/SceneHost"); g_host = (jclass)env->NewGlobalRef(c);
    m_panelCreate = env->GetStaticMethodID(g_host, "panelCreate", "(I)V");
    m_panelUpdate = env->GetStaticMethodID(g_host, "panelUpdate", "([F)V");
    m_pointer = env->GetStaticMethodID(g_host, "onPointer", "(FFI)V");
    g_stop = false;
}
static void callPointer(float u, float v, int action) {
    JNIEnv *env = nullptr; if (g_vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) return;
    env->CallStaticVoidMethod(g_host, m_pointer, u, v, action);
}

// ---------------- math (column-major 4x4) ----------------
struct M4 { float m[16]; };
static M4 ident() { M4 r{}; r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1; return r; }
static M4 mul(const M4 &a, const M4 &b) {
    M4 r{};
    for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) {
        float s = 0; for (int k = 0; k < 4; k++) s += a.m[k * 4 + rr] * b.m[c * 4 + k]; r.m[c * 4 + rr] = s; }
    return r;
}
static M4 translate(float x, float y, float z) { M4 r = ident(); r.m[12] = x; r.m[13] = y; r.m[14] = z; return r; }
static M4 scale(float x, float y, float z) { M4 r = ident(); r.m[0] = x; r.m[5] = y; r.m[10] = z; return r; }
static M4 rotY(float a) { M4 r = ident(); r.m[0] = cosf(a); r.m[2] = -sinf(a); r.m[8] = sinf(a); r.m[10] = cosf(a); return r; }
static M4 fromPose(const XrPosef &p) {
    float x = p.orientation.x, y = p.orientation.y, z = p.orientation.z, w = p.orientation.w;
    M4 r = ident();
    r.m[0] = 1 - 2 * (y * y + z * z); r.m[1] = 2 * (x * y + z * w); r.m[2] = 2 * (x * z - y * w);
    r.m[4] = 2 * (x * y - z * w); r.m[5] = 1 - 2 * (x * x + z * z); r.m[6] = 2 * (y * z + x * w);
    r.m[8] = 2 * (x * z + y * w); r.m[9] = 2 * (y * z - x * w); r.m[10] = 1 - 2 * (x * x + y * y);
    r.m[12] = p.position.x; r.m[13] = p.position.y; r.m[14] = p.position.z;
    return r;
}
static M4 rigidInverse(const M4 &a) {
    M4 r = ident();
    for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r.m[j * 4 + i] = a.m[i * 4 + j];
    for (int i = 0; i < 3; i++) r.m[12 + i] = -(r.m[i] * a.m[12] + r.m[4 + i] * a.m[13] + r.m[8 + i] * a.m[14]);
    return r;
}
static M4 projection(const XrFovf &f, float n, float fa) {
    float l = tanf(f.angleLeft), r = tanf(f.angleRight), u = tanf(f.angleUp), d = tanf(f.angleDown);
    M4 p{};
    p.m[0] = 2 / (r - l); p.m[5] = 2 / (u - d); p.m[8] = (r + l) / (r - l); p.m[9] = (u + d) / (u - d);
    p.m[10] = -(fa + n) / (fa - n); p.m[11] = -1; p.m[14] = -2 * fa * n / (fa - n);
    return p;
}

// ---------------- GL ----------------
static GLuint compile(GLenum t, const char *src) {
    GLuint s = glCreateShader(t); glShaderSource(s, 1, &src, nullptr); glCompileShader(s);
    GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[512]; glGetShaderInfoLog(s, 512, nullptr, log); LOGW("shader: %s", log); }
    return s;
}
static GLuint program(const char *vs, const char *fs) {
    GLuint p = glCreateProgram(); glAttachShader(p, compile(GL_VERTEX_SHADER, vs)); glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(p); return p;
}
static const char *VS = "#version 300 es\nlayout(location=0) in vec3 p; layout(location=1) in vec3 n; layout(location=2) in vec2 t;\n"
    "uniform mat4 mvp; uniform mat4 model; out vec3 vn; out vec2 vt;\n"
    "void main(){ gl_Position = mvp*vec4(p,1.0); vn = mat3(model)*n; vt = t; }\n";
static const char *FS_TEX = "#version 300 es\nprecision mediump float; in vec3 vn; in vec2 vt; uniform sampler2D tex; out vec4 o;\n"
    "void main(){ float l = 0.45 + 0.55*max(dot(normalize(vn), normalize(vec3(0.3,1.0,0.4))),0.0); o = vec4(texture(tex,vt).rgb*l,1.0); }\n";
static const char *FS_SOLID = "#version 300 es\nprecision mediump float; in vec3 vn; in vec2 vt; uniform vec4 color; out vec4 o;\n"
    "void main(){ float l = 0.55 + 0.45*max(dot(normalize(vn), normalize(vec3(0.3,1.0,0.4))),0.0); o = vec4(color.rgb*l,color.a); }\n";
static const char *FS_OES = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float; in vec3 vn; in vec2 vt;\n"
    "uniform samplerExternalOES tex; uniform mat4 tm; out vec4 o;\n"
    "void main(){ vec2 uv = (tm*vec4(vt,0.0,1.0)).xy; o = vec4(texture(tex,uv).rgb,1.0); }\n";

struct Mesh { GLuint vao = 0, vbo = 0, ibo = 0; GLsizei count = 0; GLuint tex = 0; bool ok = false; };

static std::vector<unsigned char> readAsset(AAssetManager *am, const std::string &name) {
    std::vector<unsigned char> d;
    AAsset *a = AAssetManager_open(am, name.c_str(), AASSET_MODE_BUFFER);
    if (!a) return d;
    d.resize(AAsset_getLength(a)); AAsset_read(a, d.data(), d.size()); AAsset_close(a);
    return d;
}
static void upload(Mesh &m, const float *v, size_t nv, const uint32_t *idx, size_t ni) {
    glGenVertexArrays(1, &m.vao); glBindVertexArray(m.vao);
    glGenBuffers(1, &m.vbo); glBindBuffer(GL_ARRAY_BUFFER, m.vbo); glBufferData(GL_ARRAY_BUFFER, nv * 32, v, GL_STATIC_DRAW);
    glGenBuffers(1, &m.ibo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, m.ibo); glBufferData(GL_ELEMENT_ARRAY_BUFFER, ni * 4, idx, GL_STATIC_DRAW);
    for (int i = 0; i < 3; i++) { glEnableVertexAttribArray(i); }
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 32, (void *)0);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 32, (void *)12);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 32, (void *)24);
    m.count = (GLsizei)ni; m.ok = true;
}
static Mesh loadProp(AAssetManager *am, const std::string &name) {
    Mesh m; auto d = readAsset(am, "scene/" + name + ".mesh");
    if (d.size() < 12 || memcmp(d.data(), "OLM1", 4) != 0) { LOGW("missing mesh %s", name.c_str()); return m; }
    uint32_t nv, ni; memcpy(&nv, d.data() + 4, 4); memcpy(&ni, d.data() + 8, 4);
    if (d.size() != 12 + (size_t)nv * 32 + (size_t)ni * 4) { LOGW("bad mesh %s", name.c_str()); return m; }
    const float *v = (const float *)(d.data() + 12); const uint32_t *idx = (const uint32_t *)(d.data() + 12 + (size_t)nv * 32);
    upload(m, v, nv, idx, ni);
    auto j = readAsset(am, "scene/" + name + ".jpg"); int w, h, c;
    unsigned char *px = j.empty() ? nullptr : stbi_load_from_memory(j.data(), (int)j.size(), &w, &h, &c, 3);
    glGenTextures(1, &m.tex); glBindTexture(GL_TEXTURE_2D, m.tex);
    if (px) { glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE, px); glGenerateMipmap(GL_TEXTURE_2D); stbi_image_free(px); }
    else { unsigned char g[3] = {150, 140, 130}; glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, 1, 1, 0, GL_RGB, GL_UNSIGNED_BYTE, g); }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    return m;
}
// unit cube / quad helpers (pos, normal, uv)
static Mesh makeBox() {
    static const float n[6][3] = {{0,0,1},{0,0,-1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}};
    std::vector<float> v; std::vector<uint32_t> idx;
    for (int f = 0; f < 6; f++) {
        float ax[3] = {n[f][0], n[f][1], n[f][2]}; float u[3], w[3];
        if (fabsf(ax[1]) > 0.5f) { u[0] = 1; u[1] = 0; u[2] = 0; } else { u[0] = 0; u[1] = 1; u[2] = 0; }
        w[0] = ax[1]*u[2]-ax[2]*u[1]; w[1] = ax[2]*u[0]-ax[0]*u[2]; w[2] = ax[0]*u[1]-ax[1]*u[0];
        uint32_t b = (uint32_t)(v.size() / 8);
        for (int i = 0; i < 4; i++) {
            float su = (i == 0 || i == 3) ? -0.5f : 0.5f, sw = (i < 2) ? -0.5f : 0.5f;
            for (int k = 0; k < 3; k++) v.push_back(ax[k] * 0.5f + u[k] * su + w[k] * sw);
            v.push_back(ax[0]); v.push_back(ax[1]); v.push_back(ax[2]); v.push_back(su + 0.5f); v.push_back(sw + 0.5f);
        }
        uint32_t q[6] = {0,1,2,0,2,3}; for (uint32_t i : q) idx.push_back(b + i);
        // make both windings visible: backface culling is disabled
    }
    Mesh m; upload(m, v.data(), v.size() / 8, idx.data(), idx.size()); return m;
}
static Mesh makeQuad() { // XY plane, facing +z, uv (0,0) bottom-left
    float v[] = {-0.5f,-0.5f,0, 0,0,1, 0,0,  0.5f,-0.5f,0, 0,0,1, 1,0,  0.5f,0.5f,0, 0,0,1, 1,1,  -0.5f,0.5f,0, 0,0,1, 0,1};
    uint32_t i[] = {0,1,2,0,2,3}; Mesh m; upload(m, v, 4, i, 6); return m;
}


// ---------------- scene ----------------
static const float PANEL_W = 1.6f, PANEL_H = 0.9f, PANEL_X = 0, PANEL_Y = 1.45f, PANEL_Z = -1.6f;   // stage space (y up from floor)

struct SceneCore {
    GLuint pTex = 0, pSolid = 0, pOes = 0, panelTex = 0;
    Mesh box, quad, props[4];
    void init(JNIEnv *env, AAssetManager *am) {
        pTex = program(VS, FS_TEX); pSolid = program(VS, FS_SOLID); pOes = program(VS, FS_OES);
        box = makeBox(); quad = makeQuad();
        const char *names[4] = {"Sofa_01", "CoffeeTable_01", "ArmChair_01", "Shelf_01"};
        for (int i = 0; i < 4; i++) props[i] = loadProp(am, names[i]);
        glGenTextures(1, &panelTex); glBindTexture(GL_TEXTURE_EXTERNAL_OES, panelTex);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        env->CallStaticVoidMethod(g_host, m_panelCreate, (jint)panelTex);
    }
    void drawMesh(const M4 &vp, const M4 &model, const Mesh &m, GLuint prog, const float *color) {
        if (!m.ok) return; glUseProgram(prog);
        M4 mvp = mul(vp, model); glUniformMatrix4fv(glGetUniformLocation(prog, "mvp"), 1, GL_FALSE, mvp.m); glUniformMatrix4fv(glGetUniformLocation(prog, "model"), 1, GL_FALSE, model.m);
        if (color) glUniform4fv(glGetUniformLocation(prog, "color"), 1, color);
        glBindVertexArray(m.vao); glDrawElements(GL_TRIANGLES, m.count, GL_UNSIGNED_INT, nullptr);
    }
    /** vpm maps STAGE space (y up from the floor) to clip space. */
    void drawScene(const M4 &vpm, const float *tm4, bool haveHit, const M4 &hitModel) {
        glClearColor(0.10f, 0.09f, 0.08f, 1); glEnable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        auto draw = [&](const M4 &vp, const M4 &model, const Mesh &m, GLuint prog, const float *color) { drawMesh(vp, model, m, prog, color); };
                    // room: floor, ceiling, 4 walls (unit box scaled)
                    const float wall[4] = {0.78f, 0.72f, 0.62f, 1}, floorC[4] = {0.40f, 0.29f, 0.20f, 1}, ceilC[4] = {0.9f, 0.88f, 0.84f, 1};
                    draw(vpm, mul(translate(0, -0.05f, -0.4f), scale(5.4f, 0.1f, 5.6f)), box, pSolid, floorC);
                    draw(vpm, mul(translate(0, 2.75f, -0.4f), scale(5.4f, 0.1f, 5.6f)), box, pSolid, ceilC);
                    draw(vpm, mul(translate(0, 1.35f, -3.2f), scale(5.4f, 2.7f, 0.1f)), box, pSolid, wall);
                    draw(vpm, mul(translate(0, 1.35f, 2.4f), scale(5.4f, 2.7f, 0.1f)), box, pSolid, wall);
                    draw(vpm, mul(translate(-2.65f, 1.35f, -0.4f), scale(0.1f, 2.7f, 5.6f)), box, pSolid, wall);
                    draw(vpm, mul(translate(2.65f, 1.35f, -0.4f), scale(0.1f, 2.7f, 5.6f)), box, pSolid, wall);
                    glUseProgram(pTex); glActiveTexture(GL_TEXTURE0); glUniform1i(glGetUniformLocation(pTex, "tex"), 0);
                    struct P { int i; float x, z, ry, s; } place[4] = {{0, -2.35f, -1.5f, 1.5708f, 1.f}, {1, -1.2f, -1.5f, 1.5708f, 0.7f}, {2, 2.2f, -1.2f, -1.5708f, 1.f}, {3, 1.4f, -3.05f, 0.f, 1.f}};
                    for (auto &p : place) { glBindTexture(GL_TEXTURE_2D, props[p.i].tex); draw(vpm, mul(translate(p.x, 0, p.z), mul(rotY(p.ry), scale(p.s, p.s, p.s))), props[p.i], pTex, nullptr); }
                    // panel
                    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_EXTERNAL_OES, panelTex); glUseProgram(pOes);
                    glUniform1i(glGetUniformLocation(pOes, "tex"), 0); glUniformMatrix4fv(glGetUniformLocation(pOes, "tm"), 1, GL_FALSE, tm4);
                    draw(vpm, mul(translate(PANEL_X, PANEL_Y, PANEL_Z), scale(PANEL_W, PANEL_H, 1)), quad, pOes, nullptr);
        if (haveHit) { const float dotC[4] = {1.f, 0.35f, 0.1f, 1}; draw(vpm, hitModel, quad, pSolid, dotC); }
    }
};

/** Ray (stage space) vs the launcher panel. Returns true and u,v (v up) when it hits. */
static bool panelHit(float ox, float oy, float oz, float dx, float dy, float dz, float *u, float *v, M4 *hitModel) {
    if (dz >= -1e-4f) return false;
    float t = (PANEL_Z - oz) / dz; if (t <= 0) return false;
    float hx = ox + dx * t, hy = oy + dy * t;
    *u = (hx - (PANEL_X - PANEL_W / 2)) / PANEL_W; *v = (hy - (PANEL_Y - PANEL_H / 2)) / PANEL_H;
    if (*u < 0 || *u > 1 || *v < 0 || *v > 1) return false;
    *hitModel = mul(translate(hx, hy, PANEL_Z + 0.01f), scale(0.025f, 0.025f, 1));
    return true;
}
