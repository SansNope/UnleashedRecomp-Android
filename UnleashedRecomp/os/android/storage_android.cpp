#include "storage_android.h"

#include <user/paths.h>

#include <SDL.h>
#include <SDL_system.h>

#include <cstdio>
#include <cstdlib>

namespace os::android
{
    // A directory can exist but be unusable: e.g. created via `adb shell mkdir` it's owned
    // by the shell uid, and the app gets EACCES through FUSE. std::filesystem calls on such
    // paths throw all over the codebase (Config::Load etc.), so catch this case up front.
    static bool ProbeDirWritable(const std::filesystem::path &dir)
    {
        std::filesystem::path probePath = dir / ".write_probe";
        FILE *file = fopen(probePath.c_str(), "wb");
        if (file == nullptr)
            return false;

        fclose(file);
        remove(probePath.c_str());
        return true;
    }

    const std::filesystem::path & GetInternalFilesDir()
    {
        static std::filesystem::path path = []() -> std::filesystem::path
        {
            const char *storagePath = SDL_AndroidGetInternalStoragePath();
            return (storagePath != nullptr) ? std::filesystem::path(storagePath) : std::filesystem::path();
        }();

        return path;
    }

    const std::filesystem::path & GetExternalFilesDir()
    {
        static std::filesystem::path path = []() -> std::filesystem::path
        {
            const char *storagePath = SDL_AndroidGetExternalStoragePath();
            return (storagePath != nullptr) ? std::filesystem::path(storagePath) : std::filesystem::path();
        }();

        return path;
    }

    const std::filesystem::path & GetExternalMediaDir()
    {
        static std::filesystem::path path = []() -> std::filesystem::path
        {
            // SDL has no accessor for Android/media, so derive it from the external
            // files path: .../Android/data/<pkg>/files -> .../Android/media/<pkg>.
            const std::filesystem::path &external = GetExternalFilesDir();
            if (external.empty())
                return {};

            std::string str = external.string();
            const std::string marker = "/Android/data/";
            size_t markerPos = str.find(marker);
            if (markerPos == std::string::npos)
                return {};

            size_t pkgBegin = markerPos + marker.size();
            size_t pkgEnd = str.find('/', pkgBegin);
            std::string pkg = (pkgEnd == std::string::npos)
                ? str.substr(pkgBegin)
                : str.substr(pkgBegin, pkgEnd - pkgBegin);
            if (pkg.empty())
                return {};

            return std::filesystem::path(str.substr(0, markerPos)) / "Android" / "media" / pkg;
        }();

        return path;
    }

    const std::filesystem::path & GetDataRoot()
    {
        static std::filesystem::path root = []() -> std::filesystem::path
        {
            std::error_code ec;

            // Legacy layout: game files pushed over adb straight into internal app storage.
            // Keep using it when populated so existing installs are unaffected. Resolved at
            // runtime so it always tracks the installed package id.
            std::filesystem::path legacy = !GetInternalFilesDir().empty()
                ? GetInternalFilesDir() / "UnleashedRecomp"
                : std::filesystem::path(GAME_INSTALL_DIRECTORY);
            if (std::filesystem::exists(legacy / "game", ec))
                return legacy;

            const std::filesystem::path &external = GetExternalFilesDir();

            // Android/media fallback: on-device file managers can browse it on Android 11+
            // (unlike Android/data), so phone-only users can drop game files there. Only
            // picked when populated; external app storage stays the default target.
            const std::filesystem::path &media = GetExternalMediaDir();
            if (!media.empty())
            {
                std::filesystem::path mediaRoot = media / "UnleashedRecomp";
                bool externalPopulated = !external.empty() &&
                    std::filesystem::exists(external / "UnleashedRecomp" / "game", ec);
                if (!externalPopulated && std::filesystem::exists(mediaRoot / "game", ec) &&
                    ProbeDirWritable(mediaRoot))
                {
                    return mediaRoot;
                }
            }

            if (!external.empty())
            {
                std::filesystem::path result = external / "UnleashedRecomp";
                std::filesystem::create_directories(result, ec);

                // Create the media folder too so it is discoverable from an on-device file
                // manager, and keep the media scanner away from game assets placed there.
                if (!media.empty())
                {
                    std::filesystem::create_directories(media / "UnleashedRecomp", ec);
                    std::filesystem::path noMedia = media / ".nomedia";
                    if (!std::filesystem::exists(noMedia, ec))
                    {
                        if (FILE *file = fopen(noMedia.c_str(), "wb"))
                            fclose(file);
                    }
                }

                if (!ProbeDirWritable(result))
                {
                    // Refuse to continue with a poisoned directory - later std::filesystem
                    // calls would throw an uncaught exception with no explanation.
                    char text[1024];
                    snprintf(text, sizeof(text),
                        "The storage folder is not accessible:\n\n%s\n\n"
                        "It was likely created by another tool (e.g. adb shell), so the "
                        "app cannot write to it. Delete the folder from a PC or file "
                        "manager and restart the app.",
                        result.string().c_str());
                    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Unleashed Recomp", text, nullptr);
                    std::_Exit(1);
                }

                return result;
            }

            // External storage unavailable (shouldn't happen on real devices) - stay
            // functional on internal storage.
            std::filesystem::path result = GetInternalFilesDir() / "UnleashedRecomp";
            std::filesystem::create_directories(result, ec);
            return result;
        }();

        return root;
    }

    const std::filesystem::path & GetConfigRoot()
    {
        static std::filesystem::path root = []() -> std::filesystem::path
        {
            std::error_code ec;

            // Existing install: ".config" is already populated next to the game files
            // (legacy internal layout, external app storage, or a media install that
            // predates this function). Keep using it so upgrading never relocates saves.
            if (std::filesystem::exists(GetDataRoot() / ".config" / USER_DIRECTORY, ec))
                return GetDataRoot();

            // Fresh install: prefer Android/media so ".config" (and the save data inside
            // it) sits at a real filesystem path other apps can read without root or SAF.
            const std::filesystem::path &media = GetExternalMediaDir();
            if (!media.empty())
            {
                std::filesystem::path mediaRoot = media / "UnleashedRecomp";
                std::filesystem::create_directories(mediaRoot, ec);
                if (ProbeDirWritable(mediaRoot))
                    return mediaRoot;
            }

            return GetDataRoot();
        }();

        return root;
    }
}
