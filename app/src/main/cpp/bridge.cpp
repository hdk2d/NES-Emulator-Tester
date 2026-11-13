// Anfitrión libretro mínimo para NesDeck.
// Carga un núcleo (.so) con dlopen y expone a Kotlin lo justo: cargar ROM,
// ejecutar un fotograma, SRAM (partidas de batería) y estados rápidos.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "NesBridge", __VA_ARGS__)

// ---- Subconjunto mínimo de libretro.h ----
struct retro_game_info { const char* path; const void* data; size_t size; const char* meta; };
struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps; double sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };
typedef void (*retro_log_printf_t)(int level, const char* fmt, ...);
struct retro_log_callback { retro_log_printf_t log; };
typedef bool (*retro_environment_t)(unsigned cmd, void* data);
typedef void (*retro_video_refresh_t)(const void* data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t* data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

// ---- Funciones del núcleo ----
static void (*retro_set_environment)(retro_environment_t);
static void (*retro_set_video_refresh)(retro_video_refresh_t);
static void (*retro_set_audio_sample)(retro_audio_sample_t);
static void (*retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void (*retro_set_input_poll)(retro_input_poll_t);
static void (*retro_set_input_state)(retro_input_state_t);
static void (*retro_init)(void);
static void (*retro_deinit)(void);
static bool (*retro_load_game)(const retro_game_info*);
static void (*retro_unload_game)(void);
static void (*retro_get_system_av_info)(retro_system_av_info*);
static void (*retro_run)(void);
static void (*retro_reset)(void);
static size_t (*retro_serialize_size)(void);
static bool (*retro_serialize)(void*, size_t);
static bool (*retro_unserialize)(const void*, size_t);
static void* (*retro_get_memory_data)(unsigned);
static size_t (*retro_get_memory_size)(unsigned);

// ---- Estado ----
static void* g_lib = nullptr;
static bool g_loaded = false;
static std::vector<uint8_t> g_rom;
static std::string g_sys, g_save;
static int g_fmt = 2;                       // 0 = 0RGB1555, 1 = XRGB8888, 2 = RGB565
static uint8_t* g_vbuf = nullptr;
static size_t g_vcap = 0;
static uint8_t* g_abuf = nullptr;
static size_t g_acap = 0, g_aframes = 0;
static unsigned g_w = 256, g_h = 240;
static int g_buttons = 0;
static retro_system_av_info g_av;

template <typename T>
static bool sym(T& fn, const char* name) {
    fn = reinterpret_cast<T>(dlsym(g_lib, name));
    if (!fn) LOG("falta el símbolo %s", name);
    return fn != nullptr;
}

static bool resolve() {
    return sym(retro_set_environment, "retro_set_environment") &&
           sym(retro_set_video_refresh, "retro_set_video_refresh") &&
           sym(retro_set_audio_sample, "retro_set_audio_sample") &&
           sym(retro_set_audio_sample_batch, "retro_set_audio_sample_batch") &&
           sym(retro_set_input_poll, "retro_set_input_poll") &&
           sym(retro_set_input_state, "retro_set_input_state") &&
           sym(retro_init, "retro_init") &&
           sym(retro_deinit, "retro_deinit") &&
           sym(retro_load_game, "retro_load_game") &&
           sym(retro_unload_game, "retro_unload_game") &&
           sym(retro_get_system_av_info, "retro_get_system_av_info") &&
           sym(retro_run, "retro_run") &&
           sym(retro_reset, "retro_reset") &&
           sym(retro_serialize_size, "retro_serialize_size") &&
           sym(retro_serialize, "retro_serialize") &&
           sym(retro_unserialize, "retro_unserialize") &&
           sym(retro_get_memory_data, "retro_get_memory_data") &&
           sym(retro_get_memory_size, "retro_get_memory_size");
}

// ---- Callbacks ----
static void core_log(int, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, "Core", fmt, ap);
    va_end(ap);
}

static bool env_cb(unsigned cmd, void* data) {
    switch (cmd & 0xFFFF) {                 // se ignoran los bits EXPERIMENTAL/PRIVATE
        case 3:  *static_cast<bool*>(data) = true; return true;                       // GET_CAN_DUPE
        case 9:  *static_cast<const char**>(data) = g_sys.c_str(); return true;       // GET_SYSTEM_DIRECTORY
        case 31: *static_cast<const char**>(data) = g_save.c_str(); return true;      // GET_SAVE_DIRECTORY
        case 27: static_cast<retro_log_callback*>(data)->log = core_log; return true; // GET_LOG_INTERFACE
        case 17: *static_cast<bool*>(data) = false; return true;                      // GET_VARIABLE_UPDATE
        case 52: *static_cast<unsigned*>(data) = 0; return true;                      // GET_CORE_OPTIONS_VERSION
        case 10: {                                                                    // SET_PIXEL_FORMAT
            int f = *static_cast<int*>(data);
            if (f == 0 || f == 1 || f == 2) { g_fmt = f; return true; }
            return false;
        }
        default: return false;              // GET_VARIABLE y el resto: el núcleo usa sus valores por defecto
    }
}

static void video_cb(const void* data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !g_vbuf || (size_t)w * h * 4 > g_vcap) return;   // data == null: fotograma repetido
    g_w = w; g_h = h;
    uint32_t* dst = reinterpret_cast<uint32_t*>(g_vbuf);          // salida: bytes R,G,B,A
    for (unsigned y = 0; y < h; y++) {
        const uint8_t* row = static_cast<const uint8_t*>(data) + y * pitch;
        if (g_fmt == 1) {
            const uint32_t* s = reinterpret_cast<const uint32_t*>(row);
            for (unsigned x = 0; x < w; x++) {
                uint32_t p = s[x];
                *dst++ = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
            }
        } else {
            const uint16_t* s = reinterpret_cast<const uint16_t*>(row);
            for (unsigned x = 0; x < w; x++) {
                uint32_t p = s[x], r, g, b;
                if (g_fmt == 2) {
                    r = (p >> 11) & 31; g = (p >> 5) & 63; b = p & 31;
                    r = (r << 3) | (r >> 2); g = (g << 2) | (g >> 4); b = (b << 3) | (b >> 2);
                } else {
                    r = (p >> 10) & 31; g = (p >> 5) & 31; b = p & 31;
                    r = (r << 3) | (r >> 2); g = (g << 3) | (g >> 2); b = (b << 3) | (b >> 2);
                }
                *dst++ = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        }
    }
}

static size_t audio_batch_cb(const int16_t* data, size_t frames) {
    if (!g_abuf) return frames;
    size_t room = g_acap / 4 - g_aframes;
    size_t n = frames < room ? frames : room;
    memcpy(g_abuf + g_aframes * 4, data, n * 4);
    g_aframes += n;
    return frames;
}

static void audio_cb(int16_t l, int16_t r) {
    int16_t pair[2] = {l, r};
    audio_batch_cb(pair, 1);
}

static void poll_cb() {}

static int16_t state_cb(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port != 0 || device != 1) return 0;           // RETRO_DEVICE_JOYPAD
    if (id == 256) return (int16_t)(g_buttons & 0xFFFF); // RETRO_DEVICE_ID_JOYPAD_MASK
    return (id < 16) ? (int16_t)((g_buttons >> id) & 1) : 0;
}

// ---- JNI ----
#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_nesdeck_core_Native_##name

JNI(jboolean, load)(JNIEnv* env, jobject, jstring jlib, jstring jsys, jstring jsave,
                    jbyteArray jrom, jobject jvideo, jobject jaudio) {
    if (g_loaded) return JNI_FALSE;
    const char* lib = env->GetStringUTFChars(jlib, nullptr);
    g_lib = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!g_lib) LOG("dlopen(%s): %s", lib, dlerror());
    env->ReleaseStringUTFChars(jlib, lib);
    if (!g_lib || !resolve()) return JNI_FALSE;

    const char* s = env->GetStringUTFChars(jsys, nullptr);  g_sys = s;  env->ReleaseStringUTFChars(jsys, s);
    s = env->GetStringUTFChars(jsave, nullptr);             g_save = s; env->ReleaseStringUTFChars(jsave, s);

    jsize n = env->GetArrayLength(jrom);
    g_rom.assign((size_t)n, 0);
    env->GetByteArrayRegion(jrom, 0, n, reinterpret_cast<jbyte*>(g_rom.data()));

    g_vbuf = static_cast<uint8_t*>(env->GetDirectBufferAddress(jvideo));
    g_vcap = (size_t)env->GetDirectBufferCapacity(jvideo);
    g_abuf = static_cast<uint8_t*>(env->GetDirectBufferAddress(jaudio));
    g_acap = (size_t)env->GetDirectBufferCapacity(jaudio);
    if (!g_vbuf || !g_abuf) return JNI_FALSE;

    retro_set_environment(env_cb);
    retro_init();
    retro_set_video_refresh(video_cb);
    retro_set_audio_sample(audio_cb);
    retro_set_audio_sample_batch(audio_batch_cb);
    retro_set_input_poll(poll_cb);
    retro_set_input_state(state_cb);

    retro_game_info info = {"game.nes", g_rom.data(), g_rom.size(), nullptr};
    if (!retro_load_game(&info)) { retro_deinit(); return JNI_FALSE; }
    memset(&g_av, 0, sizeof(g_av));
    retro_get_system_av_info(&g_av);
    g_loaded = true;
    return JNI_TRUE;
}

JNI(jdoubleArray, timing)(JNIEnv* env, jobject) {
    jdouble v[2] = {g_av.timing.fps, g_av.timing.sample_rate};
    jdoubleArray a = env->NewDoubleArray(2);
    env->SetDoubleArrayRegion(a, 0, 2, v);
    return a;
}

JNI(void, runFrame)(JNIEnv* env, jobject, jint buttons, jintArray out) {
    if (!g_loaded) return;
    g_buttons = buttons;
    g_aframes = 0;
    retro_run();
    jint r[3] = {(jint)g_aframes, (jint)g_w, (jint)g_h};
    env->SetIntArrayRegion(out, 0, 3, r);
}

JNI(jbyteArray, getSram)(JNIEnv* env, jobject) {
    if (!g_loaded) return nullptr;
    size_t n = retro_get_memory_size(0);                    // RETRO_MEMORY_SAVE_RAM
    void* p = retro_get_memory_data(0);
    if (!p || n == 0) return nullptr;
    jbyteArray a = env->NewByteArray((jsize)n);
    env->SetByteArrayRegion(a, 0, (jsize)n, static_cast<jbyte*>(p));
    return a;
}

JNI(void, setSram)(JNIEnv* env, jobject, jbyteArray data) {
    if (!g_loaded) return;
    size_t n = retro_get_memory_size(0);
    void* p = retro_get_memory_data(0);
    if (!p || n == 0) return;
    size_t len = (size_t)env->GetArrayLength(data);
    if (len > n) len = n;
    env->GetByteArrayRegion(data, 0, (jsize)len, static_cast<jbyte*>(p));
}

JNI(jbyteArray, saveState)(JNIEnv* env, jobject) {
    if (!g_loaded) return nullptr;
    size_t n = retro_serialize_size();
    if (n == 0) return nullptr;
    std::vector<uint8_t> buf(n);
    if (!retro_serialize(buf.data(), n)) return nullptr;
    jbyteArray a = env->NewByteArray((jsize)n);
    env->SetByteArrayRegion(a, 0, (jsize)n, reinterpret_cast<jbyte*>(buf.data()));
    return a;
}

JNI(jboolean, loadState)(JNIEnv* env, jobject, jbyteArray data) {
    if (!g_loaded) return JNI_FALSE;
    jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> buf((size_t)n);
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(buf.data()));
    return retro_unserialize(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}

JNI(void, reset)(JNIEnv*, jobject) { if (g_loaded) retro_reset(); }

JNI(void, unload)(JNIEnv*, jobject) {
    if (!g_loaded) return;
    retro_unload_game();
    retro_deinit();
    g_loaded = false;
    g_vbuf = nullptr;
    g_abuf = nullptr;
}
