#include "vulkan_driver_android.h"

#include <os/android/storage_android.h>
#include <os/logger.h>
#include <user/config.h>

#include <adrenotools/driver.h>
#include <miniz.h>
#include <nlohmann/json.hpp>

#include <SDL.h>
#include <SDL_system.h>

#include <cctype>
#include <cstdint>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <fcntl.h>
#include <jni.h>
#include <string>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <vector>

// The APK variants ship source-built Mesa 26.1.4 Turnip from the same cc9b2874cb69
// snapshot: either the clean A725 command-buffer-start quirk or the per-draw WAIT_FOR_ME
// comparator. The FILENAME stays at its historical value because existing installs have
// driver_name.txt pointing at it. Provisioning compares the complete asset contents, so
// clean and WFM binaries (which happen to have the same size) still replace one another.
// The Android Render Mode option selects the normal/GMEM ("none") or Sysmem
// ("sysmem") TU_DEBUG default. An external driver_import/tu_debug.txt remains a
// diagnostic override. "flushall" enables Mesa's real full per-draw flush and causes
// a massive FPS hit.
static constexpr const char *BUNDLED_DRIVER_NAME = "vulkan.unleashed26_1_wfm_a732.so";
// The asset must NOT live under "turnip/": SDL_RWFromFile resolves relative paths against
// internal storage BEFORE the APK asset system, and the driver is extracted to the internal
// "turnip/" directory under the same name - the extracted copy would permanently shadow the
// packaged asset and driver updates shipped in the APK would never be seen again.
static constexpr const char *BUNDLED_DRIVER_ASSET = "bundled_driver/vulkan.unleashed26_1_wfm_a732.so";
static constexpr const char *VAUZI_710_DRIVER_NAME = "vulkan.vauzi710_v2_7.so";
static constexpr const char *VAUZI_710_DRIVER_ASSET = "bundled_driver/vulkan.vauzi710_v2_7.so";
static constexpr const char *EXPERIMENTAL_A725_DRIVER_NAME = "vulkan.wb26_2_rp_pair_ccu_color_a725.so";
static constexpr const char *EXPERIMENTAL_A725_DRIVER_ASSET = "bundled_driver/vulkan.wb26_2_rp_pair_ccu_color_a725.so";
// The A8xx profile deliberately reuses the same gen8-capable binary. Only its forced
// TU_DEBUG preset differs, so carrying a second 19 MB copy in the APK would be wasteful.
static constexpr const char *EXPERIMENTAL_A8XX_DRIVER_NAME = EXPERIMENTAL_A725_DRIVER_NAME;
static constexpr const char *DEFAULT_DRIVER_NAME = "vulkan.unleashed26_1_wfm_a732.so";
static constexpr const char *LAST_IMPORTED_DRIVER_FILE = "last_imported_driver.txt";
static constexpr const char *VULKAN_STARTUP_STATE_FILE = "vulkan_startup_state.txt";

enum class VulkanStartupPhase
{
    CustomPending,
    SafeFallbackPending,
    RecoveryExhausted,
};

struct VulkanStartupState
{
    VulkanStartupPhase phase{};
    EAndroidVulkanDriver configuredDriver{};
    EAndroidRenderMode configuredRenderMode{};
};

static bool g_vulkanStartupPrepared;
static bool g_vulkanStartupStateActive;
static bool g_vulkanStartupSuccessReported;
static bool g_forceSafeVulkanFallback;
static bool g_armCustomVulkanStartup;
static EAndroidVulkanDriver g_runtimeVulkanDriver;
static EAndroidRenderMode g_runtimeRenderMode;

static std::string GetTurnipDir()
{
    const std::filesystem::path &internalDir = os::android::GetInternalFilesDir();
    if (internalDir.empty())
        return {};

    return (internalDir / "turnip/").string();
}

enum class EAndroidGpuFamily
{
    Adreno,
    Xclipse,
    Other,
    Unknown,
};

// Identifies the GPU family from the Vulkan/EGL HAL system properties (the HAL library is
// named vulkan.<ro.hardware.vulkan>.so) without loading any driver. The bundled Turnip build
// only works on Adreno; attempting it on other GPUs crashes the first launch and relies on
// boot recovery to reach the system driver on the second one.
static EAndroidGpuFamily DetectGpuFamily(std::string &description)
{
    char vulkanProp[PROP_VALUE_MAX]{};
    char eglProp[PROP_VALUE_MAX]{};
    __system_property_get("ro.hardware.vulkan", vulkanProp);
    __system_property_get("ro.hardware.egl", eglProp);

    description = {};
    if (vulkanProp[0] != '\0')
        description += vulkanProp;

    if (eglProp[0] != '\0' && strcmp(vulkanProp, eglProp) != 0)
    {
        if (!description.empty())
            description += "/";

        description += eglProp;
    }

    std::string lowered = description;
    for (char &c : lowered)
        c = char(tolower(uint8_t(c)));

    if (lowered.empty())
        return EAndroidGpuFamily::Unknown;

    if (lowered.find("adreno") != std::string::npos)
        return EAndroidGpuFamily::Adreno;

    // Samsung Xclipse (RDNA-based) ships its Vulkan HAL as vulkan.samsung.so.
    if (lowered.find("samsung") != std::string::npos || lowered.find("sgpu") != std::string::npos ||
        lowered.find("xclipse") != std::string::npos)
    {
        return EAndroidGpuFamily::Xclipse;
    }

    return EAndroidGpuFamily::Other;
}

static const char *VulkanDriverName(EAndroidVulkanDriver driver)
{
    switch (driver)
    {
        case EAndroidVulkanDriver::System:   return "System";
        case EAndroidVulkanDriver::Bundled:  return "Bundled";
        case EAndroidVulkanDriver::Vauzi710: return "Vauzi710";
        case EAndroidVulkanDriver::ExperimentalA725: return "ExperimentalA725";
        case EAndroidVulkanDriver::ExperimentalA8xx: return "ExperimentalA8xx";
        case EAndroidVulkanDriver::Imported: return "Imported";
        case EAndroidVulkanDriver::Auto:
        default:                             return "Auto";
    }
}

static const char *RenderModeName(EAndroidRenderMode mode)
{
    switch (mode)
    {
        case EAndroidRenderMode::GMEM:   return "GMEM";
        case EAndroidRenderMode::Sysmem: return "Sysmem";
        case EAndroidRenderMode::Auto:
        default:                         return "Auto";
    }
}

static const char *StartupPhaseName(VulkanStartupPhase phase)
{
    switch (phase)
    {
        case VulkanStartupPhase::CustomPending:       return "custom_pending";
        case VulkanStartupPhase::SafeFallbackPending: return "safe_fallback_pending";
        case VulkanStartupPhase::RecoveryExhausted:   return "recovery_exhausted";
        default:                                      return "unknown";
    }
}

static std::filesystem::path GetVulkanStartupStatePath()
{
    const std::filesystem::path &internalDir = os::android::GetInternalFilesDir();
    return internalDir.empty() ? std::filesystem::path() : internalDir / VULKAN_STARTUP_STATE_FILE;
}

static bool SyncDirectory(const std::filesystem::path &directory)
{
    int directoryFd = open(directory.c_str(), O_RDONLY | O_CLOEXEC | O_DIRECTORY);
    if (directoryFd < 0)
        return false;

    const bool ok = fsync(directoryFd) == 0;
    close(directoryFd);
    return ok;
}

// The state is replaced, never edited in place. fsync(temp) + rename + fsync(parent)
// guarantees that a process/OS kill yields either the complete old state or complete new
// state, never a half-written marker that could make recovery non-deterministic.
static bool WriteVulkanStartupState(const VulkanStartupState &state)
{
    const std::filesystem::path statePath = GetVulkanStartupStatePath();
    if (statePath.empty())
        return false;

    std::filesystem::path tempPath = statePath;
    tempPath += ".tmp";

    char contents[256];
    const int length = snprintf(contents, sizeof(contents),
        "version=1\nphase=%s\nconfigured_driver=%u\nconfigured_render_mode=%u\n",
        StartupPhaseName(state.phase), unsigned(state.configuredDriver), unsigned(state.configuredRenderMode));
    if (length <= 0 || size_t(length) >= sizeof(contents))
        return false;

    int file = open(tempPath.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (file < 0)
        return false;

    size_t written = 0;
    bool ok = true;
    while (written < size_t(length))
    {
        const ssize_t result = write(file, contents + written, size_t(length) - written);
        if (result > 0)
        {
            written += size_t(result);
            continue;
        }

        if (result < 0 && errno == EINTR)
            continue;

        ok = false;
        break;
    }

    if (ok)
        ok = fsync(file) == 0;
    if (close(file) != 0)
        ok = false;

    if (!ok || rename(tempPath.c_str(), statePath.c_str()) != 0)
    {
        unlink(tempPath.c_str());
        return false;
    }

    return SyncDirectory(statePath.parent_path());
}

static bool RemoveVulkanStartupState()
{
    const std::filesystem::path statePath = GetVulkanStartupStatePath();
    if (statePath.empty())
        return false;

    if (unlink(statePath.c_str()) != 0 && errno != ENOENT)
        return false;

    // Also discard an interrupted pre-rename write. It is never considered state by readers.
    std::filesystem::path tempPath = statePath;
    tempPath += ".tmp";
    unlink(tempPath.c_str());
    return SyncDirectory(statePath.parent_path());
}

static bool ReadVulkanStartupState(VulkanStartupState &state, bool &stateFileExists)
{
    const std::filesystem::path statePath = GetVulkanStartupStatePath();
    stateFileExists = false;
    if (statePath.empty())
        return false;

    FILE *file = fopen(statePath.c_str(), "rb");
    if (file == nullptr)
        return false;

    stateFileExists = true;
    char contents[256]{};
    const size_t bytesRead = fread(contents, 1, sizeof(contents) - 1, file);
    const bool readError = ferror(file) != 0;
    fclose(file);
    if (readError || bytesRead == 0 || bytesRead == sizeof(contents) - 1)
        return false;

    unsigned version = 0;
    unsigned configuredDriver = 0;
    unsigned configuredRenderMode = 0;
    char phase[64]{};
    if (sscanf(contents,
            "version=%u\nphase=%63s\nconfigured_driver=%u\nconfigured_render_mode=%u",
            &version, phase, &configuredDriver, &configuredRenderMode) != 4 ||
        version != 1 ||
        configuredDriver > unsigned(EAndroidVulkanDriver::ExperimentalA8xx) ||
        configuredRenderMode > unsigned(EAndroidRenderMode::Sysmem))
    {
        return false;
    }

    if (strcmp(phase, "custom_pending") == 0)
        state.phase = VulkanStartupPhase::CustomPending;
    else if (strcmp(phase, "safe_fallback_pending") == 0)
        state.phase = VulkanStartupPhase::SafeFallbackPending;
    else if (strcmp(phase, "recovery_exhausted") == 0)
        state.phase = VulkanStartupPhase::RecoveryExhausted;
    else
        return false;

    state.configuredDriver = EAndroidVulkanDriver(configuredDriver);
    state.configuredRenderMode = EAndroidRenderMode(configuredRenderMode);
    return true;
}

static bool StartupSelectionMatches(const VulkanStartupState &state)
{
    return state.configuredDriver == Config::VulkanDriver.Value &&
        state.configuredRenderMode == Config::RenderMode.Value;
}

static void ForceSafeVulkanFallback(const VulkanStartupState &previousState, const char *reason)
{
    VulkanStartupState recoveryState
    {
        VulkanStartupPhase::SafeFallbackPending,
        previousState.configuredDriver,
        previousState.configuredRenderMode,
    };

    g_forceSafeVulkanFallback = true;
    g_runtimeVulkanDriver = EAndroidVulkanDriver::System;
    g_runtimeRenderMode = EAndroidRenderMode::Auto;
    g_vulkanStartupStateActive = WriteVulkanStartupState(recoveryState);

    LOGF_WARNING("Vulkan boot recovery: {} (previous phase={}, configured driver={}, render mode={}). "
        "Forcing this startup to System driver + RenderMode Auto; user Config is unchanged.",
        reason, StartupPhaseName(previousState.phase), VulkanDriverName(previousState.configuredDriver),
        RenderModeName(previousState.configuredRenderMode));

    if (!g_vulkanStartupStateActive)
    {
        LOG_ERROR("Vulkan boot recovery: failed to persist the consumed recovery state atomically.");
        // Best-effort consumption still prevents an unwritable stale custom_pending file
        // from forcing the same recovery on every launch.
        RemoveVulkanStartupState();
    }
}

static void PrepareVulkanStartup()
{
    if (g_vulkanStartupPrepared)
        return;

    g_vulkanStartupPrepared = true;
    g_runtimeVulkanDriver = Config::VulkanDriver.Value;
    g_runtimeRenderMode = Config::RenderMode.Value;

    VulkanStartupState previousState{};
    bool stateFileExists = false;
    const bool validState = ReadVulkanStartupState(previousState, stateFileExists);

    if (stateFileExists && (!validState || previousState.phase == VulkanStartupPhase::CustomPending))
    {
        if (!validState)
        {
            previousState =
            {
                VulkanStartupPhase::CustomPending,
                Config::VulkanDriver.Value,
                Config::RenderMode.Value,
            };
            ForceSafeVulkanFallback(previousState, "an invalid/incomplete pending marker was left by the previous launch");
        }
        else
        {
            ForceSafeVulkanFallback(previousState, "the previous custom-driver Vulkan startup did not reach a usable swapchain");
        }
        return;
    }

    if (!validState)
    {
        // Arm only immediately before an actual custom-driver dlopen, after paths and the
        // selected .so have been validated. System mode needs no crash marker.
        g_armCustomVulkanStartup = g_runtimeVulkanDriver != EAndroidVulkanDriver::System;
        return;
    }

    g_vulkanStartupStateActive = true;
    if (previousState.phase == VulkanStartupPhase::SafeFallbackPending)
    {
        // The safe System+Auto attempt itself failed. Consume it without forcing the same
        // fallback forever. A successful startup still clears this exhausted state.
        VulkanStartupState exhaustedState
        {
            VulkanStartupPhase::RecoveryExhausted,
            previousState.configuredDriver,
            previousState.configuredRenderMode,
        };
        if (!WriteVulkanStartupState(exhaustedState))
            LOG_ERROR("Vulkan boot recovery: failed to persist recovery_exhausted state atomically.");

        previousState = exhaustedState;
        LOGF_WARNING("Vulkan boot recovery: the one-time System + RenderMode Auto recovery did not "
            "reach a usable swapchain. Automatic recovery is now exhausted for driver={} / render mode={} "
            "to prevent a restart loop; trying the configured selection without modifying Config.",
            VulkanDriverName(previousState.configuredDriver), RenderModeName(previousState.configuredRenderMode));
    }

    if (!StartupSelectionMatches(previousState))
    {
        LOGF("Vulkan boot recovery: configured selection changed from driver={} / render mode={} to "
            "driver={} / render mode={}; enabling crash recovery for the new selection.",
            VulkanDriverName(previousState.configuredDriver), RenderModeName(previousState.configuredRenderMode),
            VulkanDriverName(Config::VulkanDriver.Value), RenderModeName(Config::RenderMode.Value));
        g_armCustomVulkanStartup = g_runtimeVulkanDriver != EAndroidVulkanDriver::System;
    }
    else
    {
        LOGF_WARNING("Vulkan boot recovery: recovery remains exhausted for driver={} / render mode={}; "
            "no additional forced fallback will be scheduled until this startup succeeds or the selection changes.",
            VulkanDriverName(previousState.configuredDriver), RenderModeName(previousState.configuredRenderMode));
    }
}

static bool ArmCustomVulkanStartup()
{
    if (!g_armCustomVulkanStartup)
        return true;

    VulkanStartupState state
    {
        VulkanStartupPhase::CustomPending,
        Config::VulkanDriver.Value,
        Config::RenderMode.Value,
    };

    if (!WriteVulkanStartupState(state))
    {
        // Never enter an untracked custom-driver startup: failure to create the durable
        // marker deterministically degrades this launch to the safe system path.
        LOG_ERROR("Vulkan boot recovery: unable to arm the custom startup marker; forcing System driver + RenderMode Auto for this launch.");
        g_forceSafeVulkanFallback = true;
        g_runtimeVulkanDriver = EAndroidVulkanDriver::System;
        g_runtimeRenderMode = EAndroidRenderMode::Auto;
        g_armCustomVulkanStartup = false;
        RemoveVulkanStartupState();
        return false;
    }

    g_armCustomVulkanStartup = false;
    g_vulkanStartupStateActive = true;
    LOGF("Vulkan boot recovery: armed custom startup marker (driver={}, render mode={}); "
        "it will be cleared only after device + usable swapchain creation.",
        VulkanDriverName(state.configuredDriver), RenderModeName(state.configuredRenderMode));
    return true;
}

static bool ReadWholeFile(const std::filesystem::path &path, std::vector<uint8_t> &data)
{
    FILE *file = fopen(path.c_str(), "rb");
    if (file == nullptr)
        return false;

    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    fseek(file, 0, SEEK_SET);
    if (size <= 0)
    {
        fclose(file);
        return false;
    }

    data.resize(size_t(size));
    bool ok = fread(data.data(), 1, data.size(), file) == data.size();
    fclose(file);
    return ok;
}

// Writes through a temporary file + rename so a mid-write kill can't leave a truncated
// driver behind that would then be loaded on every subsequent launch.
static bool WriteWholeFile(const std::filesystem::path &path, const void *data, size_t size)
{
    std::filesystem::path tempPath = path;
    tempPath += ".tmp";

    FILE *file = fopen(tempPath.c_str(), "wb");
    if (file == nullptr)
        return false;

    bool ok = fwrite(data, 1, size, file) == size;
    fclose(file);

    if (!ok)
    {
        remove(tempPath.c_str());
        return false;
    }

    std::error_code ec;
    std::filesystem::rename(tempPath, path, ec);
    if (ec)
        return false;

    // Match the permissions a manually pushed driver would have; files created by the
    // app default to 0600, which is unnecessarily tight for something the loader mmaps.
    chmod(path.c_str(), 0755);
    return true;
}

static void WriteTextFileIfMissing(const std::filesystem::path &path, const char *contents)
{
    std::error_code ec;
    if (std::filesystem::exists(path, ec))
        return;

    WriteWholeFile(path, contents, strlen(contents));
}

static void WriteTextFile(const std::filesystem::path &path, const char *contents)
{
    WriteWholeFile(path, contents, strlen(contents));
}

// Reads a file bundled in the APK assets (SDL routes relative paths to the asset manager).
static bool ReadAssetFile(const char *assetPath, std::vector<uint8_t> &data)
{
    SDL_RWops *rw = SDL_RWFromFile(assetPath, "rb");
    if (rw == nullptr)
        return false;

    Sint64 size = SDL_RWsize(rw);
    if (size <= 0)
    {
        SDL_RWclose(rw);
        return false;
    }

    data.resize(size_t(size));
    bool ok = SDL_RWread(rw, data.data(), 1, data.size()) == size_t(size);
    SDL_RWclose(rw);
    return ok;
}

static uint64_t DriverFingerprint(const std::vector<uint8_t> &driver)
{
    // FNV-1a is only a compact log identifier for distinguishing A/B assets. Package
    // integrity still comes from the APK signature and the published SHA-256 checksum.
    uint64_t hash = 14695981039346656037ull;
    for (uint8_t byte : driver)
    {
        hash ^= byte;
        hash *= 1099511628211ull;
    }

    return hash;
}

static bool IsSafeDriverFileName(const std::string &driverName);

// Imports an adrenotools-style driver package: a zip whose meta.json names the entry
// library ("libraryName") plus the driver binaries - the layout ExynosTools ships for
// Samsung Xclipse and AdrenoToolsDrivers releases use. Every regular file is extracted
// flat (basename only, so hostile entry paths cannot escape) into the turnip dir; the
// adrenotools linker namespace resolves the package's dependent libraries from there.
// Zips without meta.json fall back to selecting the single .so inside.
static bool ImportDriverPackage(const std::filesystem::path &zipPath, const std::filesystem::path &turnipDir, std::string &selectedName)
{
    mz_zip_archive zip{};
    if (!mz_zip_reader_init_file(&zip, zipPath.string().c_str(), 0))
    {
        LOGF_ERROR("Driver import: {} is not a readable zip archive.", zipPath.filename().string());
        return false;
    }

    struct ZipEntry
    {
        std::string baseName;
        mz_uint index;
    };

    std::vector<ZipEntry> entries;
    std::string libraryName;
    std::string onlySoName;
    uint32_t soCount = 0;

    const mz_uint fileCount = mz_zip_reader_get_num_files(&zip);
    for (mz_uint i = 0; i < fileCount; i++)
    {
        mz_zip_archive_file_stat stat;
        if (!mz_zip_reader_file_stat(&zip, i, &stat) || stat.m_is_directory)
            continue;

        std::string baseName = std::filesystem::path(stat.m_filename).filename().string();
        if (baseName.empty())
            continue;

        if (baseName.size() > 3 && baseName.compare(baseName.size() - 3, 3, ".so") == 0)
        {
            ++soCount;
            onlySoName = baseName;
        }

        entries.push_back({ std::move(baseName), i });
    }

    for (const ZipEntry &entry : entries)
    {
        if (entry.baseName != "meta.json")
            continue;

        size_t metaSize = 0;
        void *meta = mz_zip_reader_extract_to_heap(&zip, entry.index, &metaSize, 0);
        if (meta != nullptr)
        {
            const auto parsed = nlohmann::json::parse(
                static_cast<const char *>(meta), static_cast<const char *>(meta) + metaSize, nullptr, false);
            if (!parsed.is_discarded())
                libraryName = parsed.value("libraryName", "");

            mz_free(meta);
        }
        break;
    }

    if (libraryName.empty() && soCount == 1)
        libraryName = onlySoName;

    if (!IsSafeDriverFileName(libraryName))
    {
        LOGF_ERROR("Driver import: {} has no usable meta.json libraryName and does not contain exactly one .so.",
            zipPath.filename().string());
        mz_zip_reader_end(&zip);
        return false;
    }

    bool ok = true;
    for (const ZipEntry &entry : entries)
    {
        size_t size = 0;
        void *data = mz_zip_reader_extract_to_heap(&zip, entry.index, &size, 0);
        if (data == nullptr || !WriteWholeFile(turnipDir / entry.baseName, data, size))
        {
            LOGF_ERROR("Driver import: failed to extract {} from {}.", entry.baseName, zipPath.filename().string());
            ok = false;
        }

        if (data != nullptr)
            mz_free(data);

        if (!ok)
            break;
    }

    mz_zip_reader_end(&zip);
    if (ok)
        selectedName = libraryName;

    return ok;
}

// Users can drop an arbitrary Turnip build (plain .so, extracted from the release zip)
// or a whole driver package zip (see ImportDriverPackage) into <external>/driver_import/.
// It is copied byte-for-byte to internal storage, selected
// via driver_name.txt, and moved to driver_import/installed/ so it isn't re-processed every
// launch. Imported binaries must never be rewritten: exact inputs are required for reliable
// A/B tests and a byte-pattern patch cannot establish compatibility with an unknown build.
static void ScanDriverImportDir(const std::filesystem::path &importDir, const std::filesystem::path &turnipDir)
{
    std::error_code ec;
    for (const auto &entry : std::filesystem::directory_iterator(importDir, ec))
    {
        if (!entry.is_regular_file(ec))
            continue;

        const std::filesystem::path extension = entry.path().extension();
        std::string fileName = entry.path().filename().string();
        std::string selectedName;

        if (extension == ".so")
        {
            std::vector<uint8_t> driver;
            if (!ReadWholeFile(entry.path(), driver))
            {
                LOGF_ERROR("Driver import: failed to read {}", fileName);
                continue;
            }

            if (!WriteWholeFile(turnipDir / fileName, driver.data(), driver.size()))
            {
                LOGF_ERROR("Driver import: failed to install {} to internal storage.", fileName);
                continue;
            }

            selectedName = fileName;
        }
        else if (extension == ".zip")
        {
            if (!ImportDriverPackage(entry.path(), turnipDir, selectedName))
                continue;
        }
        else
        {
            continue;
        }

        WriteTextFile(turnipDir / "driver_name.txt", selectedName.c_str());
        WriteTextFile(turnipDir / LAST_IMPORTED_DRIVER_FILE, selectedName.c_str());
        std::filesystem::path installedDir = importDir / "installed";
        std::filesystem::create_directories(installedDir, ec);
        std::filesystem::rename(entry.path(), installedDir / fileName, ec);
        if (ec)
            std::filesystem::remove(entry.path(), ec);

        LOGF("Driver import: installed {} and selected {}.", fileName, selectedName);
    }
}

static void ProcessDriverImportDir(const std::filesystem::path &turnipDir)
{
    const std::filesystem::path &externalDir = os::android::GetExternalFilesDir();
    if (externalDir.empty())
        return;

    std::error_code ec;
    std::filesystem::path importDir = externalDir / "driver_import";
    std::filesystem::create_directories(importDir, ec);

    // Rewritten every launch so testers always see the current instructions.
    WriteTextFile(importDir / "readme.txt",
        "Optional: drop a Vulkan driver here as a plain .so file (e.g. a Mesa\n"
        "Turnip libvulkan_freedreno.so) or as a whole driver-package .zip with\n"
        "a meta.json (AdrenoTools packages, ExynosTools for Samsung Xclipse).\n"
        "On the next launch it will be installed byte-for-byte and selected.\n"
        "Processed files move to the installed/ subfolder. The app never patches\n"
        "imported binaries; use a source-built workaround when one is required.\n"
        "\n"
        "You can also create a tu_debug.txt file in THIS folder to set Turnip's\n"
        "TU_DEBUG options without rebuilding the app; it overrides the Render Mode\n"
        "selection. An fd_dev_features.txt here likewise sets Mesa's\n"
        "FD_DEV_FEATURES (e.g. enable_tp_ubwc_flag_hint=1, which the app already\n"
        "applies automatically on Xiaomi HyperOS 3+ to fix display glitches). The bundled driver has the anti-shimmer fix compiled\n"
        "in and needs no TU_DEBUG options. Diagnostic examples (one per run):\n"
        "  nolrz\n"
        "  sysmem\n"
        "  noubwc\n"
        "NOTE: \"flushall\" enables Mesa's real full per-draw flush - a massive\n"
        "FPS hit. It is for diagnostics only and is not an anti-shimmer fix.\n"
        "Delete tu_debug.txt to return control to the Render Mode option.\n"
        "The app already ships with a working driver; this is for experiments.\n"
        "\n"
        "DIAGNOSTICS: the app writes a log to log.txt in the PARENT folder (one\n"
        "level up from this driver_import/ folder). If the game freezes, close it\n"
        "and send that log.txt - it records what each thread was doing when frames\n"
        "stopped. The previous run is kept as log_prev.txt.\n"
        "\n"
        "GFXReconstruct capture (for sending a GPU trace to driver developers):\n"
        "create an empty file named gfxrecon_capture.txt in THIS folder. On the\n"
        "next launch the game records a Vulkan trace to ../gfxr/unleashed_capture.gfxr\n"
        "(the PARENT folder's gfxr/ subfolder). Keep the session SHORT (reach the\n"
        "spot that shows the bug, then close the game) - the file grows the whole\n"
        "time and can get large. Send that .gfxr, then DELETE gfxrecon_capture.txt\n"
        "to return to normal (capturing slows the game down a lot).\n"
        "\n"
        "NON-ADRENO DEVICES (Mali / PowerVR / Xclipse): the bundled driver is\n"
        "Adreno-only, so the game automatically uses the system Vulkan driver\n"
        "instead. BC textures are transcoded to ETC2/EAC (or decompressed) on\n"
        "the CPU when the driver lacks them; create force_no_bc.txt in THIS\n"
        "folder to test that path on hardware with native BC support. On Mali\n"
        "you can experiment with importing a PanVK build (a plain .so) here.\n"
        "On Samsung Xclipse, drop an ExynosTools package zip from\n"
        "https://github.com/WearyConcern1165/ExynosTools into this folder to\n"
        "use its compatibility layer instead of the plain system driver.\n");

    ScanDriverImportDir(importDir, turnipDir);

    // Twin folder under Android/media: on-device file managers can browse it on
    // Android 11+ (unlike Android/data), so phone-only users can drop drivers there.
    const std::filesystem::path &mediaDir = os::android::GetExternalMediaDir();
    if (!mediaDir.empty())
    {
        std::filesystem::path mediaImportDir = mediaDir / "driver_import";
        std::filesystem::create_directories(mediaImportDir, ec);
        WriteTextFile(mediaImportDir / "readme.txt",
            "Optional: drop a Vulkan driver here as a plain .so file or as a whole\n"
            "driver-package .zip (AdrenoTools/ExynosTools format). This folder is\n"
            "scanned on launch exactly like Android/data/<app>/files/driver_import/;\n"
            "processed files move to the installed/ subfolder. See the readme.txt\n"
            "there for TU_DEBUG and capture options. The log file (log.txt) is\n"
            "available through the launcher's log button.\n");
        ScanDriverImportDir(mediaImportDir, turnipDir);
    }
}

// Provision the app-owned bundled-driver slots. A different imported driver remains selected
// through driver_name.txt, but both bundled slots follow APK updates byte-for-byte.
static void InstallBundledDriverAsset(const std::filesystem::path &turnipDir,
    const char *driverName, const char *assetName)
{
    std::filesystem::path driverPath = turnipDir / driverName;

    std::vector<uint8_t> driver;
    if (!ReadAssetFile(assetName, driver))
    {
        // APK built without the bundled driver - nothing to provision.
        return;
    }

    LOGF("Bundled Vulkan driver asset {}: {} bytes, fingerprint {:016x}.",
        driverName, driver.size(), DriverFingerprint(driver));

    std::vector<uint8_t> installedDriver;
    const bool alreadyInstalled = ReadWholeFile(driverPath, installedDriver) && installedDriver == driver;
    if (!alreadyInstalled)
    {
        if (!WriteWholeFile(driverPath, driver.data(), driver.size()))
        {
            LOG_ERROR("Failed to extract the bundled Vulkan driver to internal storage.");
            return;
        }

        LOGF("Updated bundled Vulkan driver at {}.", driverPath.string());
    }
}

static void InstallBundledDriversIfNeeded(const std::filesystem::path &turnipDir)
{
    InstallBundledDriverAsset(turnipDir, BUNDLED_DRIVER_NAME, BUNDLED_DRIVER_ASSET);
    InstallBundledDriverAsset(turnipDir, VAUZI_710_DRIVER_NAME, VAUZI_710_DRIVER_ASSET);
    InstallBundledDriverAsset(turnipDir, EXPERIMENTAL_A725_DRIVER_NAME, EXPERIMENTAL_A725_DRIVER_ASSET);
    WriteTextFileIfMissing(turnipDir / "driver_name.txt", BUNDLED_DRIVER_NAME);
}

static void EnsureVulkanDriverInstalled(const std::string &turnipDirString)
{
    std::filesystem::path turnipDir(turnipDirString);

    std::error_code ec;
    std::filesystem::create_directories(turnipDir, ec);

    ProcessDriverImportDir(turnipDir);
    InstallBundledDriversIfNeeded(turnipDir);
}

static std::string GetCustomDriverName(const std::string &turnipDir)
{
    std::string path = turnipDir + "driver_name.txt";
    FILE *file = fopen(path.c_str(), "rb");
    if (file == nullptr)
        return DEFAULT_DRIVER_NAME;

    char buffer[256]{};
    size_t bytesRead = fread(buffer, 1, sizeof(buffer) - 1, file);
    fclose(file);

    while (bytesRead > 0 && (buffer[bytesRead - 1] == '\n' || buffer[bytesRead - 1] == '\r' || buffer[bytesRead - 1] == ' '))
        --bytesRead;

    buffer[bytesRead] = '\0';
    return (bytesRead > 0) ? std::string(buffer) : DEFAULT_DRIVER_NAME;
}

static std::string GetImportedDriverName(const std::string &turnipDir)
{
    const std::filesystem::path turnipPath(turnipDir);
    char buffer[256]{};

    FILE *file = fopen((turnipPath / LAST_IMPORTED_DRIVER_FILE).c_str(), "rb");
    if (file != nullptr)
    {
        size_t bytesRead = fread(buffer, 1, sizeof(buffer) - 1, file);
        fclose(file);

        while (bytesRead > 0 &&
            (buffer[bytesRead - 1] == '\n' || buffer[bytesRead - 1] == '\r' || buffer[bytesRead - 1] == ' '))
        {
            --bytesRead;
        }

        buffer[bytesRead] = '\0';
        if (bytesRead > 0)
            return buffer;
    }

    // Migrate installs made before the Driver UI existed. Their selected imported
    // driver only lived in driver_name.txt.
    std::string legacyName = GetCustomDriverName(turnipDir);
    if (legacyName != BUNDLED_DRIVER_NAME && legacyName != VAUZI_710_DRIVER_NAME)
    {
        WriteTextFile(turnipPath / LAST_IMPORTED_DRIVER_FILE, legacyName.c_str());
        return legacyName;
    }

    return {};
}

static bool IsSafeDriverFileName(const std::string &driverName)
{
    const std::filesystem::path path(driverName);
    return !driverName.empty() &&
        path == path.filename() &&
        path.extension() == ".so";
}

static bool ReadTrimmedTextFile(const std::filesystem::path &path, char *buffer, size_t bufferSize)
{
    FILE *file = fopen(path.c_str(), "rb");
    if (file == nullptr)
        return false;

    size_t bytesRead = fread(buffer, 1, bufferSize - 1, file);
    fclose(file);

    while (bytesRead > 0 && (buffer[bytesRead - 1] == '\n' || buffer[bytesRead - 1] == '\r' || buffer[bytesRead - 1] == ' '))
        --bytesRead;

    buffer[bytesRead] = '\0';
    return bytesRead > 0;
}

// Select a deterministic TU_DEBUG default from the restart-required Render Mode option.
// Auto and GMEM both leave sysmem disabled; GMEM is explicit while Auto leaves room for a
// future device policy. An external driver_import/tu_debug.txt (editable over MTP/file
// managers without root) remains the sole diagnostic override and is never rewritten here.
// "flushall" is Mesa's expensive full-flush diagnostic. See
// https://docs.mesa3d.org/drivers/freedreno.html for the full list of TU_DEBUG options.
static void ApplyRenderMode(EAndroidRenderMode renderMode, bool allowDiagnosticOverride,
    const char *driverTuDebugPreset = nullptr)
{
    char buffer[256]{};

    const char *modeName;
    const char *defaultTuDebug;
    if (driverTuDebugPreset != nullptr)
    {
        modeName = "Driver preset";
        defaultTuDebug = driverTuDebugPreset;
    }
    else switch (renderMode)
    {
        case EAndroidRenderMode::GMEM:
            modeName = "GMEM";
            defaultTuDebug = "none";
            break;

        case EAndroidRenderMode::Sysmem:
            modeName = "Sysmem";
            defaultTuDebug = "sysmem";
            break;

        case EAndroidRenderMode::Auto:
        default:
            modeName = "Auto";
            defaultTuDebug = "none";
            break;
    }

    setenv("TU_DEBUG", defaultTuDebug, 1);
    LOGF("Android render mode: {} (default TU_DEBUG=\"{}\").", modeName, defaultTuDebug);

    if (!allowDiagnosticOverride)
    {
        LOG("External TU_DEBUG override is disabled for this driver startup policy.");
        return;
    }

    const std::filesystem::path &externalDir = os::android::GetExternalFilesDir();
    std::filesystem::path externalPath = externalDir / "driver_import" / "tu_debug.txt";

    if (externalDir.empty() || !ReadTrimmedTextFile(externalPath, buffer, sizeof(buffer)))
        return;

    setenv("TU_DEBUG", buffer, 1);
    LOGF("Applied diagnostic TU_DEBUG override from {}: \"{}\" (overrides Render Mode {}).",
        externalPath.string(), buffer, modeName);
}

// Mesa feature flags via FD_DEV_FEATURES. Xiaomi HyperOS 3 ships a display stack whose
// composer misreads Turnip's UBWC layout, producing gameplay-impairing glitches; Mesa's
// enable_tp_ubwc_flag_hint feature fixes the interop (issue #51), so it is applied
// automatically on HyperOS 3+. A driver_import/fd_dev_features.txt overrides the value
// verbatim for experiments on other devices (delete the file to return to automatic).
static void ApplyFdDevFeatures()
{
    char buffer[256]{};

    const std::filesystem::path &externalDir = os::android::GetExternalFilesDir();
    if (!externalDir.empty() &&
        ReadTrimmedTextFile(externalDir / "driver_import" / "fd_dev_features.txt", buffer, sizeof(buffer)))
    {
        setenv("FD_DEV_FEATURES", buffer, 1);
        LOGF("Applied FD_DEV_FEATURES override from fd_dev_features.txt: \"{}\".", buffer);
        return;
    }

    // HyperOS exposes its version through ro.mi.os.version.name (e.g. "OS3.0"); MIUI
    // devices and other vendors don't define it.
    char osVersion[PROP_VALUE_MAX]{};
    __system_property_get("ro.mi.os.version.name", osVersion);
    if (osVersion[0] == '\0')
        return;

    const char *digits = osVersion;
    while (*digits != '\0' && (*digits < '0' || *digits > '9'))
        digits++;

    if (atoi(digits) >= 3)
    {
        setenv("FD_DEV_FEATURES", "enable_tp_ubwc_flag_hint=1", 1);
        LOGF("HyperOS {} detected: FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1 (UBWC interop fix).",
            osVersion);
    }
}

// Optional: if a "vk_layer_settings.txt" file is pushed alongside the driver, it's pointed to via
// VK_LAYER_SETTINGS_PATH so VK_LAYER_KHRONOS_validation picks up settings from it (e.g. enabling
// Synchronization Validation) without rebuilding the APK. See
// https://github.com/KhronosGroup/Vulkan-ValidationLayers/blob/main/docs/syncval_usage.md
static void ApplyLayerSettingsOverride(const std::string &turnipDir)
{
    std::string path = turnipDir + "vk_layer_settings.txt";

    struct stat buf {};
    if (stat(path.c_str(), &buf) != 0)
        return;

    setenv("VK_LAYER_SETTINGS_PATH", path.c_str(), 1);

    // Legacy/redundant path: older Khronos validation layer builds read validation features
    // directly from VK_LAYER_ENABLES instead of (or in addition to) the settings file mechanism.
    // Set both so this works regardless of which one this particular layer build honors.
    setenv("VK_LAYER_ENABLES", "VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT", 1);

    LOGF("Applied VK_LAYER_SETTINGS_PATH override: \"{}\"", path);
}

// Optional GFXReconstruct capture. If the marker file "gfxrecon_capture.txt" is present in the
// external driver_import/ folder, arm the bundled capture layer (enabled + configured in
// plume_vulkan.cpp via VK_EXT_layer_settings) by setting env vars it reads at instance creation.
// The trace is written to <external>/gfxr/unleashed_capture.gfxr, which the tester copies off over
// MTP. Off unless the marker is present: capturing adds heavy overhead and writes a large file, so
// this is strictly a diagnostic (e.g. sending a Turnip repro to a Mesa maintainer).
static void ApplyGfxreconstructCapture()
{
    const std::filesystem::path &externalDir = os::android::GetExternalFilesDir();
    if (externalDir.empty())
        return;

    std::error_code ec;
    std::filesystem::path marker = externalDir / "driver_import" / "gfxrecon_capture.txt";
    if (!std::filesystem::exists(marker, ec))
        return;

    std::filesystem::path captureDir = externalDir / "gfxr";
    std::filesystem::create_directories(captureDir, ec);

    std::filesystem::path captureFile = captureDir / "unleashed_capture.gfxr";
    setenv("UNLEASHED_GFXRECON_CAPTURE", "1", 1);
    setenv("UNLEASHED_GFXRECON_CAPTURE_FILE", captureFile.string().c_str(), 1);

    LOGF("GFXReconstruct capture armed (marker present). Trace: {}", captureFile.string());
}

static std::string GetNativeLibraryDir()
{
    JNIEnv *env = static_cast<JNIEnv *>(SDL_AndroidGetJNIEnv());
    jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
    if (env == nullptr || activity == nullptr)
        return {};

    jclass activityClass = env->GetObjectClass(activity);
    jmethodID getApplicationInfoMethod = env->GetMethodID(activityClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    jobject applicationInfo = env->CallObjectMethod(activity, getApplicationInfoMethod);
    jclass applicationInfoClass = env->GetObjectClass(applicationInfo);
    jfieldID nativeLibraryDirField = env->GetFieldID(applicationInfoClass, "nativeLibraryDir", "Ljava/lang/String;");
    jstring nativeLibraryDirString = static_cast<jstring>(env->GetObjectField(applicationInfo, nativeLibraryDirField));

    const char *chars = env->GetStringUTFChars(nativeLibraryDirString, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(nativeLibraryDirString, chars);

    env->DeleteLocalRef(nativeLibraryDirString);
    env->DeleteLocalRef(applicationInfoClass);
    env->DeleteLocalRef(applicationInfo);
    env->DeleteLocalRef(activityClass);

    if (!result.empty() && result.back() != '/')
        result.push_back('/');

    return result;
}

void *AndroidGetCustomVulkanLoader()
{
    // Arm capture first: it is independent of which Vulkan driver we end up loading (it works even
    // on the stock driver path where this function returns nullptr below).
    ApplyGfxreconstructCapture();

    // Applies to every driver path (custom and system) - the HyperOS UBWC interop
    // glitch is in the display stack, not in a particular driver build.
    ApplyFdDevFeatures();

    PrepareVulkanStartup();

    // Recovery bypasses custom-driver provisioning/import processing as well as dlopen. This
    // keeps the one-time fallback limited to the platform loader and deterministic Auto mode.
    if (g_forceSafeVulkanFallback)
    {
        ApplyRenderMode(EAndroidRenderMode::Auto, false);
        LOGF_WARNING("Android Vulkan driver mode: System (boot recovery override; configured mode remains {}).",
            VulkanDriverName(Config::VulkanDriver.Value));
        return nullptr;
    }

    std::string turnipDir = GetTurnipDir();
    if (turnipDir.empty())
    {
        LOG_ERROR("Internal storage path unavailable, cannot set up the custom Vulkan driver.");
        return nullptr;
    }

    EnsureVulkanDriverInstalled(turnipDir);

    std::string driverName;
    switch (g_runtimeVulkanDriver)
    {
        case EAndroidVulkanDriver::System:
            LOG("Android Vulkan driver mode: System.");
            return nullptr;

        case EAndroidVulkanDriver::Bundled:
            driverName = BUNDLED_DRIVER_NAME;
            LOG("Android Vulkan driver mode: Bundled.");
            break;

        case EAndroidVulkanDriver::Vauzi710:
            driverName = VAUZI_710_DRIVER_NAME;
            LOG("Android Vulkan driver mode: Adreno 710 (Vauzi v2.7).");
            break;

        case EAndroidVulkanDriver::ExperimentalA725:
            driverName = EXPERIMENTAL_A725_DRIVER_NAME;
            LOG("Android Vulkan driver mode: A725 Performance (experimental CCU COLOR pair).");
            break;

        case EAndroidVulkanDriver::ExperimentalA8xx:
            driverName = EXPERIMENTAL_A8XX_DRIVER_NAME;
            LOG("Android Vulkan driver mode: Adreno 8xx Experimental (CCU COLOR pair).");
            break;

        case EAndroidVulkanDriver::Imported:
            driverName = GetImportedDriverName(turnipDir);
            LOG("Android Vulkan driver mode: Imported.");
            break;

        case EAndroidVulkanDriver::Auto:
        default:
            // Preserve the pre-UI behavior: a successfully imported driver remains
            // selected; fresh installs use the bundled asset.
            driverName = GetCustomDriverName(turnipDir);
            LOG("Android Vulkan driver mode: Auto.");
            break;
    }

    std::string gpuDescription;
    const EAndroidGpuFamily gpuFamily = DetectGpuFamily(gpuDescription);
    if (gpuFamily != EAndroidGpuFamily::Adreno && gpuFamily != EAndroidGpuFamily::Unknown)
    {
        // Auto only skips the *bundled* Turnip: an explicitly imported driver selected on a
        // non-Adreno device (e.g. a PanVK build on Mali) stays honored, as do the explicit
        // Bundled/Imported modes. Returning before ArmCustomVulkanStartup keeps this launch
        // out of the crash-recovery cycle entirely.
        if (g_runtimeVulkanDriver == EAndroidVulkanDriver::Auto && driverName == BUNDLED_DRIVER_NAME)
        {
            LOGF_WARNING("Detected non-Adreno GPU (\"{}\"): the bundled Turnip driver is Adreno-only, using the system Vulkan driver.",
                gpuDescription);

            if (gpuFamily == EAndroidGpuFamily::Xclipse)
            {
                LOG("Samsung Xclipse detected. A compatibility package zip from "
                    "https://github.com/WearyConcern1165/ExynosTools can be dropped into driver_import/ to use it instead of the system driver.");
            }

            return nullptr;
        }

        LOGF_WARNING("Custom Vulkan driver \"{}\" is selected on a non-Adreno GPU (\"{}\"); attempting to load it anyway.",
            driverName, gpuDescription);
    }

    if (!IsSafeDriverFileName(driverName))
    {
        LOGF_ERROR("Invalid or unavailable Vulkan driver selection: \"{}\". Falling back to the system driver.", driverName);
        return nullptr;
    }

    struct stat buf {};
    std::string driverPath = turnipDir + driverName;
    if (stat(driverPath.c_str(), &buf) != 0)
    {
        // Auto can recover from a removed imported driver without making the app
        // unbootable. Explicit Imported remains explicit and falls back to System.
        if (g_runtimeVulkanDriver == EAndroidVulkanDriver::Auto && driverName != BUNDLED_DRIVER_NAME)
        {
            driverName = BUNDLED_DRIVER_NAME;
            driverPath = turnipDir + driverName;
        }

        if (stat(driverPath.c_str(), &buf) != 0)
        {
            LOGF_ERROR("Selected Vulkan driver is missing: {}. Falling back to the system driver.", driverPath);
            return nullptr;
        }
    }

    std::string nativeLibraryDir = GetNativeLibraryDir();
    if (nativeLibraryDir.empty())
    {
        LOG_ERROR("Failed to query nativeLibraryDir via JNI, cannot load custom Vulkan driver.");
        return nullptr;
    }

    if (!ArmCustomVulkanStartup())
    {
        ApplyRenderMode(EAndroidRenderMode::Auto, false);
        return nullptr;
    }

    EAndroidRenderMode effectiveRenderMode = g_runtimeRenderMode;
    const char *driverTuDebugPreset = nullptr;
    if (effectiveRenderMode == EAndroidRenderMode::Auto &&
        g_runtimeVulkanDriver == EAndroidVulkanDriver::Vauzi710)
    {
        effectiveRenderMode = EAndroidRenderMode::Sysmem;
        LOG("Adreno 710 Vauzi driver: Auto render mode selects Sysmem as recommended by the driver author.");
    }

    if (g_runtimeVulkanDriver == EAndroidVulkanDriver::ExperimentalA725)
    {
        effectiveRenderMode = EAndroidRenderMode::Sysmem;
        driverTuDebugPreset = "sysmem,nobin";
        LOG("A725 Performance experimental driver: forcing TU_DEBUG=sysmem,nobin.");
    }

    if (g_runtimeVulkanDriver == EAndroidVulkanDriver::ExperimentalA8xx)
    {
        effectiveRenderMode = EAndroidRenderMode::Sysmem;
        driverTuDebugPreset = "sysmem,flushall";
        LOG("Adreno 8xx Experimental driver: forcing TU_DEBUG=sysmem,flushall.");
    }

    ApplyRenderMode(effectiveRenderMode, driverTuDebugPreset == nullptr, driverTuDebugPreset);
    ApplyLayerSettingsOverride(turnipDir);

    void *libVulkan = adrenotools_open_libvulkan(
        RTLD_NOW | RTLD_LOCAL,
        ADRENOTOOLS_DRIVER_CUSTOM,
        nullptr, // tmpLibDir: unused on API >= 29 (memfd is used instead)
        nativeLibraryDir.c_str(),
        turnipDir.c_str(),
        driverName.c_str(),
        nullptr,
        nullptr);

    if (libVulkan == nullptr)
    {
        LOG_ERROR("adrenotools_open_libvulkan failed, falling back to the default Vulkan driver.");
        return nullptr;
    }

    void *getInstanceProcAddr = dlsym(libVulkan, "vkGetInstanceProcAddr");
    if (getInstanceProcAddr == nullptr)
    {
        LOG_ERROR("Custom Vulkan driver loaded but vkGetInstanceProcAddr symbol is missing.");
        return nullptr;
    }

    LOGF("Successfully loaded custom Vulkan driver ({}) via libadrenotools.", driverName);
    return getInstanceProcAddr;
}

void AndroidMarkVulkanStartupSuccessful()
{
    if (!g_vulkanStartupPrepared || g_vulkanStartupSuccessReported)
        return;

    if (!g_vulkanStartupStateActive)
    {
        g_vulkanStartupSuccessReported = true;
        return;
    }

    if (!RemoveVulkanStartupState())
    {
        LOG_ERROR("Vulkan boot recovery: device + swapchain are usable, but the startup marker could not be cleared.");
        return;
    }

    g_vulkanStartupStateActive = false;
    g_vulkanStartupSuccessReported = true;
    LOG("Vulkan boot recovery: device + usable swapchain created successfully; startup marker cleared.");
}
