# Android graphics-driver findings

Research date: 2026-07-11; Mali section updated 2026-10-10.

## Adreno 710 / Vauzi-17

The APK bundles the unmodified driver binary from Vauzi-17/710 release 2.7 as
`vulkan.vauzi710_v2_7.so`. The upstream release describes the build as
experimental Mesa 26.2.0 / Vulkan 1.4.354, focused on Adreno 710, 720 and 722,
and recommends `TU_DEBUG=sysmem`. The published ZIP SHA-256 was verified before
the binary was copied into the project.

The driver is a separate explicit choice. Auto remains on the project's
universal Turnip build so existing devices do not silently change driver. When
the Vauzi choice and Render Mode Auto are combined, the app applies Sysmem;
users can still force GMEM for comparison.

## Samsung Xclipse / ExynosTools

ExynosTools 2.1 ships different packages for Exynos 2200, 2400 and 2600, plus a
native variant. A checked Exynos 2400 Vortek package contains:

- `vulkan.xclipse24.3.9.so` as `meta.json`'s `libraryName`;
- `libVkLayer_VortekXclipse.so` and its layer manifest;
- `libnramv.so`.

The current ZIP importer safely extracts those files and can select the main
ICD. Full Vortek compatibility is not yet integrated, however: the game opens a
custom ICD directly through libadrenotools, while ExynosTools' BCn and quirk
logic lives in a Vulkan layer that must participate in the instance/device call
chain. Merely packaging the ZIP does not prove that layer is active.

A built-in Samsung choice should therefore wait for all of the following:

1. an explicit-layer loading path compatible with the direct custom ICD;
2. SoC detection and selection among the 2200/2400/2600 packages;
3. confirmation that redistribution of the packaged Samsung backend is allowed;
4. real-device testing on each supported Xclipse generation.

Until then, the stock system Vulkan driver is the safe Auto choice on Xclipse.
ZIP import remains available for experiments, but should not be described as
full ExynosTools layer support.

## Mali: stock drivers and PanVK

### What the renderer needs

Every recompiled shader indexes one large texture and sampler heap through runtime
descriptor arrays and loads constants through buffer device addresses. A driver
therefore needs Vulkan 1.2-level `descriptorIndexing` (runtime arrays, partially
bound and variable-count bindings, update-after-bind limits in the thousands),
`bufferDeviceAddress` and `scalarBlockLayout`. BC texture support is optional: without
it, textures are transcoded to ETC2/EAC on the CPU at load time.

### Stock Arm drivers

- **Valhall and newer** (G57, G68, G77, G610/G615, G710/G715, G720 and later) expose
  these features and run the game with the system driver. Reports show low-end parts
  such as G57 MC2 and G68 MP4/MP5 as playable at reduced settings, with some
  Mali-specific rendering glitches (collectibles drawn through walls, a missing
  character in one cutscene) that still need GPU captures to diagnose.
- **Bifrost** (G52, G71, G72, G76) stock drivers report no descriptor indexing. The
  descriptor limits fall back to 256 textures and 128 samplers, every graphics pipeline
  fails with `VK_ERROR_INITIALIZATION_FAILED`, and the driver crashes in
  `libGLES_mali.so` shortly after (issue #147). The game now detects the missing feature
  after device creation and shows an explanation instead. Supporting these drivers would
  need a renderer without the bindless heap, which is not planned.

### PanVK on the stock kernel driver

Upstream PanVK talks to the `panfrost`/`panthor` DRM kernel drivers, which retail
Android phones do not ship. Since mid-2026, community projects run PanVK directly on
Arm's `mali_kbase` interface (`/dev/mali0`), without root or a custom kernel:

- [zenithblue-oss/panvk-kbase-android](https://github.com/zenithblue-oss/panvk-kbase-android)
  for Valhall CSF GPUs (G610, G615, G710, G715, G720, G925; G57 experimental). It
  reports Vulkan 1.4 with descriptor indexing and buffer device address, and exposes BC
  textures through GPU decoding, so the game would skip the CPU ETC2 transcode. The
  0.1.0 RC packages declare `minApi` 35 (Android 15).
- [JimVulkan/mali-panvk](https://github.com/JimVulkan/mali-panvk) for Bifrost, tested on
  Mali-G52 MC2 (Helio P65) and G76 (Exynos 9820). It reports Vulkan 1.3 without BC
  formats. Whether its descriptor limits suffice for the game is unverified; it is the
  only known route for Bifrost.
- Smaller per-device builds exist, e.g. [LukeValen/panvk-mali-g52](https://github.com/LukeValen/panvk-mali-g52)
  (Redmi 13C, alpha).

Both main projects ship standard AdrenoTools zips (`meta.json` with `libraryName`), which
the in-app importer accepts: import the zip in the launcher's **Graphics** section and
start the game. A package whose `meta.json` `minApi` exceeds the device's API level is
moved to `driver_import/rejected/` and reported in the launcher instead of failing at
load time. All of these drivers are experimental and non-conformant; their authors
warn about GPU faults and hangs, and vendor kernels (MediaTek, Exynos, Tensor) differ.
None is bundled in the APK until real-device results with this game exist.

## Sysmem on Adreno 6xx

Turnip supports two render-pass paths: tiled on-chip GMEM and direct rendering
to system memory. `TU_DEBUG=sysmem` forces the latter. Reports that Sysmem helps
Adreno 6xx are plausible for workload- or driver-specific GMEM bugs and the
option is useful for diagnosing corruption, hangs and performance cliffs.

It should not be forced for every Adreno 6xx device: Mesa normally selects
between GMEM and Sysmem using its autotuner, and forcing either path can regress
other scenes. The launcher therefore exposes Sysmem as an explicit Render Mode;
only the dedicated Vauzi 710 choice maps Auto to Sysmem because that upstream
release specifically recommends it.
