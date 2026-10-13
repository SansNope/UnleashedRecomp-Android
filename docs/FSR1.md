# FSR 1 release candidate

Enable **Options → Video → AMD FSR 1** and set **Resolution Scale** below 100%.
Start with 50% resolution and 20% FSR Sharpness. FSR is off by default; changing
it does not change the resolution scale or anti-aliasing setting. Sharpness zero
skips RCAS and uses EASU alone. Values above 20% can emphasize aliasing.

Equivalent entries in the installation's `.config/UnleashedRecomp/config.toml`:

```toml
[Video]
ResolutionScale = 0.5
FSR = true
FSRSharpness = 0.2
```

Edit existing entries rather than adding another `[Video]` section.

## Rendering

The renderer recognizes the original game's `FxCopyColor` and
`FxColorCorrection` scene compositors by their shader hashes. It requires an
opaque fullscreen draw to the backbuffer, with a source matching the internal
scene resolution. Partial viewports, clipped draws, depth-tested draws and
unknown shaders retain the original rendering path.

The selected compositor first runs at scene resolution into a per-frame LDR
target. Its original half-pixel correction is preserved. EASU upscales that
result, then optional RCAS sharpens it. Subsequent game HUD and native UI draws
remain at display resolution; the movie shaders are not intercepted. The normal
brightness, color correction and presentation path still runs afterwards.

This is deliberately before UI composition, rather than an FSR pass over the
already enlarged final backbuffer. Applying EASU to bilinear-upscaled pixels
would lose much of its benefit.

The first intercepted draw logs `FSR 1 active: ... (scene only, FP16/FP32)`. Confirm this
message during an actual level. Merely enabling the option does not prove that
a particular scene or mod uses a supported compositor.

Targets and texture descriptors belong to individual buffered frames. A target
is resized only after that frame's GPU fence has completed. EASU uses a clamp
sampler; RCAS clamps integer neighbor reads at the image edges. Render state is
invalidated after the custom passes so the next guest draw restores its state.

Both shaders have FP32 and native FP16 variants. Vulkan automatically uses
AMD's half-precision EASU/RCAS routines when `shaderFloat16` and `shaderInt16`
are enabled on the logical device. On Vulkan 1.2+ the float feature comes from
the core Vulkan12Features structure; below 1.2 it is queried and enabled only
when `VK_KHR_shader_float16_int8` is exposed. D3D12 and unsupported Vulkan
devices retain FP32. Very large outputs (32768 or more in either dimension)
also retain FP32 because AMD's half RCAS uses signed 16-bit pixel coordinates.
The descriptor, constant-buffer and color-output interfaces remain 32-bit;
16-bit storage/interface features are not required.

Startup logs report FP16 availability; the first active pass logs its selected
precision. RC1 restores the original Show FPS overlay and removes temporary
per-frame diagnostics and periodic FSR diagnostic logs. The earlier diag1 and
fp16-test1 APKs remain available for comparisons with their extended overlays.
Native FP16 is a candidate
optimization, not a guaranteed FPS increase: texture sampling, memory traffic
and render passes are unchanged. Adreno performance must be measured on device.
The shaders use the shared renderer and do not depend on a particular Adreno
driver profile. This is FSR 1 spatial upscaling, without temporal reconstruction
or frame generation.

AMD's unmodified MIT-licensed headers are pinned in `thirdparty/FidelityFX-FSR`.
The CMake shader rules use HLSL 2018 for these headers and track their include
dependencies. Both SPIR-V and DXIL headers are supplied, matching the repository's
existing shader convention.

## Validation

The standalone Vulkan smoke test renders real EASU and RCAS passes and reads
back their results. It checks five constant colors, EASU gradient orientation,
RCAS changes on a checkerboard, three sharpening strengths, complete coverage
and borders of a 17×11 → 31×19 upscale. It passed on NVIDIA GeForce RTX 3060 Ti.
It does not exercise the guest compositor detector or prove Android compatibility.

From a Windows developer shell with Clang, CMake and Ninja:

```powershell
cmake -S tests/fsr_vulkan -B out/build/fsr-smoke -G Ninja -DCMAKE_CXX_COMPILER=clang-cl -DCMAKE_BUILD_TYPE=Release
cmake --build out/build/fsr-smoke
out/build/fsr-smoke/fsr_vulkan_smoke.exe
out/build/fsr-smoke/fsr_vulkan_smoke.exe --fp16
```

The test uses a direct Vulkan image-to-buffer copy for readback because Plume's
generic `copyTextureRegion` currently implements only uploads and image copies.
Production FSR does not require readback. Plume changes for the optimized
version only expose/enable native arithmetic capabilities; its copy code is
unchanged. Both FP32 and FP16 passed the same smoke-test checks on RTX 3060 Ti.

Android native code was built with NDK 29.0.14206865 for arm64-v8a / API 29.
Java compilation and the Android translation coverage check passed. The RC1 APK
uses the optional Gradle argument `-PunleashedVersionName=0.6.1-fsr1-rc1`;
ordinary builds retain version name 0.6.1.

The user confirmed the FP32 path activates on Adreno 725 at 50% scale
(960×540 → 1920×1080). Paired original screenshots show clearer static edges
with EASU+20% RCAS; the contributions of the two filters were not isolated.
Individual GPU samples were about 1–2 ms slower with FSR at the same scale.
Camera, animation and temperature differences prevent treating those samples
as a controlled benchmark.

The fp16-test1 overlay also confirmed native FP16 activation on Adreno 725
(960×540 → 1920×1080) and Adreno 830 (1390×632 → 2780×1264). A paired A725
sample showed 44.39 FPS / 22.49 ms GPU off and 40.85 FPS / 23.61 ms on. A830
night-scene samples showed 118.82 FPS / 6.41 ms off and 114.90 FPS / 7.35 ms on.
These are individual frame samples, not averaged or temperature-controlled
measurements. The two devices ran different scenes and output sizes; do not
use these values to compare their GPU performance or isolate FP16 versus FP32.

The user reported that movies, HUD, resolution-scale changes and restoring
the app after backgrounding looked correct during device checks. These checks
apply to fp16-test1; RC1 only removes the temporary instrumentation.

On October 14, 2026, the user reported a successful launch on a Helio G99
device. This is user-reported launch validation; the device model, selected
FP16/FP32 path, frame pacing and performance measurements were not provided.
It does not establish compatibility with every Mali device or validate all
rendering and app-lifecycle scenarios on that device.

The pre-Vulkan-1.2 extension branch and physical devices without half-precision
features remain untested. Not every scale/sharpness or cutscene was separately
documented. FSR adds GPU work at the same resolution scale; any performance
benefit comes from choosing a lower internal resolution at acceptable quality.

Official references: [FSR source](https://github.com/GPUOpen-Effects/FidelityFX-FSR)
and [AMD spatial super resolution documentation](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-spatial/).
