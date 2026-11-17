#if defined(__ANDROID__)
#include "android_companion.h"
#include <debug/map_info.h>
#include <debug/teleport.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>
#include <jni.h>

namespace {
using Clock = std::chrono::steady_clock;
// Low bit: active. Upper bits: lifecycle generation, to reject a publication
// that started before pause/dismiss/resume, even if the screen is active again.
std::atomic<uint64_t> session{0};
std::mutex mutex;
struct Snapshot {
    debug_menu::MapInfo map;
    debug_menu::TeleportSnapshot field;
    Clock::time_point time{};
} snapshot;

// Native wide strings are UTF-32 on Android, while existing guest text readers
// can also retain UTF-16 code units in wchar_t slots. Accept both representations.
jstring String(JNIEnv* env, const std::wstring& text) {
    std::vector<jchar> utf16;
    for (size_t i = 0; i < text.size(); ++i) {
        uint32_t c = uint32_t(text[i]);
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < text.size() &&
            uint32_t(text[i + 1]) >= 0xdc00 && uint32_t(text[i + 1]) <= 0xdfff) {
            utf16.push_back(jchar(c));
            utf16.push_back(jchar(text[++i]));
            continue;
        }
        if (c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) c = 0xfffd;
        if (c > 0xffff) {
            c -= 0x10000;
            utf16.push_back(jchar(0xd800 + (c >> 10)));
            utf16.push_back(jchar(0xdc00 + (c & 0x3ff)));
        } else utf16.push_back(jchar(c));
    }
    return env->NewString(utf16.data(), jsize(utf16.size()));
}
bool Finite(debug_menu::Position p) {
    return std::isfinite(p.x) && std::isfinite(p.y) && std::isfinite(p.z);
}
}

void android_companion::Publish() {
    const auto publicationSession = session.load(std::memory_order_relaxed);
    if (!(publicationSession & 1)) return;
    // Only this producer touches lastPublish. UI readers never access guest memory.
    static Clock::time_point lastPublish;
    const auto now = Clock::now();
    if (now - lastPublish < std::chrono::milliseconds(250)) return;
    lastPublish = now;
    Snapshot next;
    next.map = debug_menu::GetMapInfo();
    next.field = debug_menu::GetTeleportSnapshot();
    next.time = now;
    if (!next.map.available || !next.field.available || !Finite(next.field.current)) {
        next.field.available = false;
        next.field.pois.clear();
    } else {
        std::erase_if(next.field.pois, [](const auto& p) { return !Finite(p.position); });
        if (next.field.pois.size() > 64) next.field.pois.resize(64);
    }
    std::lock_guard lock(mutex);
    if (session.load(std::memory_order_relaxed) == publicationSession) snapshot = std::move(next);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_freefrank_lostodyssey_CompanionSnapshot_nativeSetEnabled(JNIEnv*, jclass, jboolean active) {
    // Opening/resuming must not briefly expose an old scene.
    std::lock_guard lock(mutex);
    session.store(((session.load(std::memory_order_relaxed) + 2) & ~uint64_t(1)) |
        (active == JNI_TRUE ? 1 : 0), std::memory_order_relaxed);
    snapshot = {};
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_freefrank_lostodyssey_CompanionSnapshot_nativeRead(JNIEnv* env, jclass) {
    try {
        Snapshot current;
        {
            std::unique_lock lock(mutex, std::try_to_lock);
            if (lock.owns_lock() && (session.load(std::memory_order_relaxed) & 1) &&
                Clock::now() - snapshot.time < std::chrono::milliseconds(1500)) current = snapshot;
        }
        const bool valid = current.map.available && current.field.available;
        const size_t count = valid ? current.field.pois.size() : 0;
        std::vector<jfloat> positions;
        if (valid) {
            const auto p = current.field.current;
            positions = {p.x, p.y, p.z};
            for (const auto& poi : current.field.pois) {
                positions.push_back(poi.position.x);
                positions.push_back(poi.position.y);
                positions.push_back(poi.position.z);
            }
        }
        auto stringClass = env->FindClass("java/lang/String");
        if (!stringClass) return nullptr;
        auto labels = env->NewObjectArray(jsize(count), stringClass, nullptr);
        env->DeleteLocalRef(stringClass);
        if (!labels) return nullptr;
        for (size_t i = 0; i < count; ++i) {
            auto text = String(env, current.field.pois[i].label);
            if (!text) return nullptr;
            env->SetObjectArrayElement(labels, jsize(i), text);
            env->DeleteLocalRef(text);
            if (env->ExceptionCheck()) return nullptr;
        }
        auto coordinates = env->NewFloatArray(jsize(positions.size()));
        if (!coordinates) return nullptr;
        if (!positions.empty()) env->SetFloatArrayRegion(coordinates, 0, jsize(positions.size()), positions.data());
        if (env->ExceptionCheck()) return nullptr;
        const auto mapName = current.map.name.empty() ? L"地图" : current.map.name;
        auto title = String(env, current.map.available ? mapName + L" · ID " + std::to_wstring(current.map.id) : L"等待地图");
        if (!title) return nullptr;
        auto status = String(env, valid ? L"方位示意 · 无道路或地图底图" : L"等待可控制地图；战斗、过场或加载中不显示位置");
        if (!status) return nullptr;
        auto type = env->FindClass("io/github/freefrank/lostodyssey/CompanionSnapshot");
        if (!type) return nullptr;
        auto constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;Ljava/lang/String;[F[Ljava/lang/String;)V");
        if (!constructor || env->ExceptionCheck()) return nullptr;
        return env->NewObject(type, constructor, title, status, coordinates, labels);
    } catch (...) {
        // A UI polling failure must not unwind through JNI or stop the game.
        return nullptr;
    }
}
#endif
