#pragma once

#include <kernel/xdm.h>

// Use pthreads directly on macOS to be able to increase default stack size.
#ifdef __APPLE__
#define USE_PTHREAD 1
#endif

#ifdef USE_PTHREAD
#include <pthread.h>
#endif

#define CURRENT_THREAD_HANDLE uint32_t(-2)

struct GuestThreadContext
{
    PPCContext ppcContext{};
    uint8_t* thread = nullptr;
    size_t allocSize = 0;

    GuestThreadContext(uint32_t cpuNumber, uint32_t stackSize = 0);
    ~GuestThreadContext();
};

struct GuestThreadParams
{
    uint32_t function;
    uint32_t value;
    uint32_t flags;
    // Guest stack size requested through ExCreateThread; 0 selects the default.
    // Ignoring it used to force every thread onto a fixed 256 KiB stack carved
    // from the shared user heap, so any deeper thread silently overran into
    // neighbouring allocations (issue #27).
    uint32_t stackSize = 0;
};

struct GuestThreadHandle : KernelObject
{
    GuestThreadParams params;
    std::atomic<bool> suspended;
#ifdef USE_PTHREAD
    pthread_t thread;
#else
    std::thread thread;
#endif

    GuestThreadHandle(const GuestThreadParams& params);
    ~GuestThreadHandle() override;

    uint32_t GetThreadId() const;

    uint32_t Wait(uint32_t timeout) override;
};

struct GuestThread
{
    static uint32_t Start(const GuestThreadParams& params);
    static GuestThreadHandle* Start(const GuestThreadParams& params, uint32_t* threadId);

    static uint32_t GetCurrentThreadId();
    static void SetLastError(uint32_t error);

    // Logs an error for every live guest stack whose bottom canary was overwritten.
    // Called periodically by the Android log watchdog.
    static void CheckStackCanaries();

#ifdef _WIN32
    static void SetThreadName(uint32_t threadId, const char* name);
#endif
};
