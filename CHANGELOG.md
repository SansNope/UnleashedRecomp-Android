# Changelog

## 0.5.3 (2026-07-22)

### Android audio stability and device presets

- Kept the reworked clocked-producer audio path that isolates the game audio clock from Android output stalls and preserves buffered audio across route changes.
- Improved recovery when the app returns from the background or the active output changes, including Bluetooth and device-specific routed outputs.
- Kept the **AYN** preset for consoles that start without sound and the opt-in **POCO F8 Ultra (low latency)** preset.
- The POCO profile requests AAudio's exclusive low-latency path and uses a smaller FIFO cushion. Default and AYN profiles retain the more conservative shared path.

### Vulkan driver profiles

- Added **Adreno 8xx Experimental (sysmem+flushall)** as a separate, non-default launcher choice.
- The new profile uses the bundled whitebelyash Mesa 26.2 gen8-capable driver and forces the exact `TU_DEBUG=sysmem,flushall` workaround for artifact-prone Adreno 8xx devices.
- The profile locks Render Mode to Sysmem and ignores external `tu_debug.txt` overrides so the selected workaround is deterministic.
- No duplicate driver binary is stored in the APK: the Adreno 8xx and A725 profiles share the same driver asset but apply different runtime presets.
- **A725 Performance** remains available with its existing forced `TU_DEBUG=sysmem,nobin` preset. Auto, the universal bundled driver and the Adreno 710 Vauzi profile are unchanged.

### Build metadata

- Advanced the Android package to version code 17 / version name `0.5.3`.
- Updated the persistent diagnostic log banner to identify the build as `0.5.3-release`.

## 0.5.2 (2026-07-13)

Version 0.5.2 replaces the withdrawn 0.5.1 APK. It contains every user-facing change from 0.5.1, removes performance instrumentation that was unintentionally left enabled there, and adds the features below.

### Android audio presets

- Added an experimental **POCO F8 Ultra (low latency)** preset. It reduces the internal FIFO cushion from 16 to 8 guest audio frames (about 85 ms to 43 ms) and requests AAudio's exclusive low-latency path to avoid HyperOS selecting an approximately 80 ms shared hardware burst.
- The existing **Default** and **AYN** behavior is unchanged. The POCO-specific path is opt-in because it may crackle, stall or lose sound on devices whose AAudio/MMAP implementation is unreliable.
- The selected preset, resolved FIFO parameters, AAudio performance/sharing modes, hardware burst, active buffer and platform xrun count are written to the Android log.

### Mod settings (issue #75)

- Mods that declare HMM's `ConfigSchemaFile` now have their own settings button in the Android mod manager.
- The editor supports HMM boolean, string, integer, floating-point and custom-enum fields, including descriptions, defaults and numeric limits.
- Values are written atomically to the schema's INI file while unrelated sections, keys and comments are preserved. Schema and target paths are restricted to the mod's own directory.

### In-app updates (issue #83)

- Added automatic periodic checks and a manual **Check for updates** action in the launcher.
- The app displays the release notes, downloads the official release APK with progress, verifies GitHub's SHA-256 digest when present, and rejects APKs whose package name or signing certificate differs from the installed app.
- Android's system package installer remains responsible for the actual update; game files, saves, mods and settings are not replaced.

### Install from Xbox 360 packages (PR #78)

- Added **ISO / update / DLC packages** as a game-install source. Select the base game, title update and optional DLC files together; the launcher stages them and the native installer verifies them on first launch.
- Failed installs are rolled back and the staged source files are retained for retry. Successful installs remove the staging directory.
- Based on pull request #78 by GdGohan, with Android launcher integration and failure handling added for this release.

### Adreno 725 experimental performance driver

- Added **A725 Performance (experimental; sysmem+nobin)** as an explicit, non-default driver choice.
- Selecting it forces and locks its required Sysmem render path and exact `TU_DEBUG=sysmem,nobin` preset, ignoring stale manual debug overrides.
- This build is intended for Adreno 725 testing only. Adreno 732 support is not enabled yet; Auto and the bundled universal driver remain unchanged.

### Performance and diagnostics fix

- Removed the accidentally enabled always-on GPU `PERF` instrumentation from the 0.5.1 build. This instrumentation added per-frame timestamp queries and logging overhead and was not meant for a public release.
- Corrected the log banner/build ID to `0.5.2-release` so tester logs identify the APK unambiguously.

### Changes carried forward from 0.5.1

#### Achievements

- Fixed achievement-unlock notifications not appearing on Android (issue #65). They previously failed because the game thread was identified incorrectly.

#### Touch controls

- Added an optional 8-way D-pad for normal gameplay (issue #68).
- The D-pad now also appears on the results and level-up screens.
- During cutscenes and the attract movie, controls collapse to a single **SKIP** button.
- Moved **SKIP** to the top-right so it does not cover achievement notifications or subtitles.
- Increased the default spacing between the A/B/X/Y buttons.

#### In-game settings

- Moved on-screen controls, touch-camera and left-stick settings from the launcher into the in-game **Input** menu.
- Moved the FPS and profiler toggles into the **Video** menu.
- The launcher's Graphics driver, Mods and Debug sections are collapsed by default.

#### Mods and saves

- Added a **Codes** section to the mod manager with toggles for built-in game patches, including Homing Attack on Jump, Save Score at Checkpoints and Skip Intro Logos.
- Added an **Open saves folder** shortcut and a dedicated saves root in the system file picker.

#### Graphics and localisation

- Added an experimental **Force native BC textures** option for devices whose native BC support is incorrectly detected as unavailable (issue #72). It must not be enabled on Adreno/Turnip devices, where it corrupts textures.
- Completed launcher and app UI translations for all supported languages.

## 0.5.0 (2026-07-13)

### Install the game and mods straight from the app

- **Install game files (.zip / folder)** in the launcher's Game files card: pick your game dump as a ZIP archive or a folder through the system file picker and the app copies everything into place in the background, with progress and cancel. The dump's layout is detected automatically no matter how deeply the folders are nested in the archive.
- **Raw dumps are now bootable without a PC**: if the `patched` folder produced by the desktop installer is missing, the app builds the patched executable itself from `game` + `update` on first launch.
- **Install a mod (.zip / folder)** in the Mods card: mod archives are unpacked into the right place with every contained mod detected by its `mod.ini` — multi-mod archives install in one go.

### Smarter touch controls

- **Menus show a D-pad.** On the title screen, in menus, on the world map and in the pause menu, the left analog stick turns into an 8-way D-pad — navigating game menus no longer fights the analog deadzone — and the touch camera is released so it cannot swallow taps.
- **Cutscenes show a single SKIP button.** During in-engine cutscenes every control collapses into one wide SKIP button; the full layout returns the moment gameplay resumes.

### New graphics options for low-end devices

- **Texture Quality** (Video: Full / Half / Quarter): serves every game texture from further down its own mip chain at load time. Half/Quarter cut texture memory and GPU sampling bandwidth roughly 4x/16x — what low-end texture-pack mods achieve with hundreds of megabytes of repacked archives, applied to the base game, DLC, mods and cutscenes alike with zero extra files. UI and fonts keep full resolution.
- **Planar Reflections** toggle (Video): turning it off skips the entire reflection scene render pass on stages with reflective surfaces (towns, water) — those stages effectively stop rendering the scene twice.
- **Shadow Resolution 256**: a new lowest step below 512 for the weakest GPUs.
- The Video menu no longer lists desktop-only options that did nothing on a phone (Window Size, Monitor, Fullscreen, VSync), and Input no longer shows Allow Background Input.

### App translations

- The launcher, mod manager, installer and file-provider UI are now available in Japanese, German, French, Spanish and Italian — matching the game's supported languages — plus Portuguese for the Brazilian community. The app follows the system (or per-app) language setting.

### Fixes

- Large mods no longer hide themselves and other mods from the mod manager (issue #58). The scanner used to spend its file budget inside a big mod's content folders before ever reading its `mod.ini`; Low End Mod (2494 files) reliably triggered this. Mods now cost a handful of scanned files regardless of size.
- Restored the original Sonic artwork as the launcher icon, now properly filling the adaptive-icon canvas on modern launchers (no more small square in a white circle, no more black plate) with a matching monochrome themed icon.

## 0.4.0 (2026-07-12)

### Touch camera control (issue #50)

- The in-game camera can now be controlled from the touch screen. Two modes are available in the launcher's Controls card: **Swipe on screen** (default — drag anywhere on the free right half of the screen, no extra widget) and **Right stick** (a dedicated virtual stick, arrangeable in the layout editor like every other control), plus **Off**.
- Fingers that start on buttons or sticks never move the camera, and the camera finger never presses buttons.

### Fixes

- Fixed gameplay-impairing display glitches on Xiaomi HyperOS 3 (issue #51): Mesa's `enable_tp_ubwc_flag_hint=1` feature flag is now applied automatically when HyperOS 3+ is detected, and a `driver_import/fd_dev_features.txt` file can override `FD_DEV_FEATURES` for experiments on other devices.
- The profiler overlay no longer opens on every launch (issue #46). It is off by default, can be enabled with the new "Show profiler overlay" launcher checkbox, and closing it in-game with its X button is remembered across launches.
- The mod manager no longer creates a second `mods` folder in the transfer root (issue #42). Only `<game root>/mods` is created; an empty leftover transfer folder from 0.3.0 is removed automatically, and mods already placed there are still detected.
- Fixed a crash when applying the window-size option while the app is backgrounded: on Android the display-mode list is empty whenever the native window is detached, and the callback indexed entry `-1` of the empty list (`SIGSEGV` at `fault_addr=0xffffffffffffec` on the main thread, seen on AYN Thor in issue #27). The callback now skips the update until display modes are available again.
- Log-file version banner now matches the released APK version; 0.3.0 builds still reported `1.5.0-roadmap-v34` in `log.txt`, which made log triage misleading.

### Stability hardening (issue #27 — mitigation, not a fix yet)

The "ring crash" on Snapdragon 8 Gen 2 handhelds (AYN Odin 2 / Thor) is still under active investigation; its root cause — corruption of animation-node and heap data — is not yet fixed. This release makes the crash cascade less fatal and much more diagnosable:

- The animation-node evaluator skips nodes with invalid data pointers (and logs them) instead of crashing.
- The guest null page is readable/writable on Android, so reads through the broken 0/-1 pointers return zeros; testers confirmed no visual impact.
- Indirect calls whose target is outside the recompiled code or unmapped are skipped and logged instead of jumping to a wild address.
- Fatal-signal reports, device info and GPU identity in `log.txt` continue to symbolize crash locations offline. Desktop behaviour is unchanged by all of the above.

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
