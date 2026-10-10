# Unleashed Recompiled for Android

<img width="640" height="360" alt="715 Sem Título_20260710122452" src="https://github.com/user-attachments/assets/00fa9b4a-4761-4cad-9ecb-060a5bec815d" />

Play the Xbox 360 version of *Sonic Unleashed* natively on a supported Android device.

This is an unofficial Android port of [Unleashed Recompiled](https://github.com/hedge-dev/UnleashedRecomp). It runs the game through static recompilation rather than emulating an Xbox 360, and includes an Android app, touch controls, gamepad support, a mod manager, and a custom Vulkan driver for many Qualcomm Adreno GPUs.

> [!IMPORTANT]
> This project does not include the game. You must supply files from your own legally acquired copy of *Sonic Unleashed* for Xbox 360.

> [!WARNING]
> This is still an experimental community port. Performance and stability vary by phone, Android version, and GPU driver. Keep a copy of your saves and game files.

## What works

- The full game, including title screens and regular gameplay
- ARM64 Android devices
- Installing the game and mods from a `.zip` or folder directly in the app — no PC needed
- Staging Xbox 360 base-game, title-update and DLC package files for verified installation, including adding DLC to an installed game
- Choosing game storage on internal memory or a mounted SD card, with optional verified copying of game files, mods, settings and saves
- On-screen touch controls with multi-touch, touch camera control, and a drag-to-arrange layout editor with per-control sizes; the layout adapts to the game (D-pad in menus, a SKIP button in cutscenes)
- Bluetooth and USB controllers
- Sound through speakers, wired headphones, and Bluetooth devices, with presets for handhelds that start silent (AYN) and a low-latency profile
- High refresh rate displays: FPS options above 60 are no longer capped by a 60 Hz display mode
- HMM and UMM-style mods through the included manager, including per-mod `ConfigSchemaFile` settings
- Signed in-app updates from this repository's GitHub releases
- Built-in Turnip driver plus importing another driver as a plain `.so` or an AdrenoTools/ExynosTools package `.zip`
- Game-file access through Android's system Files app
- App UI in English, Russian, Japanese, German, French, Spanish, Italian, and Portuguese
- Logs and hang diagnostics that can be shared without `adb`
  
The main development targets are Adreno 710, 725, 732, and 750. Adreno 720 and 722 are included in the bundled driver but need more testing. Adreno 830 (Snapdragon 8 Elite) has its own bundled driver, selected automatically. Other Adreno 6xx/7xx/8xx devices may work, but are not guaranteed.

**Mali support is experimental.** Recent Mali GPUs (Valhall generation and newer with a Vulkan 1.3 driver — e.g. G610/G615/G710/G715/G720) run the game through the stock system driver: the app detects a non-Adreno GPU automatically and skips the bundled Adreno driver. Textures are decompressed on the CPU when the driver lacks BC format support, which increases memory usage. Confirmed working on a Dimensity 8300 Ultra (Mali-G615), and reported playable at reduced settings on G57 and G68. Older Mali generations (Bifrost, such as G52 and G76) cannot run the game with the system driver, because it lacks the Vulkan descriptor indexing the renderer needs; the game explains this instead of crashing.

Experimental community builds of Mesa's PanVK now run on the stock Mali kernel driver: [panvk-kbase-android](https://github.com/zenithblue-oss/panvk-kbase-android) for recent Valhall GPUs (Android 15+) and [mali-panvk](https://github.com/JimVulkan/mali-panvk) for Bifrost. Their zip packages can be imported in the launcher's **Graphics** section. They are untested with this game, may cause GPU faults or hangs, and are the only known route for Bifrost devices; please attach `log.txt` when reporting results. Details are in [`docs/GRAPHICS_DRIVERS.md`](docs/GRAPHICS_DRIVERS.md).

PowerVR and Samsung Xclipse are untested. On Xclipse devices the system driver is used by default; community compatibility packages from [ExynosTools](https://github.com/WearyConcern1165/ExynosTools) can be dropped into `driver_import/` as a `.zip` to use them instead. If such a GPU supports BC texture formats natively, the launcher's **Advanced** section has an experimental **Force native BC textures** toggle that skips the CPU-side ETC2 transcode ([issue #72](https://github.com/SansNope/UnleashedRecomp-Android/issues/72)); leave it off on Adreno/Turnip, which genuinely lacks BC.

## Before you install

You need:

- A 64-bit Android device
- Android 10 or newer
- A supported Qualcomm Adreno GPU (or a recent Mali GPU — experimental)
- Several gigabytes of free storage
- Your own Xbox 360 game dump

For the smoothest first run, start with the default graphics settings. The Android build defaults to a 50% resolution scale, no anti-aliasing, 4× anisotropic filtering, and motion blur disabled.

## Installation

1. Download the latest APK from the repository's [Releases](https://github.com/SansNope/UnleashedRecomp-Android/releases) page.
2. Allow your browser or file manager to install apps from unknown sources when Android asks.
3. Install and open **UnleashedRecomp**. The first launch creates the app's folders and prepares the bundled graphics driver.
4. In the launcher's **Game files** section, tap **Install game / DLC (.zip / folder)** and pick your game dump — either a ZIP archive or an extracted folder. The app finds the game inside the archive automatically, however the folders are nested, and copies everything into place with a progress display. You can also choose **ISO / update / DLC packages**, select the base game, title update and optional DLC files together, then launch once to verify and install them.
5. Tap **Launch game**.

A raw dump (`game` + `update`, with optional `dlc`) is enough: if the `patched` folder produced by the desktop installer is missing, the app builds the patched executable itself on first launch. No PC is required at any point.

To add DLC later, use the same installer with DLC packages or a DLC-only ZIP/folder, then launch the game to verify and install the packs. You do not need to import the base game again; a title update staged next to the DLC is ignored. If a DLC import fails, the app shows the error, removes the staged copies and starts the installed game.

The launcher's storage selector lists app folders on available volumes, including mounted SD cards. You can copy your existing installation to the selected location; the copy is verified and the original files are kept. Fully close and reopen the app before changing storage after a game session. A selected SD card must remain available when launching the game.

<details>
<summary>Manual installation (alternative)</summary>

- Open Android's Files app, choose **Unleashed Recomp game files**, and copy the `game`, `update`, and optional `dlc` folders from your dump into the folder shown by the app.
- If your file manager cannot see `Android/data`, put the same folders into `Android/media/com.sega.sonicunr/UnleashedRecomp/` instead — that folder is browsable by regular file managers and the app picks it up automatically. Driver packages can likewise go into `Android/media/com.sega.sonicunr/driver_import/`.
- Do not use `adb push` directly into `Android/data`. Files created there by the shell can receive ownership that prevents the app from reading them.

</details>

## Controls

The touch controller appears automatically when you touch the screen. It provides:

- Left analog stick — which turns into an 8-way D-pad while a menu, the title screen, or the world map is open
- A, B, X, and Y
- LB and RB
- LT and RT
- Start and Back
- Camera control: drag anywhere on the free right half of the screen (default), or switch to a dedicated virtual right stick under **Input** in the game's options menu

During in-engine cutscenes the controls collapse into a single **SKIP** button; the full layout returns when gameplay resumes.

Every control can be repositioned and resized: tap **Controls** in the launcher to open the drag-to-arrange editor. Drag a control to move it. Tap a control and use **-** / **+** to resize only that control; tap empty space and use **-** / **+** to resize the whole set ([issue #169](https://github.com/SansNope/UnleashedRecomp-Android/issues/169)).

The layout adapts to context: the left stick becomes a directional pad in menus, on the world map, and on the results and level-up screens, and everything collapses to a single **SKIP** button during cutscenes and the attract movie.

On-screen control settings live in the game's own options menu under **Input**:

- **On-screen controls** — when the touch overlay is shown (Auto, Always on, Off).
- **Touch camera** — swipe on the right side of the screen, a dedicated right stick, or off.
- **On-screen stick** — a virtual analog stick, or an 8-way D-pad you can also use during gameplay ([issue #68](https://github.com/SansNope/UnleashedRecomp-Android/issues/68)).

The **Video** category adds **Show FPS** and **Show Profiler** toggles. To drag the on-screen buttons into a custom arrangement, use **Controls** in the launcher.

When a physical controller sends input, the touch overlay hides itself. Touch the screen again to bring it back.

USB and Bluetooth controllers supported by SDL should work without additional setup. In-game prompts may not match every third-party controller exactly.

## Installing mods

1. Tap **Install a mod (.zip / folder)** in the launcher's **Mods** section and pick the mod archive or folder. Every mod inside is detected by its `mod.ini` — archives containing several mods install in one go.
2. Tap **Manage mods**, enable the mods you want, and arrange their priority. Mods that declare HMM's `ConfigSchemaFile` show a settings button for their own booleans, numbers, text values and lists.
3. Tap **Save mod list** and restart the game.

Mods can also be copied into the `mods` folder manually through Android Files (each mod in its own folder with a `mod.ini`); tap **Refresh** in the manager afterwards.

The manager writes standard `cpkredir.ini` and `ModsDB.ini` files. Relative paths from desktop mod packs are also supported, so most HMM/UMM layouts can be copied without manually rewriting every path. Desktop-only code mods or mods that depend on Windows DLLs will not work on Android.

The mod manager also has a **Codes** section listing built-in game patches — for example *Homing attack on jump*, *Save score at checkpoints*, or *Skip intro logos*. Tick the ones you want and save the mod list; codes are applied on the next launch. Per-mod schema settings implement [issue #75](https://github.com/SansNope/UnleashedRecomp-Android/issues/75).

## Updating the app

The launcher checks this repository's latest GitHub release periodically and also provides a manual **Check for updates** button. Downloads are limited to the official release path, checked against GitHub's SHA-256 digest when available, and must carry the same Android signing certificate as the installed app before Android's package installer is opened. Game files, saves, mods and settings remain in place during an update.

## Graphics drivers

The app includes a community-built Mesa Turnip driver tuned for the Adreno devices listed above. It is selected automatically on a fresh installation. A separate **Adreno 710 (Vauzi)** entry bundles Vauzi-17's current 710/720/722 release for A/B testing; its Auto render mode selects Sysmem.

You can choose the driver and render mode in the launcher's **Graphics** section or in the game's options menu. If you want to try another driver:

1. Open the app's transfer folder in Android Files.
2. Copy a compatible ARM64 driver into `driver_import` — either a plain Turnip `.so` or a whole driver-package `.zip` (AdrenoTools releases; ExynosTools backend loading is experimental).
3. Start the game and select the imported driver.

Only import drivers from a source you trust. A bad or incompatible Vulkan driver can cause graphical corruption, freezes, or startup crashes. The built-in recovery path lets you return to the bundled driver if an imported one fails.

### Device notes

- **Adreno 710:** first try **Adreno 710 (Vauzi)** with Render Mode **Auto** (Sysmem). The universal bundled driver remains available for comparison and includes a synchronization fix for early-a7xx shimmer.
- **Adreno 6xx:** if Auto/GMEM shows corruption, hangs, or unexpectedly low performance, try Render Mode **Sysmem**. It forces `TU_DEBUG=sysmem`; it is a workaround to test, not a universal performance win.
- **Adreno 725:** start with the universal bundled driver. An **A725 Performance (experimental; sysmem+nobin)** choice is also included for affected devices; selecting it automatically locks the required Sysmem mode and `TU_DEBUG=sysmem,nobin`. It is not selected by default and is not intended for Adreno 732 yet.
- **Adreno 732:** supported through a community device profile based on the closely related Adreno 735.
- **Adreno 750:** disable MSAA if you see corruption. The known issue is in the Turnip MSAA path; the default Android settings already leave anti-aliasing off.
- **Adreno 720 / 722:** driver entries are included, but real-device feedback is still needed.
- **Adreno 830 (Snapdragon 8 Elite):** Auto selects the bundled **Adreno 8xx** driver (Mesa 26.3 with a Qualcomm gralloc UBWC fix), which always renders with `TU_DEBUG=sysmem,nobin`. Other Adreno 8xx GPUs can select it manually; it has only been validated on Adreno 830.
- **Adreno 6xx with an imported Turnip driver:** if the image freezes while audio continues, switch to **A725 Performance** or another driver. Since 0.6.1 the game reports a lost GPU device instead of freezing ([issue #170](https://github.com/SansNope/UnleashedRecomp-Android/issues/170)).

Technical findings and current limitations for ExynosTools and PanVK are documented in [`docs/GRAPHICS_DRIVERS.md`](docs/GRAPHICS_DRIVERS.md).

## Performance on weaker devices

The Android build already defaults to a 50% resolution scale, no anti-aliasing, and motion blur off. If that is not enough, the in-game Video options offer, roughly in order of impact:

- **Resolution Scale** — the single biggest lever; it can go down to 25%.
- **Texture Quality** (Full / Half / Quarter) — loads every texture at half or quarter resolution, cutting texture memory and GPU bandwidth like a low-resolution texture pack, but covering the base game, DLC, and mods with no extra files. UI and fonts stay sharp.
- **FPS cap** — capping at 30 greatly reduces heat and gives steadier frame pacing on devices that cannot hold 60.
- **Shadow Resolution** — down to 256 for the weakest GPUs.
- **Planar Reflections** — turning them off stops reflective stages (towns, water) from rendering the scene twice.

These options make separately downloaded "low end" texture packs unnecessary for most devices, and they stack with data-side mods that strip particles or global illumination.

## Troubleshooting

### The game cannot find my files

Use **Install game / DLC (.zip / folder)** in the launcher's **Game files** section — it locates the game inside your dump automatically and reports what is missing. If you copied files manually, make sure the selected directory contains `game`, `update`, and, if available, `dlc`, in the Files location exposed by **UnleashedRecomp**, not an arbitrary folder with the same name.

### The game opens to a black screen or corrupted graphics

- Return to the bundled driver.
- Disable anti-aliasing/MSAA.
- Keep the resolution scale at 50% while testing.
- Restart the app after changing the driver.

### Sound crackles or stops after using Bluetooth

Pause for a moment after connecting or disconnecting the device, then return to the game. If audio does not recover, restart the app and attach the latest log when reporting the issue.

### No sound or high audio latency

The launcher's **Audio** section has presets: **AYN** for handhelds that start without sound (AYN Odin 2 / Thor and similar) and **POCO F8 Ultra (low latency)**, an experimental exclusive low-latency AAudio path that may crackle on other devices. The preset takes effect on the next launch.

### The app freezes

Close it normally if possible, reopen the app's transfer folder, and retrieve:

- `log.txt` — the latest run
- `log_prev.txt` — the previous run

The logger includes a hang watchdog, so `log.txt` may contain thread-state information even when no debugger was connected.

When reporting a problem, include your phone model, SoC/GPU, Android version, selected driver, render mode, and the exact point where the problem occurred.

## For developers

Building the port requires Windows, Visual Studio 2022 Build Tools, CMake, Ninja, Android SDK/NDK r29, JDK 17, and vcpkg. Host recompilation tools must be built for Windows before cross-compiling the Android ARM64 target.

The repository contains helper scripts, but some still contain machine-specific paths and should be reviewed before use. A checkout path without spaces is strongly recommended.

No generated or copyrighted game code may be committed. In particular, keep these local:

- `UnleashedRecompLib/private/`
- `UnleashedRecompLib/ppc/`

The Android-specific implementation lives primarily in:

- `UnleashedRecomp/os/android/`
- `android-apk/`
- `thirdparty/libadrenotools/`
- `thirdparty/plume/`
- `thirdparty/SDL/`
- `UnleashedRecomp/ui/touch_controls.*`

## AI-assisted development

A large part of this port was written in collaboration with AI tools — primarily Anthropic's Claude (Fable 5) model. The workflow is human-led: the maintainers set the direction, test every build on real devices, debug with testers, and decide what ships; the AI does much of the code writing, crash analysis, and reverse engineering under that guidance. It has proven effective — the "ring crash" root-cause hunt in issue #27 went through nine diagnostic builds driven this way — but it also means occasional artifacts, freezes, or audio issues can slip through. Detailed bug reports with `log.txt` attached are the most valuable contribution: they are how problems get root-caused and fixed.

## Legal

This repository contains no *Sonic Unleashed* game assets. It is not affiliated with or endorsed by SEGA, Microsoft, the upstream Unleashed Recompiled team, Mesa, or Qualcomm.

The project is distributed under GPL-3.0, following the upstream project. Third-party components retain their own licenses.

## Credits

This port exists because many people shared code, testing time, traces, hardware access, and patient debugging:

- [hedge-dev and the Unleashed Recompiled contributors](https://github.com/hedge-dev/UnleashedRecomp) — the original static recompilation project
- [SansNope](https://github.com/SansNope) — Android port stewardship, builds, Turnip integration, and the public home of this fork
- [ITSeniy](https://github.com/ITSeniy) — lifecycle and audio stabilization, Vulkan recovery, touch controls, Android file access, driver management, and the in-app mod workflow
- [GdGohan](https://github.com/GdGohan) — ModLoader compatibility work and Android build contributions
- [puopg (Michael Chen)](https://github.com/puopg) — the guest-memory `memmove` fix for ring crashes and collision triggers, removal of earlier workarounds, and fresh-clone build fixes
- [vladector](https://github.com/vladector) — landscape rendering in the display-cutout area and the landscape, full-screen launcher proposal
- [renderbag/plume](https://github.com/renderbag/plume) — the Vulkan renderer used by Unleashed Recompiled
- Mesa's Freedreno/Turnip developers — the open-source Vulkan driver that makes the port practical on Adreno hardware
- [bylaws/libadrenotools](https://github.com/bylaws/libadrenotools) and the Android driver community — runtime custom-driver loading
- [Vauzi-17/710](https://github.com/Vauzi-17/710) and whitebelyash/AdrenoToolsDrivers — community Adreno 710/720/722 device information
- Anthropic's Claude (Fable 5) — AI pair programming across the port: code, crash analysis, and reverse engineering under maintainer guidance
- Everyone who tested unfinished builds, captured logs, reproduced GPU bugs, and reported what happened
