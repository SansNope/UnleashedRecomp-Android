#include <apu/audio.h>
#include <app.h>
#include <cpu/guest_thread.h>
#include <kernel/heap.h>
#include <os/logger.h>
#include <ui/game_window.h>
#include <user/config.h>

#ifdef __ANDROID__
extern "C" void Android_JNI_FlushAudioOutput(void);
extern "C" void Android_JNI_PauseAudioOutput(void);
extern "C" void Android_JNI_ResumeAudioOutput(void);
extern "C" int Android_JNI_RouteAudioOutput(int device_id);
#endif

// ============================================================================================
// Audio output, v2 ("clocked producer / trivial consumer").
//
// Invariants distilled from every failure mode seen so far (see project notes):
//
//  1. The guest render callback is the engine's audio CLOCK. It must be invoked at an average
//     of exactly XAUDIO_SAMPLES_HZ/XAUDIO_NUM_SAMPLES (187.5/s) of wall time, never bursting
//     above real time based on queue state ("catch-up" broke level loading twice), and never
//     stopping (a stopped clock deadlocks cutscenes/loads that wait on audio progress).
//  2. The guest must never run on the platform's audio thread, and the clock must never
//     depend on the platform stream's liveness: AAudio/AudioTrack streams were observed to
//     stall permanently (MMAP on SD 8 Gen 3; legacy AudioTrack write futex on the dev phone),
//     which with a device-clocked design froze the whole game.
//  3. Production capped at "one frame per wakeup" loses slots whenever the OS wakes the
//     thread late; the resulting deficit is permanent, so outputs that consume in large
//     bursts (Bluetooth routes, 1024-frame HALs) sit at an empty buffer and crackle - the
//     original tester bug. Production must be per ELAPSED SLOT, not per wakeup.
//
// Design: a producer thread owns a virtual slot clock (CLOCK_MONOTONIC based). On every
// wakeup it invokes the guest once per elapsed slot, submissions land in a small FIFO. The
// SDL device callback (large chunks, no low-latency modes) only memcpy's from the FIFO and
// plays silence when it runs dry - it never blocks and never touches guest state. The clock
// starts the configured number of cushion frames in the past so a jitter-absorbing cushion builds during the very
// first wakeups (boot, before any streaming) and is never rebuilt mid-game above real time.
// If the FIFO is full the producer skips guest calls (stock backpressure semantics) - unless
// the device looks dead (no consumption for DEVICE_DEAD_NANOS), in which case frames are
// rendered and dropped so the engine clock survives a dead platform stream.
// ============================================================================================
#define APU_PULL_MODEL 1

static PPCFunc* g_clientCallback{};
static uint32_t g_clientCallbackParam{}; // pointer in guest memory
static SDL_AudioDeviceID g_audioDevice{};
static bool g_downMixToStereo;

#if APU_PULL_MODEL

struct AudioPresetSettings
{
    const char* name;
    uint16_t deviceSamples;
    size_t cushionFrames;
    size_t routePrefillFrames;
    bool keepDefaultRouteDuringBoot;
    bool useAAudioLowLatency;
};

// Device-side chunks stay conservative in every preset. The POCO F8 Ultra profile opts into
// AAudio's low-latency performance path and reduces our FIFO cushion; the generic profiles
// keep the non-MMAP path that is known to be more robust on other Snapdragon devices.
#ifdef __ANDROID__
static AudioPresetSettings ResolveAudioPresetSettings()
{
    switch (Config::AndroidAudioPreset)
    {
    case EAndroidAudioPreset::AYN:
        return { "AYN", XAUDIO_NUM_SAMPLES * 4, 16, 16, true, false };
    case EAndroidAudioPreset::LowLatency:
        return { "POCOF8Ultra", XAUDIO_NUM_SAMPLES * 4, 8, 8, false, true };
    case EAndroidAudioPreset::Default:
    default:
        return { "Default", XAUDIO_NUM_SAMPLES * 4, 16, 16, false, false };
    }
}
#else
static AudioPresetSettings ResolveAudioPresetSettings()
{
    return { "Default", XAUDIO_NUM_SAMPLES, 12, 12, false, false };
}
#endif

// Resolved again after the user configuration is loaded, before SDL audio is initialized.
// Avoid reading Config from a cross-translation-unit static initializer.
#ifdef __ANDROID__
static AudioPresetSettings g_audioPresetSettings{ "Default", XAUDIO_NUM_SAMPLES * 4, 16, 16, false, false };
#else
static AudioPresetSettings g_audioPresetSettings{ "Default", XAUDIO_NUM_SAMPLES, 12, 12, false, false };
#endif

static constexpr size_t FIFO_CAPACITY_FRAMES = 64;
static constexpr int64_t DEVICE_DEAD_NANOS = 1'000'000'000; // 1 s without consumption = stalled stream
static constexpr int64_t ROUTE_REMOVE_DEBOUNCE_NANOS = 500'000'000; // output sink removed (BT off)
static constexpr int64_t ROUTE_BT_ADD_DEBOUNCE_NANOS = 1'000'000'000; // wait for A2DP codec before reopen
// Android's initial registerAudioDeviceCallback burst delivers every existing output as an
// "added" event moments after audio init; adds inside this window are enumeration, not hotplug.
static constexpr int64_t BOOT_ENUMERATION_WINDOW_NANOS = 15'000'000'000;
static constexpr int64_t ROUTE_BT_ADD_RETRY_NANOS = 500'000'000;
static constexpr int ROUTE_BT_ADD_MAX_ATTEMPTS = 4;
static constexpr uint32_t DRIFT_CORRECTION_MIN_DRY_CALLBACKS = 3; // avoid pulsing on brief underruns
static constexpr int64_t BT_SOFT_TRANSITION_HOLDOFF_NANOS = 5'000'000'000; // suppress drift fix after BT soft reconnect

// FIFO between the producer thread and the SDL audio callback. Byte-granular ring so odd
// device chunk sizes work. Guarded by SDL_LockAudioDevice (the callback runs with the device
// lock held, so the producer locks it around every ring access).
static std::vector<uint8_t> g_fifo;
static size_t g_fifoHead; // read position
static size_t g_fifoUsed; // bytes stored
static std::atomic<int64_t> g_lastConsumeTime{}; // updated by the callback; watchdog input
static std::atomic<uint32_t> g_consecutiveDryCallbacks{}; // partial/full FIFO underruns in the device callback
static std::atomic<uint32_t> g_totalDryCallbacks{};
static std::atomic<int> g_lastCallbackBytes{};
static std::atomic<int> g_maxCallbackBytes{};
static std::atomic<int64_t> g_routeReopenAfter{}; // monotonic deadline; audio thread performs the reopen
static std::atomic<int> g_pendingOutputDeviceIndex{ -1 }; // latest SDL output index from a hotplug add
static std::atomic<int> g_reopenOutputDeviceIndex{ -1 }; // consumed by CreateAudioDevice on full reopen fallback

static std::atomic<bool> g_preserveFifoOnReopen{}; // keep FIFO across REMOVE reopen only
static std::atomic<bool> g_pendingReopenPreserveFifo{ true };
static std::atomic<bool> g_pendingRouteIsAdd{}; // BT added vs output removed
static std::atomic<int> g_pendingRouteAndroidId{0}; // Android device id for lightweight route attempt on ADD
static std::atomic<int> g_pendingRouteAttempts{0};
static std::atomic<bool> g_routeChangeInProgress{};
static std::atomic<bool> g_rebuildCushion{}; // reset slot clock to boot-style cushion (not sync-to-now)
static std::atomic<bool> g_deferUnpauseUntilPrefilled{}; // route change: pause until full cushion refills
static std::atomic<int64_t> g_driftCorrectionHoldoffUntil{};
static std::atomic<int64_t> g_firstDeviceOpenTime{}; // for early-boot BT debounce
static std::atomic<int64_t> g_audioSystemInitTime{}; // set once at init; anchors the boot enumeration window
static std::atomic<bool> g_appInBackground{}; // app minimized — must not resume playback
static bool g_lastCallSubmitted; // did the last guest invocation submit a frame?

static int64_t MonotonicNanos()
{
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

static size_t FrameBytes()
{
    size_t channels = g_downMixToStereo ? 2 : XAUDIO_NUM_CHANNELS;
    return channels * XAUDIO_NUM_SAMPLES * sizeof(float);
}

static void MaybeResumePlaybackAfterPrefill()
{
    if (g_appInBackground.load(std::memory_order_relaxed))
        return;

    if (g_routeChangeInProgress.load(std::memory_order_relaxed))
        return;

    if (!g_deferUnpauseUntilPrefilled.load(std::memory_order_relaxed))
        return;

    size_t fifoBytes = 0;
    SDL_LockAudioDevice(g_audioDevice);
    fifoBytes = g_fifoUsed;
    SDL_UnlockAudioDevice(g_audioDevice);

    if (fifoBytes < g_audioPresetSettings.routePrefillFrames * FrameBytes())
        return;

    g_deferUnpauseUntilPrefilled.store(false, std::memory_order_relaxed);
    SDL_PauseAudioDevice(g_audioDevice, 0);
    LOGF("Audio output resumed after prefill ({} bytes in FIFO).", fifoBytes);
}

static void FifoWrite(const uint8_t* data, size_t size)
{
    // Called with the device lock held. Caller guarantees capacity.
    size_t tail = (g_fifoHead + g_fifoUsed) % g_fifo.size();
    size_t first = std::min(size, g_fifo.size() - tail);
    memcpy(g_fifo.data() + tail, data, first);
    memcpy(g_fifo.data(), data + first, size - first);
    g_fifoUsed += size;
}

static void ClearAudioFifo()
{
    SDL_LockAudioDevice(g_audioDevice);
    g_fifoHead = 0;
    g_fifoUsed = 0;
    SDL_UnlockAudioDevice(g_audioDevice);
}

static void AudioDeviceCallback(void* userdata, Uint8* stream, int len)
{
    g_lastCallbackBytes.store(len, std::memory_order_relaxed);
    int prevMax = g_maxCallbackBytes.load(std::memory_order_relaxed);
    while (len > prevMax && !g_maxCallbackBytes.compare_exchange_weak(prevMax, len, std::memory_order_relaxed))
    {
    }

    g_lastConsumeTime.store(MonotonicNanos(), std::memory_order_relaxed);

    if (g_fifo.empty())
    {
        memset(stream, 0, len);
        g_consecutiveDryCallbacks.fetch_add(1, std::memory_order_relaxed);
        g_totalDryCallbacks.fetch_add(1, std::memory_order_relaxed);
        return;
    }

    size_t toCopy = std::min(size_t(len), g_fifoUsed);
    size_t first = std::min(toCopy, g_fifo.size() - g_fifoHead);
    memcpy(stream, g_fifo.data() + g_fifoHead, first);
    memcpy(stream + first, g_fifo.data(), toCopy - first);
    g_fifoHead = (g_fifoHead + toCopy) % g_fifo.size();
    g_fifoUsed -= toCopy;

    if (toCopy < size_t(len))
    {
        memset(stream + toCopy, 0, len - toCopy); // ran dry: this slice plays silence
        g_consecutiveDryCallbacks.fetch_add(1, std::memory_order_relaxed);
        g_totalDryCallbacks.fetch_add(1, std::memory_order_relaxed);
    }
    else
    {
        g_consecutiveDryCallbacks.store(0, std::memory_order_relaxed);
    }
}

static std::unique_ptr<std::thread> g_audioThread;
static volatile bool g_audioThreadShouldExit;

static void CreateAudioDevice();

static void ScheduleAudioRouteChange(const char* reason, int deviceId, int64_t debounceNanos)
{
    const int64_t deadline = MonotonicNanos() + debounceNanos;
    int64_t prev = g_routeReopenAfter.load(std::memory_order_relaxed);
    while (deadline > prev)
    {
        if (g_routeReopenAfter.compare_exchange_weak(prev, deadline, std::memory_order_relaxed, std::memory_order_relaxed))
            break;
    }

    LOGF("Audio route change: scheduling handler ({}, id={}).", reason, deviceId);
}

static void PerformRouteReopen(const char* label, int reopenIndex, bool preserveFifo)
{
    // Android recreates a dead IAudioTrack on route change (SDL issue #4985). nullptr reopen
    // picks up the current system default (BT after A2DP settle, speaker after unplug).
    g_pendingOutputDeviceIndex.store(-1, std::memory_order_relaxed);
    g_reopenOutputDeviceIndex.store(reopenIndex, std::memory_order_relaxed);

    // Preserve FIFO and avoid full clock reset on route changes.
    // This reduces the "burst of guest renders" that causes choppiness.
    // The existing CreateAudioDevice has save/restore logic if preserve is set.
    g_preserveFifoOnReopen.store(preserveFifo, std::memory_order_relaxed);
    g_routeChangeInProgress.store(true, std::memory_order_relaxed);

#ifdef __ANDROID__
    Android_JNI_FlushAudioOutput();
#endif

    SDL_PauseAudioDevice(g_audioDevice, 1);
    g_deferUnpauseUntilPrefilled.store(true, std::memory_order_relaxed);
    CreateAudioDevice();

    // Do NOT force full cushion rebuild here — we want to keep the virtual clock
    // as continuous as possible. Holdoff is still set to suppress drift correction briefly.
    g_rebuildCushion.store(true, std::memory_order_release);
    g_driftCorrectionHoldoffUntil.store(MonotonicNanos() + BT_SOFT_TRANSITION_HOLDOFF_NANOS, std::memory_order_relaxed);
    g_consecutiveDryCallbacks.store(0, std::memory_order_relaxed);
    g_lastConsumeTime.store(MonotonicNanos(), std::memory_order_relaxed);
    g_routeChangeInProgress.store(false, std::memory_order_relaxed);
    LOGF("Audio route change: {} - {} reopen (preserve FIFO: {}, cushion rebuild).", label,
        reopenIndex >= 0 ? "routed" : "default", preserveFifo ? "yes" : "no");
}

static void TryProcessAudioRouteChange()
{
    const int64_t reopenAfter = g_routeReopenAfter.load(std::memory_order_relaxed);
    if (reopenAfter == 0 || MonotonicNanos() < reopenAfter)
        return;

    g_routeReopenAfter.store(0, std::memory_order_relaxed);

    if (g_audioDevice == 0)
    {
        return;
    }

    const bool isAdd = g_pendingRouteIsAdd.exchange(false, std::memory_order_relaxed);
    int androidId = g_pendingRouteAndroidId.exchange(0, std::memory_order_relaxed);
    const int outputIndex = g_pendingOutputDeviceIndex.load(std::memory_order_relaxed);

    int routedIndex = isAdd ? outputIndex : -1;

#ifdef __ANDROID__
    // AYN preset: multi-output handhelds enumerate sinks the pinned reopen can land on
    // that produce no audible sound (AYN Thor: last id = an inaudible sink -> total
    // silence from launch). Adds inside the boot enumeration window reopen the system
    // default route instead; real hotplug later (BT mid-game) still pins to the new
    // device, which nullptr reopens historically failed to pick up.
    if (routedIndex >= 0 && g_audioPresetSettings.keepDefaultRouteDuringBoot &&
        MonotonicNanos() - g_audioSystemInitTime.load(std::memory_order_relaxed) < BOOT_ENUMERATION_WINDOW_NANOS)
    {
        LOGF("Audio route change: boot-window output add (Android id {}) - AYN preset keeps the default route.", androidId);
        routedIndex = -1;
    }
    else if (isAdd && androidId != 0)
    {
        LOGF("Audio route change: opening routed output {} directly for Android id {}.", outputIndex, androidId);
    }
#else
    (void)androidId;
#endif

    PerformRouteReopen(isAdd ? "output added" : "output removed", routedIndex, !isAdd);
}

static void PauseAudioForBackground()
{
    if (g_audioDevice == 0)
        return;

    SDL_PauseAudioDevice(g_audioDevice, 1);
#ifdef __ANDROID__
    Android_JNI_FlushAudioOutput();
    Android_JNI_PauseAudioOutput();
#endif
    g_appInBackground.store(true, std::memory_order_release);
}

static void ResumeAudioFromForeground()
{
    if (g_audioDevice == 0)
    {
        g_appInBackground.store(false, std::memory_order_release);
        return;
    }

    if (!g_appInBackground.load(std::memory_order_acquire))
        return;

    // The virtual producer clock uses wall time. If we resumed it unchanged, it would try
    // to render every slot missed in the background, briefly overloading the game and
    // desynchronizing audio. Start a fresh cushion from the foreground instant instead.
    SDL_PauseAudioDevice(g_audioDevice, 1);
    ClearAudioFifo();
    g_deferUnpauseUntilPrefilled.store(true, std::memory_order_relaxed);
    g_rebuildCushion.store(true, std::memory_order_release);
    g_consecutiveDryCallbacks.store(0, std::memory_order_relaxed);
    g_lastConsumeTime.store(MonotonicNanos(), std::memory_order_relaxed);
    g_driftCorrectionHoldoffUntil.store(MonotonicNanos() + BT_SOFT_TRANSITION_HOLDOFF_NANOS, std::memory_order_relaxed);
    g_appInBackground.store(false, std::memory_order_release);

#ifdef __ANDROID__
    Android_JNI_ResumeAudioOutput();
#endif

    LOGF("Audio background resume: rebuilding {}-frame cushion.", g_audioPresetSettings.cushionFrames);
}

extern "C" void Unleashed_AppSetPaused(int paused)
{
#ifdef __ANDROID__
    // SDL's Android pause/resume semaphore handshake can deliver a spurious "resume"
    // right after backgrounding (observed on device: resume 40 ms after pause, audio
    // kept playing in background). A genuinely foregrounded app always has a native
    // window - ignore resumes without one. The game tick (app.cpp) unfreezes us when
    // the window actually returns.
    if (!paused && GameWindow::GetAndroidNativeWindow() == nullptr)
    {
        LOG("Ignoring spurious app resume: native window is gone (still backgrounded).");
        return;
    }

    App::s_androidPaused.store(paused != 0, std::memory_order_release);

    // The hang watchdog would read the intentional freeze as a game hang and spam
    // thread dumps into log.txt; suspend it for the duration.
    os::logger::SetWatchdogSuspended(paused != 0);
#endif

#if APU_PULL_MODEL
    if (paused)
        PauseAudioForBackground();
    else
        ResumeAudioFromForeground();
#else
    (void)paused;
#endif

    LOGF("App {} (game loop + audio{}).", paused ? "paused" : "resumed",
        paused ? " stopped" : " restarted");
}

static int AppLifecycleWatch(void* userdata, SDL_Event* event)
{
    if (event == nullptr)
        return 0;

    // Diagnostics only. SDL's Android pause/resume events proved unreliable in this app
    // (spurious resume 40 ms after pause in one run, no events at all in another - its
    // semaphore handshake assumes a single-threaded event-polling app, which this is not).
    // Lifecycle state is driven by AndroidWindowWatcherThread instead.
    if (event->type == SDL_APP_DIDENTERBACKGROUND)
        LOG("SDL background event (informational only).");
    else if (event->type == SDL_APP_DIDENTERFOREGROUND)
        LOG("SDL foreground event (informational only).");

    return 0;
}

#ifdef __ANDROID__
// Freezes/unfreezes the game based on the one lifecycle signal that cannot lie or get
// lost: the ANativeWindow. The OS destroys it when the app is backgrounded and creates
// a new one on restore. A dedicated thread watches it because neither the game tick nor
// SDL's event pump can be trusted to run at that moment - the main thread was observed
// wedged inside the GPU driver on a dead swapchain for the whole backgrounded period.
static void AndroidWindowWatcherThread()
{
    bool seenWindow = false;
    bool paused = false;
    int nullSamples = 0;
    int aliveSamples = 0;

    for (;;)
    {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        const bool windowAlive = GameWindow::GetAndroidNativeWindow() != nullptr;
        if (windowAlive)
        {
            seenWindow = true;
            nullSamples = 0;
            aliveSamples++;
        }
        else
        {
            aliveSamples = 0;
            nullSamples++;
        }

        // Audio initializes before the window exists; don't treat that as "backgrounded".
        if (!seenWindow)
            continue;

        // Two consecutive samples (~100 ms) to debounce transient states.
        if (!paused && nullSamples >= 2)
        {
            paused = true;
            LOG("Native window gone - freezing game and audio.");
            Unleashed_AppSetPaused(1);
        }
        else if (paused && aliveSamples >= 2)
        {
            paused = false;
            LOG("Native window returned - unfreezing game and audio.");
            Unleashed_AppSetPaused(0);
        }
    }
}
#endif

static int AudioHotplugWatch(void* userdata, SDL_Event* event)
{
    if (event == nullptr)
        return 0;

    if (event->type == SDL_AUDIODEVICEADDED && !event->adevice.iscapture)
    {
        const char* devName = SDL_GetAudioDeviceName(event->adevice.which, 0);
        int androidId = (devName != nullptr) ? atoi(devName) : 0;

        const int64_t sinceOpen = MonotonicNanos() - g_firstDeviceOpenTime.load(std::memory_order_relaxed);
        const int64_t debounce = sinceOpen < 15'000'000'000 ? 500'000'000 : ROUTE_BT_ADD_DEBOUNCE_NANOS;
        LOGF("Audio route change: output added (SDL index {}, Android id \"{}\") — "
            "scheduling lightweight route attempt in {} ms.",
            event->adevice.which, devName != nullptr ? devName : "?", debounce / 1'000'000);
        g_pendingRouteIsAdd.store(true, std::memory_order_relaxed);
        g_pendingRouteAndroidId.store(androidId, std::memory_order_relaxed);
        g_pendingRouteAttempts.store(0, std::memory_order_relaxed);
        g_pendingOutputDeviceIndex.store(event->adevice.which, std::memory_order_relaxed);
        ScheduleAudioRouteChange("output added", event->adevice.which, debounce);
    }
    else if (event->type == SDL_AUDIODEVICEREMOVED && !event->adevice.iscapture)
    {
        if (event->adevice.which == g_audioDevice || event->adevice.which == 0)
        {
            g_pendingRouteIsAdd.store(false, std::memory_order_relaxed);
            g_pendingRouteAndroidId.store(0, std::memory_order_relaxed);
            g_pendingRouteAttempts.store(0, std::memory_order_relaxed);
            g_pendingOutputDeviceIndex.store(-1, std::memory_order_relaxed);
            LOGF("Audio route change: output removed (which={}) — scheduling full reopen.",
                event->adevice.which);
            ScheduleAudioRouteChange("output removed", event->adevice.which, ROUTE_REMOVE_DEBOUNCE_NANOS);
        }
    }

    return 0;
}

static void AudioThread()
{
    using namespace std::chrono_literals;

    GuestThreadContext ctx(0);

    constexpr auto INTERVAL = std::chrono::nanoseconds(1000000000ns * XAUDIO_NUM_SAMPLES / XAUDIO_SAMPLES_HZ);

    // Start the slot clock in the past so the first wakeups legitimately owe the configured
    // extra slots - the cushion builds at boot without any mid-game catch-up mechanism.
    int64_t startTime = MonotonicNanos() - g_audioPresetSettings.cushionFrames * INTERVAL.count();
    uint64_t producedSlots = 0;
    int64_t lastDriftCorrection = 0;
    int64_t lastUnderrunLog = 0;
    uint32_t lastDryCallbacksLogged = 0;

    g_lastConsumeTime.store(MonotonicNanos(), std::memory_order_relaxed);

    while (!g_audioThreadShouldExit)
    {
        if (g_appInBackground.load(std::memory_order_relaxed))
        {
            std::this_thread::sleep_for(50ms);
            continue;
        }

        TryProcessAudioRouteChange();

        if (g_rebuildCushion.exchange(false, std::memory_order_acq_rel))
        {
            startTime = MonotonicNanos() - g_audioPresetSettings.cushionFrames * INTERVAL.count();
            producedSlots = 0;
            lastDriftCorrection = 0;
        }

        const uint64_t elapsedSlots = uint64_t(MonotonicNanos() - startTime) / INTERVAL.count();

        while (producedSlots < elapsedSlots && !g_audioThreadShouldExit)
        {
            const size_t frameBytes = FrameBytes();

            SDL_LockAudioDevice(g_audioDevice);
            bool fifoFull = (g_fifo.size() - g_fifoUsed) < frameBytes;
            SDL_UnlockAudioDevice(g_audioDevice);

            if (fifoFull)
            {
                int64_t sinceConsume = MonotonicNanos() - g_lastConsumeTime.load(std::memory_order_relaxed);
                if (sinceConsume < DEVICE_DEAD_NANOS)
                {
                    // Healthy backpressure: the device is behind; pause the guest clock for
                    // this slot exactly like the stock MAX_LATENCY check did.
                    producedSlots = elapsedSlots;
                    break;
                }
                // Stream looks dead (see invariant 2): keep the engine clock alive, render
                // into the void. The FIFO stays full so audio resumes the moment the device
                // comes back and starts consuming again.
            }

            g_lastCallSubmitted = false;
            ctx.ppcContext.r3.u32 = g_clientCallbackParam;
            g_clientCallback(ctx.ppcContext, g_memory.base);
            producedSlots++;
        }

        MaybeResumePlaybackAfterPrefill();

        {
            const int64_t nowNs = MonotonicNanos();
            const uint32_t totalDry = g_totalDryCallbacks.load(std::memory_order_relaxed);
            if (totalDry != lastDryCallbacksLogged && nowNs - lastUnderrunLog > 1'000'000'000)
            {
                size_t fifoBytes = 0;
                SDL_LockAudioDevice(g_audioDevice);
                fifoBytes = g_fifoUsed;
                SDL_UnlockAudioDevice(g_audioDevice);

                LOGF("Audio underrun: dry callbacks {} -> {}, FIFO {} bytes, cb last/max {}/{}, defer {}, route {}, background {}.",
                    lastDryCallbacksLogged, totalDry, fifoBytes,
                    g_lastCallbackBytes.load(std::memory_order_relaxed),
                    g_maxCallbackBytes.load(std::memory_order_relaxed),
                    g_deferUnpauseUntilPrefilled.load(std::memory_order_relaxed) ? "yes" : "no",
                    g_routeChangeInProgress.load(std::memory_order_relaxed) ? "yes" : "no",
                    g_appInBackground.load(std::memory_order_relaxed) ? "yes" : "no");

                lastDryCallbacksLogged = totalDry;
                lastUnderrunLog = nowNs;
            }
        }

        // Crystal drift correction: if the device's clock runs slightly faster than ours, the
        // cushion drains by ~1 frame every few minutes and would eventually hit the crackle
        // regime again. When the FIFO is fully dry AND the engine is actively producing audio
        // (never true during level loads - their guest calls submit nothing, so this cannot
        // re-create the catch-up bug), shift the virtual clock back one slot to owe one extra
        // frame. Rate-limited hard to once per second. Suppressed for BT_SOFT_TRANSITION_HOLDOFF
        // after route reopen so brief post-route underruns do not pulse the clock.
        {
            int64_t nowNs = MonotonicNanos();
            SDL_LockAudioDevice(g_audioDevice);
            bool fifoDry = (g_fifoUsed == 0);
            SDL_UnlockAudioDevice(g_audioDevice);

            const uint32_t dryCallbacks = g_consecutiveDryCallbacks.load(std::memory_order_relaxed);
            const int64_t driftHoldoffUntil = g_driftCorrectionHoldoffUntil.load(std::memory_order_relaxed);
            if (fifoDry && g_lastCallSubmitted && dryCallbacks >= DRIFT_CORRECTION_MIN_DRY_CALLBACKS &&
                nowNs >= driftHoldoffUntil && nowNs - lastDriftCorrection > 1'000'000'000)
            {
                startTime -= INTERVAL.count();
                lastDriftCorrection = nowNs;
                g_consecutiveDryCallbacks.store(0, std::memory_order_relaxed);
            }
        }

        auto now = std::chrono::steady_clock::now();
        auto next = now + (INTERVAL - now.time_since_epoch() % INTERVAL);

        std::this_thread::sleep_for(std::chrono::floor<std::chrono::milliseconds>(next - now));

        MaybeResumePlaybackAfterPrefill();

        while (std::chrono::steady_clock::now() < next && !g_audioThreadShouldExit)
            std::this_thread::yield();
    }
}

#endif

static void CreateAudioDevice()
{
    std::vector<uint8_t> savedFifo;
    size_t savedFifoHead = 0;
    size_t savedFifoUsed = 0;
    size_t savedFrameBytes = 0;
    const bool preserveFifo = g_preserveFifoOnReopen.load(std::memory_order_relaxed);

    if (preserveFifo && !g_fifo.empty())
    {
        savedFrameBytes = FrameBytes();
        savedFifoHead = g_fifoHead;
        savedFifoUsed = g_fifoUsed;
        savedFifo = g_fifo;
    }

    if (g_audioDevice != NULL)
        SDL_CloseAudioDevice(g_audioDevice);

    bool surround = Config::ChannelConfiguration == EChannelConfiguration::Surround;
    int allowedChanges = surround ? SDL_AUDIO_ALLOW_CHANNELS_CHANGE : 0;

    SDL_AudioSpec desired{}, obtained{};
    desired.freq = XAUDIO_SAMPLES_HZ;
    desired.format = AUDIO_F32SYS;
    desired.channels = surround ? XAUDIO_NUM_CHANNELS : 2;
#if APU_PULL_MODEL
    desired.samples = g_audioPresetSettings.deviceSamples;
    desired.callback = AudioDeviceCallback;
#else
    desired.samples = XAUDIO_NUM_SAMPLES;
#endif
    const char* devName = nullptr;
    int reopenIndex = -1;
#if APU_PULL_MODEL
    reopenIndex = g_reopenOutputDeviceIndex.exchange(-1, std::memory_order_relaxed);
    if (reopenIndex >= 0)
        devName = SDL_GetAudioDeviceName(reopenIndex, 0);
#endif

    if (devName != nullptr)
        LOGF("Opening routed output device: SDL index {} Android id \"{}\".", reopenIndex, devName);

    g_audioDevice = SDL_OpenAudioDevice(devName, 0, &desired, &obtained, allowedChanges);

    if (obtained.channels != 2 && obtained.channels != XAUDIO_NUM_CHANNELS) // This check may fail only when surround sound is enabled.
    {
        SDL_CloseAudioDevice(g_audioDevice);
        g_audioDevice = SDL_OpenAudioDevice(devName, 0, &desired, &obtained, 0);
    }

    if (!g_audioDevice && devName != nullptr)
    {
        LOGFN_ERROR("Failed to open routed audio device \"{}\", falling back to default: {}", devName, SDL_GetError());
        g_audioDevice = SDL_OpenAudioDevice(nullptr, 0, &desired, &obtained, allowedChanges);
        if (obtained.channels != 2 && obtained.channels != XAUDIO_NUM_CHANNELS)
        {
            SDL_CloseAudioDevice(g_audioDevice);
            g_audioDevice = SDL_OpenAudioDevice(nullptr, 0, &desired, &obtained, 0);
        }
    }

    if (!g_audioDevice)
        LOGFN_ERROR("Failed to open audio device: {}", SDL_GetError());
    else if (devName != nullptr)
        LOGFN("Audio device opened: freq {}, channels {}, samples {} (requested {}) [routed to \"{}\"]",
            obtained.freq, (int)obtained.channels, (int)obtained.samples, (int)desired.samples, devName);
    else
        LOGFN("Audio device opened: freq {}, channels {}, samples {} (requested {})",
            obtained.freq, (int)obtained.channels, (int)obtained.samples, (int)desired.samples);

    g_downMixToStereo = (obtained.channels == 2);

#if APU_PULL_MODEL
    {
        SDL_LockAudioDevice(g_audioDevice);
        const size_t frameBytes = FrameBytes();
        const size_t fifoCapacity = FIFO_CAPACITY_FRAMES * frameBytes;

        if (preserveFifo && savedFifoUsed > 0 && savedFrameBytes == frameBytes)
        {
            g_fifo = std::move(savedFifo);
            if (g_fifo.size() < fifoCapacity)
                g_fifo.resize(fifoCapacity);
            g_fifoHead = savedFifoHead;
            g_fifoUsed = savedFifoUsed;
            g_preserveFifoOnReopen.store(false, std::memory_order_relaxed);
        }
        else
        {
            g_fifo.assign(fifoCapacity, 0);
            g_fifoHead = 0;
            g_fifoUsed = 0;
            g_preserveFifoOnReopen.store(false, std::memory_order_relaxed);
        }
        SDL_UnlockAudioDevice(g_audioDevice);

        if (g_clientCallback != nullptr && !g_deferUnpauseUntilPrefilled.load(std::memory_order_relaxed) &&
            !g_appInBackground.load(std::memory_order_relaxed))
        {
            SDL_PauseAudioDevice(g_audioDevice, 0);
        }

        g_firstDeviceOpenTime.store(MonotonicNanos(), std::memory_order_relaxed);
    }
#endif
}

void XAudioInitializeSystem()
{
#ifdef _WIN32
    // Force wasapi on Windows.
    SDL_setenv("SDL_AUDIODRIVER", "wasapi", true);
#endif
    SDL_SetHint(SDL_HINT_AUDIO_CATEGORY, "playback");
    SDL_SetHint(SDL_HINT_AUDIO_DEVICE_APP_NAME, "Unleashed Recompiled");

    g_audioPresetSettings = ResolveAudioPresetSettings();
#ifdef __ANDROID__
    SDL_SetHint("UNLEASHED_AAUDIO_LOW_LATENCY", g_audioPresetSettings.useAAudioLowLatency ? "1" : "0");
#endif

    if (SDL_InitSubSystem(SDL_INIT_AUDIO) < 0)
    {
        LOGFN_ERROR("Failed to init audio subsystem: {}", SDL_GetError());
        return;
    }

    LOGF("SDL audio driver: \"{}\".", SDL_GetCurrentAudioDriver());

    LOGF("Audio preset: {} (device samples {}, cushion {} frames / {} ms, route prefill {} frames, AAudio low latency {}).",
        g_audioPresetSettings.name, g_audioPresetSettings.deviceSamples,
        g_audioPresetSettings.cushionFrames,
        g_audioPresetSettings.cushionFrames * XAUDIO_NUM_SAMPLES * 1000 / XAUDIO_SAMPLES_HZ,
        g_audioPresetSettings.routePrefillFrames,
        g_audioPresetSettings.useAAudioLowLatency ? "on" : "off");

#if APU_PULL_MODEL
    g_audioSystemInitTime.store(MonotonicNanos(), std::memory_order_relaxed);
    SDL_EventState(SDL_AUDIODEVICEADDED, SDL_ENABLE);
    SDL_EventState(SDL_AUDIODEVICEREMOVED, SDL_ENABLE);
    SDL_AddEventWatch(AudioHotplugWatch, nullptr);
    SDL_AddEventWatch(AppLifecycleWatch, nullptr);
#endif

#ifdef __ANDROID__
    std::thread(AndroidWindowWatcherThread).detach();
#endif

    CreateAudioDevice();
}

#if !APU_PULL_MODEL

static std::unique_ptr<std::thread> g_audioThread;
static volatile bool g_audioThreadShouldExit;

static void AudioThread()
{
    using namespace std::chrono_literals;

    GuestThreadContext ctx(0);

    size_t channels = g_downMixToStereo ? 2 : XAUDIO_NUM_CHANNELS;

    while (!g_audioThreadShouldExit)
    {
        uint32_t queuedAudioSize = SDL_GetQueuedAudioSize(g_audioDevice);
        constexpr size_t MAX_LATENCY = 10;
        const size_t callbackAudioSize = channels * XAUDIO_NUM_SAMPLES * sizeof(float);

        if ((queuedAudioSize / callbackAudioSize) <= MAX_LATENCY)
        {
            ctx.ppcContext.r3.u32 = g_clientCallbackParam;
            g_clientCallback(ctx.ppcContext, g_memory.base);
        }

        auto now = std::chrono::steady_clock::now();
        constexpr auto INTERVAL = 1000000000ns * XAUDIO_NUM_SAMPLES / XAUDIO_SAMPLES_HZ;
        auto next = now + (INTERVAL - now.time_since_epoch() % INTERVAL);

        std::this_thread::sleep_for(std::chrono::floor<std::chrono::milliseconds>(next - now));

        while (std::chrono::steady_clock::now() < next)
            std::this_thread::yield();
    }
}

#endif

static void CreateAudioThread()
{
    if (!g_deferUnpauseUntilPrefilled.load(std::memory_order_relaxed) &&
        !g_appInBackground.load(std::memory_order_relaxed))
    {
        SDL_PauseAudioDevice(g_audioDevice, 0);
    }
    g_audioThreadShouldExit = false;
    g_audioThread = std::make_unique<std::thread>(AudioThread);
}

void XAudioRegisterClient(PPCFunc* callback, uint32_t param)
{
    auto* pClientParam = static_cast<uint32_t*>(g_userHeap.Alloc(sizeof(param)));
    ByteSwapInplace(param);
    *pClientParam = param;
    g_clientCallbackParam = g_memory.MapVirtual(pClientParam);
    g_clientCallback = callback;

    CreateAudioThread();
}

void XAudioSubmitFrame(void* samples)
{
    auto floatSamples = reinterpret_cast<be<float>*>(samples);

    if (g_downMixToStereo)
    {
        // 0: left 1.0f, right 0.0f
        // 1: left 0.0f, right 1.0f
        // 2: left 0.75f, right 0.75f
        // 3: left 0.0f, right 0.0f
        // 4: left 1.0f, right 0.0f
        // 5: left 0.0f, right 1.0f

        std::array<float, 2 * XAUDIO_NUM_SAMPLES> audioFrames;

        for (size_t i = 0; i < XAUDIO_NUM_SAMPLES; i++)
        {
            float ch0 = floatSamples[0 * XAUDIO_NUM_SAMPLES + i];
            float ch1 = floatSamples[1 * XAUDIO_NUM_SAMPLES + i];
            float ch2 = floatSamples[2 * XAUDIO_NUM_SAMPLES + i];
            float ch3 = floatSamples[3 * XAUDIO_NUM_SAMPLES + i];
            float ch4 = floatSamples[4 * XAUDIO_NUM_SAMPLES + i];
            float ch5 = floatSamples[5 * XAUDIO_NUM_SAMPLES + i];

            audioFrames[i * 2 + 0] = (ch0 + ch2 * 0.75f + ch4) * Config::MasterVolume;
            audioFrames[i * 2 + 1] = (ch1 + ch2 * 0.75f + ch5) * Config::MasterVolume;
        }

#if APU_PULL_MODEL
        g_lastCallSubmitted = true;
        SDL_LockAudioDevice(g_audioDevice);
        if (g_fifo.size() - g_fifoUsed >= sizeof(audioFrames))
            FifoWrite(reinterpret_cast<const uint8_t*>(audioFrames.data()), sizeof(audioFrames));
        // else: device stalled and the ring is full - drop the frame, the clock matters more.
        SDL_UnlockAudioDevice(g_audioDevice);
#else
        SDL_QueueAudio(g_audioDevice, &audioFrames, sizeof(audioFrames));
#endif
    }
    else
    {
        std::array<float, XAUDIO_NUM_CHANNELS * XAUDIO_NUM_SAMPLES> audioFrames;

        for (size_t i = 0; i < XAUDIO_NUM_SAMPLES; i++)
        {
            for (size_t j = 0; j < XAUDIO_NUM_CHANNELS; j++)
                audioFrames[i * XAUDIO_NUM_CHANNELS + j] = floatSamples[j * XAUDIO_NUM_SAMPLES + i] * Config::MasterVolume;
        }

#if APU_PULL_MODEL
        g_lastCallSubmitted = true;
        SDL_LockAudioDevice(g_audioDevice);
        if (g_fifo.size() - g_fifoUsed >= sizeof(audioFrames))
            FifoWrite(reinterpret_cast<const uint8_t*>(audioFrames.data()), sizeof(audioFrames));
        SDL_UnlockAudioDevice(g_audioDevice);
#else
        SDL_QueueAudio(g_audioDevice, &audioFrames, sizeof(audioFrames));
#endif
    }
}
