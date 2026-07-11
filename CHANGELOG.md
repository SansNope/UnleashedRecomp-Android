# Changelog

## Unreleased

### Fixes

- The profiler overlay no longer opens on every launch (issue #46). It is off by default, can be enabled with the new "Show profiler overlay" launcher checkbox, and closing it in-game with its X button is remembered across launches.
- The mod manager no longer creates a second `mods` folder in the transfer root (issue #42). Only `<game root>/mods` is created; an empty leftover transfer folder from 0.3.0 is removed automatically, and mods already placed there are still detected.
- Worked around the "ring crash" from issue #27 (100% crash on Snapdragon 8 Gen 2 handhelds when collecting a ring, independent of driver, render mode and frame rate): the game's animation node evaluator dereferences a child node data pointer that is 0 or -1 on those devices. The evaluation is now skipped for such nodes and the state is logged instead of crashing; the root cause in game data/state is still under investigation.
- Fixed a crash when applying the window-size option while the app is backgrounded: on Android the display-mode list is empty whenever the native window is detached, and the callback indexed entry `-1` of the empty list (`SIGSEGV` at `fault_addr=0xffffffffffffec` on the main thread, seen on AYN Thor in issue #27). The callback now skips the update until display modes are available again.
- Log-file version banner now matches the released APK version; 0.3.0 builds still reported `1.5.0-roadmap-v34` in `log.txt`, which made log triage misleading.

## 0.3.0 (2026-07-11)

### Unified Android launcher

- Replaced the direct SDL home-screen entry with a lightweight launcher that validates the game installation before startup and provides driver/render-mode, touch-control, FPS, intro-skip, Vulkan validation and GFXReconstruct settings.
- Added launcher actions for importing Vulkan `.so`/`.zip` packages, opening game/transfer folders, viewing logs, managing mods and starting the touch-layout editor.
- Moved the mod manager behind the unified launcher so the app exposes a single home-screen icon.
- Added shared Java storage-path handling so the launcher, mod manager and DocumentsProvider select the same active game root as native code.
- Changed the Android application id to `com.sega.sonicunr`, advanced the APK to version code 10 / `1.5.0-roadmap-v35`, and added English and Russian launcher resources.

### Storage and touch controls

- Added `Android/media/com.sega.sonicunr` as an on-device-file-manager-friendly fallback for game files and driver imports while preserving populated internal and `Android/data` installations.
- Resolve the internal files directory from SDL at runtime instead of relying only on a compiled package path, and create `.nomedia` for media-storage game assets.
- Replaced the permanent in-game touch-control EDIT button with a one-shot launcher action; the editor remains accessible even when normal touch controls are disabled.

### Graphics drivers

- Added Vauzi-17/710 v2.7 as a separate bundled **Adreno 710 (Vauzi)** driver choice. With Render Mode Auto it uses the author's recommended Sysmem path; the existing universal Turnip remains the default for compatibility.
- Provision and update both bundled driver assets independently without replacing an imported selection.
- Documented Sysmem as an opt-in diagnostic/workaround for Adreno 6xx instead of forcing it globally.
- Documented why ExynosTools needs explicit Vulkan-layer integration before it can be treated as a fully supported built-in Samsung driver, and why PanVK requires a panfrost/panthor-capable Android kernel rather than only an APK-side Mesa build.

### Reliability and build automation

- Fixed swapchain recovery after a failed recreation by destroying the retired old swapchain before retrying, avoiding repeated `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR` failures on affected Android drivers.
- Added a GitHub Actions ARM64 APK workflow with private game-file checkout, host code-generation tools, NDK/vcpkg cross-compilation, ccache, optional release signing, artifact upload and release attachment.
- Added CI setup documentation and ignored all game-derived PPC generator outputs so they cannot be committed accidentally.

## 1.5.0 (2026-07-11)

### Experimental Mali GPU support

- The game now runs on recent Mali GPUs through the stock system Vulkan driver — no custom driver needed. Requires a Valhall-generation GPU with a Vulkan 1.3 driver (e.g. Mali-G610/G615/G710/G715/G720). Confirmed working on a Dimensity 8300 Ultra (Mali-G615). Older Mali generations (Bifrost and earlier) cannot work.
- Non-Adreno devices (Mali / PowerVR / Xclipse) now automatically skip the bundled Adreno driver in Auto mode. Previously the first launch crashed into boot recovery and only the second launch reached the system driver.
- Vulkan device requirements reworked for portability: features promoted to core Vulkan 1.2 are enabled through the core path on 1.2+ drivers (stock Mali/PowerVR blobs don't have to list the legacy extension strings), robustness2 is optional, and MIRROR_ONCE samplers degrade gracefully where unsupported.

### BC texture support on GPUs without BC formats

- Game textures (BC1–BC5, BC7) are transcoded to ETC2/EAC on the CPU at load time on devices whose driver lacks BC support — with the same GPU memory footprint as the originals. sRGB content keeps correct sRGB sampling via the ETC2 sRGB formats.
- If ETC2 is unavailable too, textures fall back to plain RGBA decoding.
- The profiler overlay shows which path is active ("BC Textures: Supported / ETC2 Transcode / CPU Decode"), and a `force_no_bc.txt` file in `driver_import/` forces the fallback for testing.

### Driver package zip import (ExynosTools, AdrenoTools)

- `driver_import/` now accepts whole driver-package `.zip` files in addition to plain `.so` binaries: the package's `meta.json` selects the entry library, and all bundled files are extracted alongside it. This covers [ExynosTools](https://github.com/WearyConcern1165/ExynosTools) packages for Samsung Xclipse and AdrenoToolsDrivers releases (e.g. Adreno 8xx builds) without unzipping anything manually.

### Better crash and hang diagnostics

- `log.txt` now begins with a device summary: model, SoC, Android version, GPU HAL, ABI and RAM.
- The GPU name, vendor and driver version are logged as soon as the rendering device is created.
- Fatal crashes (SIGSEGV, SIGABRT and friends) are now recorded in `log.txt` with the signal, fault address and crash location resolved to module+offset — a bare log file is enough to identify where a crash happened, no adb or root required.

### Fixes

- Restored Adreno 710/720/722 support: the bundled driver asset had silently reverted to an older build without those GPUs ("Unable to find devices that support Vulkan" on Adreno 710).
- Bundled driver updates shipped in APK updates now actually reach existing installs; previously the extracted copy permanently shadowed the packaged asset and updates were never applied.
- Bindless descriptor sets are clamped to the device's limits instead of assuming 65536 textures, for drivers with small descriptor limits (PowerVR-class).
