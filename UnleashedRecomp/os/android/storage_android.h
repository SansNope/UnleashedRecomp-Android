#pragma once

#include <filesystem>

namespace os::android
{
    // App-private internal files directory (e.g. /data/user/0/org.libsdl.app/files).
    // Empty if SDL/JNI is not ready yet, so never call this from a static initializer.
    const std::filesystem::path & GetInternalFilesDir();

    // App-specific external files directory (e.g. /storage/emulated/0/Android/data/org.libsdl.app/files).
    // Reachable from a PC over USB (MTP) without root and needs no runtime permissions.
    // Empty if unavailable; never call from a static initializer.
    const std::filesystem::path & GetExternalFilesDir();

    // Root directory for game files and user data (config/saves). Prefers the legacy
    // internal install layout (game files pushed over adb before the APK became
    // distributable); otherwise an "UnleashedRecomp" directory on external app storage,
    // which users can populate from a PC without root.
    const std::filesystem::path & GetDataRoot();


}
