#pragma once

#include <filesystem>

namespace os::android
{
    // App-private internal files directory (e.g. /data/user/0/org.libsdl.app/files).
    // Empty if SDL/JNI is not ready yet, so never call this from a static initializer.
    const std::filesystem::path & GetInternalFilesDir();

    // App-specific external files directory (e.g. /storage/emulated/0/Android/data/<pkg>/files).
    // Reachable from a PC over USB (MTP) without root and needs no runtime permissions.
    // Empty if unavailable; never call from a static initializer.
    const std::filesystem::path & GetExternalFilesDir();

    // App-specific media directory (e.g. /storage/emulated/0/Android/media/<pkg>).
    // Unlike Android/data, on-device file managers can browse it on Android 11+,
    // so users can drop game files there without a PC. Empty if unavailable.
    const std::filesystem::path & GetExternalMediaDir();

    // Root directory for game files and user data (config/saves). Prefers the legacy
    // internal install layout (game files pushed over adb before the APK became
    // distributable), then a populated "UnleashedRecomp" directory on external app
    // storage, then a populated one under Android/media; defaults to external app
    // storage, which users can populate from a PC without root.
    const std::filesystem::path & GetDataRoot();

    // Root directory for ".config" (settings, saves). An existing install keeps using
    // ".config" next to GetDataRoot() so upgrading never relocates a user's save data.
    // A fresh install prefers Android/media instead: unlike Android/data, it is not
    // hidden from other apps by scoped storage on Android 11+, so tools that need a
    // real filesystem path (e.g. Syncthing) can reach saves there without root or SAF.
    const std::filesystem::path & GetConfigRoot();
}
