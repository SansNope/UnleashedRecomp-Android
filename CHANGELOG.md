# Changelog

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
