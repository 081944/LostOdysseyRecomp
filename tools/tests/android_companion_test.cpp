// Game-free Android fixture for the production snapshot/JNI boundary.
// Build as an ordinary NDK executable and run with adb; no APK is required.
#include <hid/android_companion.cpp>
#include <cstdio>
#include <limits>
#include <memory>
#include <stdexcept>
#include <thread>

namespace {
debug_menu::MapInfo mapInput;
debug_menu::TeleportSnapshot fieldInput;
int mapReads = 0;
JNIEnv* activeEnvironment = nullptr;
bool restartDuringRead = false;
std::vector<std::unique_ptr<std::u16string>> strings;
std::vector<float> capturedPositions;
std::vector<std::u16string> capturedLabels;
std::u16string capturedTitle;

void Require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
JNIEnv MakeEnvironment(JNINativeInterface& table) {
    table.FindClass = [](JNIEnv*, const char*) -> jclass { return reinterpret_cast<jclass>(1); };
    table.DeleteLocalRef = [](JNIEnv*, jobject) {};
    table.ExceptionCheck = [](JNIEnv*) -> jboolean { return JNI_FALSE; };
    table.NewString = [](JNIEnv*, const jchar* value, jsize size) -> jstring {
        auto text = std::make_unique<std::u16string>();
        if (size) text->assign(reinterpret_cast<const char16_t*>(value), size);
        auto result = reinterpret_cast<jstring>(text.get());
        strings.push_back(std::move(text));
        return result;
    };
    table.NewObjectArray = [](JNIEnv*, jsize size, jclass, jobject) -> jobjectArray {
        capturedLabels.assign(size, {});
        return reinterpret_cast<jobjectArray>(&capturedLabels);
    };
    table.SetObjectArrayElement = [](JNIEnv*, jobjectArray, jsize index, jobject text) {
        capturedLabels.at(index) = *reinterpret_cast<std::u16string*>(text);
    };
    table.NewFloatArray = [](JNIEnv*, jsize size) -> jfloatArray {
        capturedPositions.assign(size, 0);
        return reinterpret_cast<jfloatArray>(&capturedPositions);
    };
    table.SetFloatArrayRegion = [](JNIEnv*, jfloatArray, jsize start, jsize size, const jfloat* values) {
        Require(start == 0 && size == jsize(capturedPositions.size()), "coordinate array bounds");
        std::copy_n(values, size, capturedPositions.begin());
    };
    table.GetMethodID = [](JNIEnv*, jclass, const char* name, const char* signature) -> jmethodID {
        Require(std::string(name) == "<init>" && std::string(signature) ==
            "(Ljava/lang/String;Ljava/lang/String;[F[Ljava/lang/String;)V", "Java constructor contract");
        return reinterpret_cast<jmethodID>(1);
    };
    table.NewObjectV = [](JNIEnv*, jclass, jmethodID, va_list arguments) -> jobject {
        auto title = va_arg(arguments, jstring);
        capturedTitle = *reinterpret_cast<std::u16string*>(title);
        return reinterpret_cast<jobject>(1);
    };
    JNIEnv env{};
    env.functions = &table;
    return env;
}
void Read(JNIEnv& env) {
    Require(Java_io_github_freefrank_lostodyssey_CompanionSnapshot_nativeRead(&env, nullptr) != nullptr,
        "JNI must produce an owned snapshot object");
}
void Enabled(JNIEnv& env, bool active) {
    Java_io_github_freefrank_lostodyssey_CompanionSnapshot_nativeSetEnabled(&env, nullptr, active ? JNI_TRUE : JNI_FALSE);
}
}

debug_menu::MapInfo debug_menu::GetMapInfo() {
    ++mapReads;
    if (restartDuringRead) {
        restartDuringRead = false;
        Enabled(*activeEnvironment, false);
        Enabled(*activeEnvironment, true);
    }
    return mapInput;
}
debug_menu::TeleportSnapshot debug_menu::GetTeleportSnapshot() { return fieldInput; }

int main() {
    try {
        JNINativeInterface table{};
        auto env = MakeEnvironment(table);
        activeEnvironment = &env;
        Enabled(env, false);
        android_companion::Publish();
        Require(mapReads == 0, "inactive companion must not collect game state");
        Read(env);
        Require(capturedPositions.empty() && capturedLabels.empty(), "inactive state has no markers");

        mapInput = {true, 42, L"地图 \U0001f9ed", L"field"};
        fieldInput.available = true;
        fieldInput.current = {100, 200, 5};
        fieldInput.pois.push_back({1, L"invalid", {std::numeric_limits<float>::quiet_NaN(), 0, 0}});
        for (unsigned i = 0; i < 70; ++i) fieldInput.pois.push_back({i + 2, L"出口", {float(i), 0, 0}});
        Enabled(env, true);
        android_companion::Publish();
        Read(env);
        Require(capturedTitle == u"地图 \U0001f9ed · ID 42", "UTF-32 to UTF-16 must preserve CJK and surrogate pairs");
        Require(capturedLabels.size() == 64 && capturedPositions.size() == 195, "POI payload bound");
        Require(capturedPositions[0] == 100 && capturedLabels[0] == u"出口", "player and filtered POI data");
        mapInput.name = std::wstring{L'地', wchar_t(0xd83e), wchar_t(0xdded), wchar_t(0xd800)};
        std::this_thread::sleep_for(std::chrono::milliseconds(260));
        android_companion::Publish();
        Read(env);
        Require(capturedTitle == u"地\U0001f9ed\ufffd · ID 42", "guest UTF-16 pairs and malformed code units");
        std::this_thread::sleep_for(std::chrono::milliseconds(260));
        restartDuringRead = true;
        android_companion::Publish();
        Read(env);
        Require(capturedPositions.empty(), "an in-flight publication cannot cross a lifecycle generation");
        std::this_thread::sleep_for(std::chrono::milliseconds(260));
        android_companion::Publish();
        Read(env);
        Require(!capturedPositions.empty(), "the new session accepts its own publication");

        // A stale producer must not leave ghost markers after loading or a stopped game thread.
        std::this_thread::sleep_for(std::chrono::milliseconds(1600));
        Read(env);
        Require(capturedPositions.empty() && capturedLabels.empty(), "stale snapshot expires");
        mapInput.available = false;
        android_companion::Publish();
        Read(env);
        Require(capturedPositions.empty(), "unknown map discards position even if the player snapshot exists");
        Enabled(env, false);
        const int reads = mapReads;
        android_companion::Publish();
        Require(mapReads == reads, "closing the display stops collection");
        Enabled(env, true);
        Read(env);
        Require(capturedPositions.empty(), "resume cannot expose the previous session's snapshot");
        std::puts("Android companion JNI/expiry/Unicode checks passed");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "Companion fixture failed: %s\n", error.what());
        return 1;
    }
}
