# UnleashedRecomp — Android (Adreno) port

An **unofficial, work‑in‑progress Android port** of
[UnleashedRecomp](https://github.com/hedge-dev/UnleashedRecomp) — the static
recompilation of the Xbox 360 version of *Sonic Unleashed*. This repository is a
fork of the upstream project with an Android (ARM64) target added, using a
custom **Mesa Turnip** Vulkan driver loaded via **libadrenotools**.

> Licensed under **GPL‑3.0**, same as upstream. This is a personal project shared
> so others can build on it. Not affiliated with SEGA, hedge‑dev, or the Mesa
> project.

---

## ⚠️ No game files are included (and you must not add them to a public fork)

This repo contains **no copyrighted game content**. Excluded on purpose:

- `UnleashedRecompLib/private/` — the raw dump (`default.xex`, `default.xexp`,
  `shader.ar`, …). **You provide your own legal dump here.**
- `UnleashedRecompLib/ppc/` — the C++ that `XenonRecomp` *generates* from your
  XEX (`ppc_recomp.*.cpp`, `ppc_func_mapping.cpp`). It is derived from the game
  binary and is produced locally by the build; it is git‑ignored upstream and
  here too.

You need a legal dump of **Sonic Unleashed (Xbox 360)** — a matching
`default.xex` + `default.xexp` pair (correct region / Title Update) and
`shader.ar`. Put them in `UnleashedRecompLib/private/` before building.

---

## Status (as of this snapshot)

Playable on several Adreno devices via Turnip. Reaches the title screen and in‑
game, roughly 40–60 FPS depending on GPU / resolution scale.

| Device | SoC | Turnip family | State |
|---|---|---|---|
| Adreno 725 | SD 7+ Gen 2 | a7xx **gen1** | Main dev target. Playable. Needs per‑draw `CP_WAIT_FOR_ME`. |
| Adreno 750 | SD 8 Gen 3 | a7xx **gen3** | Playable **with MSAA off** (see below). |
| Adreno 732 | SD 7+ Gen 3 | binned a735 / gen2 | Brought up via a chip‑id hack; ~60 FPS. |

Other Adreno 6xx/7xx + Android 9+ *should* work (libadrenotools requirement) but
are untested.

---

## Why a custom driver is required (the core problem)

The engine's renderer (**plume**, Vulkan backend) relies on
`VK_KHR_buffer_device_address` and 64‑bit address arithmetic in shaders, which
pulls in the SPIR‑V `Int64` capability (`shaderInt64`).

The **stock Qualcomm Adreno driver** on many SoCs (e.g. the Adreno 720/725 on
SD 7+ Gen 2) reports only **Vulkan 1.1** and **`shaderInt64 = false`**. Almost
every graphics pipeline then fails to compile (`VK_ERROR_UNKNOWN`, internal
Adreno shader‑compiler assertion). This is the original hard blocker.

**Solution:** load **Mesa Turnip** (open‑source Adreno Vulkan driver, Vulkan
1.4, full `shaderInt64` / buffer_device_address) at runtime via
**libadrenotools** — the same technique Android emulators use. The title screen
then renders and the game runs.

> Note for newer flagships: a device whose *stock* driver is already Vulkan 1.3+
> with `shaderInt64` (e.g. Adreno 750) can in principle run on the stock driver
> with no Turnip. This port still ships/uses Turnip because the primary target
> (a725) has no such stock driver, and to keep one code path.

---

## Bundled driver

`android-apk/app/src/main/assets/turnip/vulkan.unleashed26_1_wfm_a732.so`
(also copied to `driver/` in this repo for convenience) is our **source‑built
Mesa 26.1.4 Turnip** with:

- **patch 0001** — an unconditional per‑draw `TU_CMD_FLAG_WAIT_FOR_ME`
  (`CP_WAIT_FOR_ME`) baked into `tu6_emit_flushes()` (no `TU_DEBUG` gate). This
  fixes the a725 "shimmer" (see findings). Because it is compiled in, **`TU_DEBUG`
  must stay `none`** — setting `flushall` on a source build enables Mesa's *real*
  full per‑draw cache flush and tanks the framerate.
- **patch 0004** — adds the Adreno **732** chip id to the FD735 device entry.

Covers a725 / a732 / a750. The app extracts it to internal storage on first
launch and can also import an arbitrary Turnip `.so` dropped into the external
`driver_import/` folder.

The driver is built in CI from a fork of the Turnip build scripts:
**`SansNope/Banners-Turnip`**, branch `unleashed` (a copy of the relevant
scripts is in `turnip-driver-ci/` here). Variants are selected by the `VARIANT`
env var (`wfm`, `wfm-a732`, `clean`, …) and Mesa ref by `MESA_REF`.

---

## Key technical findings (the whole investigation)

### Adreno 725 (gen1) — "shimmer" / transient corruption
Textures & models briefly disintegrate / vanish for ~1 frame; reproduces on the
title screen (rotating Earth). Bisected the `TU_DEBUG=flushall` mask bit‑by‑bit
on device: **`TU_CMD_FLAG_WAIT_FOR_ME` (0x200) alone is necessary and
sufficient.** This is `CP_WAIT_FOR_ME`, a command‑processor front‑end sync — *not*
a cache flush — so **no Vulkan API barrier can fix it** (it lives mid‑render‑pass
between draws). Desktop NVIDIA renders the same command stream cleanly →
Turnip/a7xx‑specific. Fixed by baking WFM per‑draw (patch 0001).

### Adreno 750 (gen3) — different bug, MSAA‑gated
The a725 WFM fix is not sufficient on gen3. Bisection first pointed at a per‑draw
`FD_CCU_CLEAN_COLOR` (color‑cache coherency), but that cost ~40% FPS. **Key
later finding: the corruption is gated by the app's MSAA setting** — 2× MSAA →
corruption, **MSAA off → clean**, on an otherwise identical build. So the gen3
issue looks like a **Turnip MSAA‑path (tile‑resolve / CCU) problem**, and the
CCU flush was only masking it. **a750 recipe = WFM driver + MSAA off.**

### Adreno 732 bring‑up
a732 (SM7675) is absent from Mesa's device table. It shares the "cliffs" kgsl
core with the a735, so it was brought up by adding its (guessed) chip id to the
FD735/gen2 entry (patch 0004). Loaded first try; a full level at ~60 FPS.

### Upstream Mesa bug report
The per‑draw corruption (both GPUs, with the two‑GPU bisection matrix) is filed
upstream: **gitlab.freedesktop.org/mesa/mesa** work item **15792**. A clean
upstream `main` build reproduces it, confirming it is not caused by our patches.
A GFXReconstruct capture workflow exists for sending traces to the maintainers
(see *Diagnostics*).

### Audio
Reworked to a "clocked producer / trivial consumer" model
(`apu/driver/sdl2_driver.cpp`, `APU_PULL_MODEL`): a producer thread runs the
guest once per *elapsed* 5.33 ms slot (fixes a permanent queue‑deficit that
caused crackle on devices consuming in large HAL bursts), a ~64 ms cushion, a
dead‑stream watchdog, and drift correction. Plus SDL AAudio patches
(`thirdparty/SDL/src/audio/aaudio/`): `PERFORMANCE_MODE_NONE` instead of
`LOW_LATENCY` (avoids the fragile MMAP path) and a larger device buffer.
**Invariant:** the engine's audio clock must never depend on platform‑stream
liveness, and guest code must never run on the platform audio thread.

### Android config defaults
Fresh installs default to `ResolutionScale 0.5`, `AntiAliasing None`,
`AnisotropicFiltering 4`, `MotionBlur Off`. Note the low‑end‑GPU detection in
`gpu/video.cpp` (`ApplyLowEndDefaults`) previously forced 2× MSAA on Android —
now overridden to None (this silently caused the a750 artifact saga).

### On‑device diagnostics (this build)
- **`log.txt`** — every log line plus captured `stderr` (where plume/Turnip
  print Vulkan / GPU‑fault messages) is mirrored to
  `Android/data/org.libsdl.app/files/log.txt`, unbuffered, with a **hang
  watchdog**: a background thread dumps every thread's state (`/proc/self/task`)
  to the log if frames stop for >5 s. Useful for freezes on remote devices with
  no adb. Previous run kept as `log_prev.txt`.
- **GFXReconstruct capture** — see *Diagnostics* below.

---

## Layout of the Android‑specific changes

- `UnleashedRecomp/os/android/` — the OS abstraction layer:
  - `logger_android.cpp` — stderr→logcat/file redirect, `log.txt` file sink,
    hang‑watchdog thread.
  - `storage_android.cpp` — internal/external paths, writability probe, data root
    resolution (legacy internal install vs external app storage).
  - `vulkan_driver_android.{h,cpp}` — `AndroidGetCustomVulkanLoader()`: loads the
    Turnip driver via `adrenotools_open_libvulkan` (JNI to get
    `nativeLibraryDir`), first‑launch driver extraction, `driver_import/`
    importer + ELF‑aware WFM byte‑patcher, `TU_DEBUG` override, GFXReconstruct
    arming.
  - `process_android.cpp`, `media_android.cpp`, `user_android.cpp`,
    `version_android.cpp`.
- `thirdparty/plume/plume_vulkan.cpp` — Android bits: custom Vulkan loader via
  volk `volkInitializeCustom`, `BACKBUFFER_FORMAT` = RGBA on Android,
  `VK_EXT_descriptor_indexing` added to optional extensions (needed on <1.2
  drivers), optional validation / GFXReconstruct layer wiring.
- `UnleashedRecomp/gpu/video.cpp` — `#ifdef __ANDROID__` paths (backbuffer
  format, low‑end defaults, profiler overlay force‑on, `Heartbeat()` per frame).
- `UnleashedRecomp/apu/driver/sdl2_driver.cpp` — the audio v2 pull model.
- `UnleashedRecomp/ui/game_window.cpp` — forced landscape via
  `SDL_HINT_ORIENTATIONS`.
- `thirdparty/libadrenotools/` — vendored (builds `adrenotools` static lib + 4
  dlopen'd "hook" shared libs). `thirdparty/SDL/` — patched AAudio backend.
- `android-apk/` — the Gradle project (classic `SDLActivity`), `AndroidManifest`,
  bundled driver asset, `useLegacyPackaging true` (required so the Vulkan loader
  can find app‑bundled layers on disk).
- `turnip-driver-ci/` — the Turnip build scripts / patches used to produce the
  driver.

---

## Building (Windows)

> The whole thing has only been built on Windows so far. You need a Windows box
> for the host tools + Android cross‑compile; a real Android/Adreno device to run.

### Prerequisites
- **Visual Studio 2022 Build Tools** with the C++ workload **and** the *Windows
  11 SDK* individual component (the SDK is **not** installed by the workload
  alone — CMake's compiler check fails without `rc.exe`).
- **Android NDK r29** (16 KB‑aligned output by default — important on Android 15+).
- **JDK 17**, **Android SDK** (platform‑tools/adb, build‑tools).
- **vcpkg** (bootstrapped), **CMake**, **Ninja**.
- ⚠️ **Use a path with NO SPACES** for the checkout. `cmcldeps.exe` (CMake's
  MSVC RC dependency scanner) mishandles spaces in its own path and fails with a
  misleading `CreateProcess: %1 is not a valid Win32 application`. A junction did
  *not* fully fix it — physically use e.g. `E:\UnleashedRecompAndroid`.
- ⚠️ `vcvars64.bat` may silently fail to add the SDK bin dir to PATH on some
  setups. The `build_*.bat` scripts here set `PATH`/`INCLUDE`/`LIB` explicitly
  instead of relying on it — adjust the hard‑coded paths inside them to your
  install.

### Steps
1. **Game files:** put your `default.xex`, `default.xexp`, `shader.ar` in
   `UnleashedRecompLib/private/`.
2. **Submodules / vcpkg:** `update_submodules.bat`; bootstrap vcpkg.
3. **Host tools:** `build_host_tools.bat` then `build_host_tools_target.bat`.
   These build `XenonRecomp` / `XenosRecomp` / `file_to_c` (they must run on the
   PC even for an Android target) and generate `UnleashedRecompLib/ppc/` from
   your XEX and the shader cache from `shader.ar`.
4. **Android configure + build:** `build_android_configure.bat` then
   `build_android_target.bat` → produces
   `out/build/android-arm64/UnleashedRecomp/libmain.so`.
   (If you change any `CMakeLists.txt`, the target script's incremental invoke
   can break — reconfigure with the full env‑complete `cmake` command.)
5. **Hook libs + jniLibs (manual, not folded into the scripts):** the 4
   libadrenotools "hook" libs are dlopen'd by soname, so CMake doesn't pull them
   in. Build them and copy them next to `libmain.so`:
   ```
   cmake --build out/build/android-arm64 --target hook_impl --target main_hook \
         --target file_redirect_hook --target gsl_alloc_hook
   copy out/build/android-arm64/thirdparty/libadrenotools/src/hook/*.so \
        android-apk/app/src/main/jniLibs/arm64-v8a/
   copy out/build/android-arm64/UnleashedRecomp/libmain.so \
        android-apk/app/src/main/jniLibs/arm64-v8a/
   ```
   ⚠️ **`build_apk.bat` does NOT pick up a rebuilt `libmain.so`** — you must copy
   it into `jniLibs/arm64-v8a/` manually every time before packaging.
6. **APK:** `build_apk.bat` (Gradle `assembleDebug`) →
   `android-apk/app/build/outputs/apk/debug/app-debug.apk`.

### Running on device
- Install the APK. On first launch it creates
  `Android/data/org.libsdl.app/files/` with `driver_import/` (+ a `readme.txt`)
  and extracts the bundled Turnip driver internally.
- **Game files** go in `Android/data/org.libsdl.app/files/UnleashedRecomp/` via
  MTP / a file manager (⚠️ *not* `adb push` into `Android/data` — files created
  by the shell there are owned by the shell uid and the app gets EACCES through
  FUSE).
- Optional: drop a different Turnip `.so` into `driver_import/` to swap drivers;
  create `tu_debug.txt` there to set `TU_DEBUG` (keep it `none` for the bundled /
  any source‑built driver).

---

## Diagnostics

### log.txt (freeze/hang capture)
Always on. If the game freezes, close it and grab
`Android/data/org.libsdl.app/files/log.txt`. When frames stop for >5 s the
watchdog appends `HANG DETECTED` + a per‑thread dump (name + scheduler state +
kernel wait channel) so you can tell a GPU/driver hang (render thread blocked in
an ioctl/fence) from a guest‑side deadlock (thread spinning / parked on a futex).

### GFXReconstruct capture (for driver developers)

The app has an opt-in path to record a Vulkan API trace (`.gfxr`) to send to
Turnip / Mesa maintainers. It is **off by default**: no capture layer is
committed here, there is no build dependency on it, and nothing runs unless you
enable it. The app-side hook lives in `thirdparty/plume/plume_vulkan.cpp` and
`os/android/vulkan_driver_android.cpp` (`ApplyGfxreconstructCapture`): on launch,
**if the file `driver_import/gfxrecon_capture.txt` exists**, the app enables the
`VK_LAYER_LUNARG_gfxreconstruct` layer (when the layer `.so` is present) and
passes the capture settings via `VK_EXT_layer_settings`.

So you always need two things: the layer `.so` in the APK, and the marker file on
the device. The catch: that marker-only flow relies on `VK_EXT_layer_settings`,
which the **prebuilt** LunarG layer does NOT honor, and that prebuilt is
4 KB-aligned so Android 16 won't even load it. Pick one of the two paths below.

**Path A — quick capture on a device you control (you have adb).** What was used
to produce the a725 traces; works with the stock prebuilt layer.

1. Download the [LunarG/gfxreconstruct](https://github.com/LunarG/gfxreconstruct)
   Android release and copy `arm64-v8a/libVkLayer_gfxreconstruct.so` into
   `android-apk/app/src/main/jniLibs/arm64-v8a/`, then build the APK.
2. Create the marker `gfxrecon_capture.txt` in `driver_import/` (makes the app
   load the layer and create the `gfxr/` output folder).
3. Configure the layer with system properties (the prebuilt layer ignores the
   app-supplied `VK_EXT_layer_settings`, so it must be told the path this way):
   ```
   adb shell setprop debug.gfxrecon.capture_file /storage/emulated/0/Android/data/org.libsdl.app/files/gfxr/cap.gfxr
   adb shell setprop debug.gfxrecon.capture_compression_type LZ4
   ```
   ⚠️ The property VALUE is capped at **92 bytes** — use a short path/filename.
4. Launch with:
   ```
   adb shell monkey -p org.libsdl.app -c android.intent.category.LAUNCHER 1
   ```
   Starting via `monkey`/`am` **bypasses** Android 16's "app doesn't support
   16 KB pages" dialog that would otherwise block a launcher start (the prebuilt
   layer is 4 KB-aligned).
5. Optional, for a small file — trim instead of a full capture: set
   `debug.gfxrecon.capture_android_trigger false` before launch, then once you
   are at the repro scene toggle it `true` for a few seconds and back to `false`.
6. `adb pull` the `.gfxr` from `.../files/gfxr/`.

**Path B — adb-free capture (e.g. a remote tester with no adb).** Path A's
`setprop` and `monkey` steps are impossible without adb, and the launcher is
blocked by the 16 KB dialog. For this you must **build the gfxreconstruct layer
from source** with a modern NDK so it is (a) **16 KB-aligned** (Android 15/16
loads it, launcher start works) and (b) honors **`VK_EXT_layer_settings`** (recent
gfxreconstruct `dev`). Then the marker-file flow works with no adb: the app passes
the output path via `VK_EXT_layer_settings`, and the tester just drops
`gfxrecon_capture.txt`, plays to the repro spot, closes the game, and sends
`files/gfxr/*.gfxr`. (This source build was not needed for our own testing, so it
is not included here.)

---

## Credits & links
- Upstream: [hedge-dev/UnleashedRecomp](https://github.com/hedge-dev/UnleashedRecomp)
- Renderer: **plume**; **libadrenotools** (bylaws); **Mesa Turnip** (freedreno)
- Turnip driver builds: `SansNope/Banners-Turnip` (fork), K11MCH1 AdrenoToolsDrivers
- Mesa issue: `gitlab.freedesktop.org/mesa/mesa` work item **15792**
- Another stock‑driver Android fork (Vulkan 1.3+ devices only, no Turnip):
  `winnerspiros/UnleashedRecomp_Android`
