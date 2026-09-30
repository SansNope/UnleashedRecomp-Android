#include "paths.h"
#include <os/process.h>

std::filesystem::path g_executableRoot = os::process::GetExecutableRoot();

bool CheckPortable()
{
    return std::filesystem::exists(g_executableRoot / "portable.txt");
}

std::filesystem::path BuildUserPath()
{
    if (CheckPortable())
        return g_executableRoot;

    std::filesystem::path userPath;

#if defined(_WIN32)
    PWSTR knownPath = NULL;
    if (SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, NULL, &knownPath) == S_OK)
        userPath = std::filesystem::path{ knownPath } / USER_DIRECTORY;

    CoTaskMemFree(knownPath);
#elif defined(__ANDROID__)
    // No meaningful $HOME/passwd entry for an app UID; getpwuid()->pw_dir returns a
    // generic path apps can't write to. See GetConfigRoot() for where config/saves
    // actually land (next to the game files for an existing install, Android/media
    // for a fresh one so raw-path tools like Syncthing can reach saves).
    userPath = os::android::GetConfigRoot() / ".config" / USER_DIRECTORY;
#elif defined(__linux__) || defined(__APPLE__)
    const char* homeDir = getenv("HOME");
#if defined(__linux__)
    if (homeDir == nullptr)
    {
        homeDir = getpwuid(getuid())->pw_dir;
    }
#endif

    if (homeDir != nullptr)
    {
        // Prefer to store in the .config directory if it exists. Use the home directory otherwise.
        std::filesystem::path homePath = homeDir;
#if defined(__linux__)
        std::filesystem::path configPath = homePath / ".config";
#else
        std::filesystem::path configPath = homePath / "Library" / "Application Support";
#endif
        if (std::filesystem::exists(configPath))
            userPath = configPath / USER_DIRECTORY;
        else
            userPath = homePath / ("." USER_DIRECTORY);
    }
#else
    static_assert(false, "GetUserPath() not implemented for this platform.");
#endif

    return userPath;
}

const std::filesystem::path& GetUserPath()
{
    // Lazy: on Android the path is resolved through SDL/JNI, which isn't available
    // yet when static initializers of this library run.
    static std::filesystem::path userPath = BuildUserPath();
    return userPath;
}
