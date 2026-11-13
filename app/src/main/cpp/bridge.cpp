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
struct retro_game_info {
    const char* path;
    const void* data;
    size_t size;
    const char* meta;
};
struct retro_system_info {
    const char* library_name;
    const char* library_version;
    const char* valid_extensions;
    bool need_fullpath;
    bool block_extract;
};
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
static void (*retro_get_system_info)(retro_system_info*);
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
           sym(retro_get_system_info, "retro_get_system_info") &&
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

static void close_library() {
    if (g_lib) {
        dlclose(g_lib);
        g_lib = nullptr;
    }
    retro_set_environment = nullptr;
    retro_set_video_refresh = nullptr;
    retro_set_audio_sample = nullptr;
    retro_set_audio_sample_batch = nullptr;
    retro_set_input_poll = nullptr;
    retro_set_input_state = nullptr;
    retro_init = nullptr;
    retro_get_system_info = nullptr;
    retro_deinit = nullptr;
    retro_load_game = nullptr;
    retro_unload_game = nullptr;
    retro_get_system_av_info = nullptr;
    retro_run = nullptr;
    retro_reset = nullptr;
    retro_serialize_size = nullptr;
    retro_serialize = nullptr;
    retro_unserialize = nullptr;
    retro_get_memory_data = nullptr;
    retro_get_memory_size = nullptr;
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
    // Libretro puede enviar nullptr para repetir el fotograma anterior.
    if (!data || !g_vbuf || w == 0 || h == 0 || w > 512 || h > 480) return;
    if ((size_t)w > g_vcap / 4 / (size_t)h) return;

    const size_t bytes_per_pixel = (g_fmt == 1) ? 4u : 2u;
    if (pitch < (size_t)w * bytes_per_pixel) return;

    uint32_t* dst = reinterpret_cast<uint32_t*>(g_vbuf);
    const uint8_t* src = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < h; ++y) {
        const uint8_t* row = src + (size_t)y * pitch;
        for (unsigned x = 0; x < w; ++x) {
            uint32_t r, g, b;
            if (g_fmt == 1) {
                // XRGB8888 de libretro: 0x00RRGGBB.
                uint32_t p;
                memcpy(&p, row + (size_t)x * 4, sizeof(p));
                r = (p >> 16) & 0xFF;
                g = (p >> 8) & 0xFF;
                b = p & 0xFF;
            } else {
                uint16_t p;
                memcpy(&p, row + (size_t)x * 2, sizeof(p));
                if (g_fmt == 2) { // RGB565
                    r = (p >> 11) & 31; g = (p >> 5) & 63; b = p & 31;
                    r = (r << 3) | (r >> 2);
                    g = (g << 2) | (g >> 4);
                    b = (b << 3) | (b >> 2);
                } else { // 0RGB1555
                    r = (p >> 10) & 31; g = (p >> 5) & 31; b = p & 31;
                    r = (r << 3) | (r >> 2);
                    g = (g << 3) | (g >> 2);
                    b = (b << 3) | (b >> 2);
                }
            }
            // Android ARGB_8888 espera el entero 0xAARRGGBB.
            // FCEUmm entrega los canales rojo y azul en orden BGR para este formato.
            // Intercambiarlos aquí produce el ARGB_8888 esperado por Android.
            dst[(size_t)y * w + x] = 0xFF000000u | (b << 16) | (g << 8) | r;
        }
    }
    g_w = w;
    g_h = h;
}

static size_t audio_batch_cb(const int16_t* data, size_t frames) {
    if (frames == 0) return 0;
    if (!data || !g_abuf) return 0;
    const size_t capacity = g_acap / 4;
    if (g_aframes >= capacity) return 0;
    const size_t room = capacity - g_aframes;
    const size_t accepted = frames < room ? frames : room;
    if (accepted == 0) return 0;
    memcpy(g_abuf + g_aframes * 4, data, accepted * 4);
    g_aframes += accepted;
    return accepted;
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
                    jstring jrompath, jbyteArray jrom, jobject jvideo, jobject jaudio) {
    if (g_loaded || g_lib || !jlib || !jsys || !jsave || !jrom || !jvideo || !jaudio)
        return JNI_FALSE;

    const char* lib = env->GetStringUTFChars(jlib, nullptr);
    if (!lib) return JNI_FALSE;
    g_lib = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!g_lib) LOG("dlopen(%s): %s", lib, dlerror());
    env->ReleaseStringUTFChars(jlib, lib);
    if (!g_lib) return JNI_FALSE;
    if (!resolve()) {
        close_library();
        return JNI_FALSE;
    }

    const char* s = env->GetStringUTFChars(jsys, nullptr);
    if (!s) { close_library(); return JNI_FALSE; }
    g_sys = s;
    env->ReleaseStringUTFChars(jsys, s);

    s = env->GetStringUTFChars(jsave, nullptr);
    if (!s) { close_library(); return JNI_FALSE; }
    g_save = s;
    env->ReleaseStringUTFChars(jsave, s);

    if (!jrompath || !jrom) { close_library(); return JNI_FALSE; }
    const char* rom_path = env->GetStringUTFChars(jrompath, nullptr);
    if (!rom_path) { close_library(); return JNI_FALSE; }

    const jsize n = env->GetArrayLength(jrom);
    if (n <= 16) {
        env->ReleaseStringUTFChars(jrompath, rom_path);
        close_library();
        return JNI_FALSE;
    }
    g_rom.assign((size_t)n, 0);
    env->GetByteArrayRegion(jrom, 0, n, reinterpret_cast<jbyte*>(g_rom.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->ReleaseStringUTFChars(jrompath, rom_path);
        g_rom.clear();
        close_library();
        return JNI_FALSE;
    }

    // Consultar la política real del núcleo. FCEUmm declara need_fullpath=true:
    // necesita la ruta real de la ROM y no el nombre ficticio "game.nes".
    retro_system_info core_info{};
    retro_get_system_info(&core_info);
    LOG("Core: %s %s, extensions=%s, need_fullpath=%d; ROM=%s (%d bytes)",
        core_info.library_name ? core_info.library_name : "?",
        core_info.library_version ? core_info.library_version : "?",
        core_info.valid_extensions ? core_info.valid_extensions : "?",
        core_info.need_fullpath ? 1 : 0, rom_path, n);

    g_vbuf = static_cast<uint8_t*>(env->GetDirectBufferAddress(jvideo));
    const jlong video_capacity = env->GetDirectBufferCapacity(jvideo);
    g_abuf = static_cast<uint8_t*>(env->GetDirectBufferAddress(jaudio));
    const jlong audio_capacity = env->GetDirectBufferCapacity(jaudio);
    if (!g_vbuf || !g_abuf || video_capacity <= 0 || audio_capacity <= 0) {
        env->ReleaseStringUTFChars(jrompath, rom_path);
        g_vbuf = nullptr; g_abuf = nullptr; g_vcap = g_acap = 0;
        g_rom.clear();
        close_library();
        return JNI_FALSE;
    }
    g_vcap = (size_t)video_capacity;
    g_acap = (size_t)audio_capacity;
    g_aframes = 0;
    g_w = 256; g_h = 240;
    g_fmt = 2;

    retro_set_environment(env_cb);
    retro_set_video_refresh(video_cb);
    retro_set_audio_sample(audio_cb);
    retro_set_audio_sample_batch(audio_batch_cb);
    retro_set_input_poll(poll_cb);
    retro_set_input_state(state_cb);
    retro_init();

    // Si el núcleo exige ruta completa, se le entrega la ruta real del archivo.
    // Para núcleos que aceptan carga desde memoria, se entrega el contenido en data.
    retro_game_info info = {
        rom_path,
        core_info.need_fullpath ? nullptr : g_rom.data(),
        core_info.need_fullpath ? 0u : g_rom.size(),
        nullptr
    };
    const bool game_ok = retro_load_game(&info);
    env->ReleaseStringUTFChars(jrompath, rom_path);
    if (!game_ok) {
        LOG("retro_load_game rechazó la ROM; revisa ruta, formato y cabecera iNES.");
        retro_deinit();
        g_vbuf = nullptr; g_abuf = nullptr; g_vcap = g_acap = 0;
        g_rom.clear();
        close_library();
        return JNI_FALSE;
    }

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
    if (g_loaded) {
        retro_unload_game();
        retro_deinit();
    }
    g_loaded = false;
    g_vbuf = nullptr;
    g_abuf = nullptr;
    g_vcap = g_acap = g_aframes = 0;
    g_rom.clear();
    g_sys.clear();
    g_save.clear();
    memset(&g_av, 0, sizeof(g_av));
    close_library();
}
