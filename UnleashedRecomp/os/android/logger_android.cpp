#include <os/logger.h>

#include <cpu/guest_thread.h>
#include <os/android/page_watch.h>
#include <os/android/storage_android.h>

#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <dlfcn.h>
#include <filesystem>
#include <mutex>
#include <pthread.h>
#include <signal.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <ucontext.h>
#include <unistd.h>

#define ANDROID_LOG_TAG "UnleashedRecomp"

// ---------------------------------------------------------------------------
// Persistent on-device log file
// ---------------------------------------------------------------------------
// Hangs on this port leave no crash artifact - the process simply stops making
// progress - and on some tester devices logcat is drowned out by driver debug
// spam within seconds, so it can't be relied on to capture the moment of the
// freeze. Mirror every log line, plus captured stderr (where plume/Turnip print
// Vulkan errors and GPU-fault messages), into a plain text file on external app
// storage that a tester can copy off over MTP with no root and no adb:
//   Android/data/<pkg>/files/log.txt   (next to driver_import/)

static std::mutex s_logMutex;
static FILE* s_logFile = nullptr;
static bool s_logFileOpenAttempted = false;

// Raw descriptor of log.txt for the crash handler, which cannot take s_logMutex or use
// stdio. The stream is unbuffered, so raw write() interleaves at line granularity.
static std::atomic<int> s_logRawFd{ -1 };

static int GetTid()
{
    return static_cast<int>(syscall(SYS_gettid));
}

static double MonotonicSeconds()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return double(ts.tv_sec) + double(ts.tv_nsec) / 1e9;
}

static const double s_startSeconds = MonotonicSeconds();

// Caller must hold s_logMutex. Opens the file lazily because external storage can
// only be resolved once SDL/JNI is up, which is later than the first log lines.
static FILE* GetLogFileLocked()
{
    if (s_logFile != nullptr || s_logFileOpenAttempted)
        return s_logFile;

    const std::filesystem::path& dir = os::android::GetExternalFilesDir();
    if (dir.empty())
        return nullptr; // not ready yet - try again on the next log line

    s_logFileOpenAttempted = true;

    // Keep the previous run's log for one generation: a tester may relaunch before
    // copying the file that captured the freeze.
    std::error_code ec;
    std::filesystem::path path = dir / "log.txt";
    if (std::filesystem::exists(path, ec))
        std::filesystem::rename(path, dir / "log_prev.txt", ec);

    s_logFile = fopen(path.c_str(), "wb");
    if (s_logFile != nullptr)
    {
        setvbuf(s_logFile, nullptr, _IONBF, 0); // unbuffered: a hang/kill keeps the tail
        s_logRawFd.store(fileno(s_logFile), std::memory_order_release);
    }

    return s_logFile;
}

// Writes one "[+seconds][tTID]<tag>[func] message" line to the log file.
static void WriteLogRecord(const char* tag, const char* func, const char* msg, size_t msgLen)
{
    char prefix[80];
    int plen = snprintf(prefix, sizeof(prefix), "[%9.3f][t%d]%s",
        MonotonicSeconds() - s_startSeconds, GetTid(), tag != nullptr ? tag : "");
    if (plen < 0)
        plen = 0;
    else if (plen > (int)sizeof(prefix))
        plen = (int)sizeof(prefix);

    std::lock_guard<std::mutex> lock(s_logMutex);
    FILE* file = GetLogFileLocked();
    if (file == nullptr)
        return;

    fwrite(prefix, 1, size_t(plen), file);
    if (func != nullptr)
    {
        fputc('[', file);
        fwrite(func, 1, strlen(func), file);
        fputc(']', file);
    }
    fputc(' ', file);
    if (msg != nullptr && msgLen > 0)
        fwrite(msg, 1, msgLen, file);
    fputc('\n', file);
}

// ---------------------------------------------------------------------------
// stderr -> logcat + log file
// ---------------------------------------------------------------------------
// plume and other thirdparty code report Vulkan errors via fprintf(stderr, ...),
// which is otherwise discarded on Android. Redirect the stderr file descriptor
// through a pipe so those messages reach both logcat and log.txt.
static int s_stderrPipeReadFd = -1;

static void* StderrToLogcatThread(void*)
{
    char buffer[1024];
    ssize_t bytesRead;
    while ((bytesRead = read(s_stderrPipeReadFd, buffer, sizeof(buffer) - 1)) > 0)
    {
        if (buffer[bytesRead - 1] == '\n')
            --bytesRead;

        buffer[bytesRead] = '\0';
        __android_log_write(ANDROID_LOG_ERROR, "stderr", buffer);
        WriteLogRecord("[stderr]", nullptr, buffer, size_t(bytesRead));
    }

    return nullptr;
}

// ---------------------------------------------------------------------------
// Hang watchdog
// ---------------------------------------------------------------------------
// Present() pings Heartbeat() every frame. A dedicated thread watches that ping:
// if frames stop for HANG_THRESHOLD seconds it records a thread dump built from
// /proc/self/task/* (name + scheduler state + kernel wait channel), so a freeze
// with no adb access still tells us which thread is stuck and where - separating a
// GPU/driver hang (render thread blocked in an ioctl/fence) from a guest-side
// deadlock (a thread spinning or parked on a futex).

static std::atomic<double> s_lastHeartbeat{ 0.0 };
static std::atomic<uint64_t> s_frameCount{ 0 };
static std::atomic<bool> s_watchdogSuspended{ false };
static std::once_flag s_watchdogOnce;

static void ReadProcFileTrimmed(const char* path, char* out, size_t outSize)
{
    out[0] = '\0';
    FILE* file = fopen(path, "rb");
    if (file == nullptr)
        return;

    size_t n = fread(out, 1, outSize - 1, file);
    fclose(file);
    while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r' || out[n - 1] == ' '))
        --n;
    out[n] = '\0';
}

static void DumpThreads()
{
    DIR* dir = opendir("/proc/self/task");
    if (dir == nullptr)
    {
        WriteLogRecord("[watchdog]", nullptr, "cannot open /proc/self/task", 27);
        return;
    }

    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr)
    {
        if (entry->d_name[0] == '.')
            continue;

        char path[128];
        char comm[64];
        char wchan[128];

        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);
        ReadProcFileTrimmed(path, comm, sizeof(comm));

        snprintf(path, sizeof(path), "/proc/self/task/%s/wchan", entry->d_name);
        ReadProcFileTrimmed(path, wchan, sizeof(wchan));
        if (wchan[0] == '\0')
            strcpy(wchan, "0");

        // Scheduler state is the char right after the "(comm)" field in stat.
        char state = '?';
        snprintf(path, sizeof(path), "/proc/self/task/%s/stat", entry->d_name);
        char stat[256];
        ReadProcFileTrimmed(path, stat, sizeof(stat));
        char* lastParen = strrchr(stat, ')');
        if (lastParen != nullptr && lastParen[1] == ' ')
            state = lastParen[2];

        char line[320];
        int m = snprintf(line, sizeof(line), "tid %s [%s] state=%c wchan=%s",
            entry->d_name, comm, state, wchan);
        WriteLogRecord("[watchdog]", nullptr, line, m > 0 ? size_t(m) : 0);
    }

    closedir(dir);
}

static void* WatchdogThread(void*)
{
    constexpr double HANG_THRESHOLD = 5.0;   // no presented frame for this long => hang
    constexpr double ALIVE_INTERVAL = 5.0;   // otherwise note liveness this often
    constexpr double REDUMP_INTERVAL = 15.0; // while still hung, re-dump this often

    bool hung = false;
    double lastAliveLog = 0.0;
    double lastDump = 0.0;

    for (;;)
    {
        usleep(1000 * 1000); // 1s

        // The game is intentionally frozen (backgrounded); missing frames are not a hang.
        if (s_watchdogSuspended.load(std::memory_order_relaxed))
            continue;

        // Catch guest stacks that silently overran into the user heap (issue #27).
        GuestThread::CheckStackCanaries();

        const double now = MonotonicSeconds() - s_startSeconds;
        const double last = s_lastHeartbeat.load(std::memory_order_relaxed);
        const uint64_t frames = s_frameCount.load(std::memory_order_relaxed);
        const double sinceFrame = now - last;

        if (sinceFrame > HANG_THRESHOLD)
        {
            if (!hung)
            {
                char line[192];
                int m = snprintf(line, sizeof(line),
                    "HANG DETECTED: no frame presented for %.1fs (last frame #%llu at +%.3fs). Thread dump follows:",
                    sinceFrame, (unsigned long long)frames, last);
                WriteLogRecord("[watchdog]", nullptr, line, m > 0 ? size_t(m) : 0);
                DumpThreads();
                hung = true;
                lastDump = now;
            }
            else if (now - lastDump >= REDUMP_INTERVAL)
            {
                WriteLogRecord("[watchdog]", nullptr, "still hung, thread dump follows:", 32);
                DumpThreads();
                lastDump = now;
            }
        }
        else
        {
            if (hung)
            {
                char line[96];
                int m = snprintf(line, sizeof(line), "RESUMED at frame #%llu (was stalled)",
                    (unsigned long long)frames);
                WriteLogRecord("[watchdog]", nullptr, line, m > 0 ? size_t(m) : 0);
                hung = false;
            }

            if (now - lastAliveLog >= ALIVE_INTERVAL)
            {
                char line[96];
                int m = snprintf(line, sizeof(line), "alive: frame #%llu",
                    (unsigned long long)frames);
                WriteLogRecord("[heartbeat]", nullptr, line, m > 0 ? size_t(m) : 0);
                lastAliveLog = now;
            }
        }
    }

    return nullptr;
}

// ---------------------------------------------------------------------------
// Crash reporter
// ---------------------------------------------------------------------------
// Tombstones land in /data/tombstones, which testers cannot read without root, and
// half the crash issues arrive with nothing but "it crashed". Catch fatal signals and
// append the signal, fault address and PC/LR (resolved to module+offset via dladdr)
// to log.txt with only async-signal-safe raw write()s, then re-raise into the previous
// handler so the system tombstone/debuggerd flow still runs.

static struct sigaction s_previousCrashActions[NSIG];

static void CrashWriteRaw(int fd, const char* str)
{
    size_t len = strlen(str);
    while (len > 0)
    {
        const ssize_t written = write(fd, str, len);
        if (written <= 0)
            return;

        str += written;
        len -= size_t(written);
    }
}

static void CrashWriteHex(int fd, uint64_t value)
{
    char buffer[19];
    char* p = buffer + sizeof(buffer);
    *--p = '\0';
    do
    {
        *--p = "0123456789abcdef"[value & 0xF];
        value >>= 4;
    } while (value != 0);
    *--p = 'x';
    *--p = '0';
    CrashWriteRaw(fd, p);
}

static void CrashWriteDec(int fd, uint64_t value)
{
    char buffer[21];
    char* p = buffer + sizeof(buffer);
    *--p = '\0';
    do
    {
        *--p = char('0' + value % 10);
        value /= 10;
    } while (value != 0);
    CrashWriteRaw(fd, p);
}

// dladdr is not formally async-signal-safe, but bionic's implementation only walks
// already-loaded soinfo structures without allocating; debuggerd relies on the same.
static void CrashWriteAddress(int fd, const char* label, uint64_t address)
{
    CrashWriteRaw(fd, label);
    CrashWriteHex(fd, address);

    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(address), &info) != 0 && info.dli_fname != nullptr)
    {
        const char* baseName = strrchr(info.dli_fname, '/');
        CrashWriteRaw(fd, " (");
        CrashWriteRaw(fd, baseName != nullptr ? baseName + 1 : info.dli_fname);
        CrashWriteRaw(fd, "+");
        CrashWriteHex(fd, address - reinterpret_cast<uint64_t>(info.dli_fbase));
        CrashWriteRaw(fd, ")");
    }
}

static void CrashSignalHandler(int signal, siginfo_t* info, void* contextPtr)
{
#if defined(__aarch64__)
    // Software watchpoint (issue #27): a write fault on the armed page is logged
    // with the writer's pc and execution continues; it is not a crash.
    if (signal == SIGSEGV && info != nullptr && contextPtr != nullptr)
    {
        const ucontext_t* watchContext = static_cast<const ucontext_t*>(contextPtr);
        if (os::android::PageWatchHandleFault(info->si_addr,
                watchContext->uc_mcontext.pc, watchContext->uc_mcontext.regs[30], GetTid()))
        {
            return;
        }
    }
#endif

    const int fd = s_logRawFd.load(std::memory_order_acquire);
    if (fd >= 0)
    {
        CrashWriteRaw(fd, "[crash] FATAL SIGNAL ");
        CrashWriteDec(fd, uint64_t(signal));
        switch (signal)
        {
            case SIGSEGV: CrashWriteRaw(fd, " (SIGSEGV)"); break;
            case SIGABRT: CrashWriteRaw(fd, " (SIGABRT)"); break;
            case SIGBUS:  CrashWriteRaw(fd, " (SIGBUS)"); break;
            case SIGILL:  CrashWriteRaw(fd, " (SIGILL)"); break;
            case SIGFPE:  CrashWriteRaw(fd, " (SIGFPE)"); break;
            case SIGTRAP: CrashWriteRaw(fd, " (SIGTRAP)"); break;
        }

        CrashWriteRaw(fd, " code=");
        CrashWriteDec(fd, uint64_t(info != nullptr ? info->si_code : 0));
        CrashWriteRaw(fd, " tid=");
        CrashWriteDec(fd, uint64_t(GetTid()));
        if (info != nullptr && (signal == SIGSEGV || signal == SIGBUS))
        {
            CrashWriteRaw(fd, " fault_addr=");
            CrashWriteHex(fd, reinterpret_cast<uint64_t>(info->si_addr));
        }
        CrashWriteRaw(fd, "\n");

#if defined(__aarch64__)
        const ucontext_t* context = static_cast<const ucontext_t*>(contextPtr);
        if (context != nullptr)
        {
            CrashWriteRaw(fd, "[crash]");
            CrashWriteAddress(fd, " pc=", context->uc_mcontext.pc);
            CrashWriteAddress(fd, " lr=", context->uc_mcontext.regs[30]);
            CrashWriteRaw(fd, " sp=");
            CrashWriteHex(fd, context->uc_mcontext.sp);
            CrashWriteRaw(fd, "\n");
        }
#endif

        CrashWriteRaw(fd, "[crash] end of report; the system tombstone (if any) has the full backtrace.\n");
    }

    // Restore and re-raise so debuggerd still produces the real tombstone.
    sigaction(signal, &s_previousCrashActions[signal], nullptr);
    raise(signal);
}

static void InstallCrashHandler()
{
    static uint8_t altStack[SIGSTKSZ * 2];
    stack_t stack{};
    stack.ss_sp = altStack;
    stack.ss_size = sizeof(altStack);
    sigaltstack(&stack, nullptr);

    struct sigaction action{};
    action.sa_sigaction = CrashSignalHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);

    for (const int signal : { SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP })
        sigaction(signal, &action, &s_previousCrashActions[signal]);
}

// ---------------------------------------------------------------------------
// Device info header
// ---------------------------------------------------------------------------
// Many crash reports arrive without device details ("crashes after moving"). Put the
// model, SoC, Android version, GPU HAL and RAM at the top of every log so a bare
// log.txt is enough to triage which hardware family the report belongs to.

static void LogDeviceInfo()
{
    const char* properties[][2] =
    {
        { "device", "ro.product.model" },
        { "brand", "ro.product.manufacturer" },
        { "soc", "ro.soc.model" },
        { "android", "ro.build.version.release" },
        { "sdk", "ro.build.version.sdk" },
        { "vulkan_hal", "ro.hardware.vulkan" },
        { "egl_hal", "ro.hardware.egl" },
        { "abi", "ro.product.cpu.abi" },
    };

    char line[512];
    int offset = 0;
    for (const auto& [label, property] : properties)
    {
        char value[PROP_VALUE_MAX]{};
        __system_property_get(property, value);
        offset += snprintf(line + offset, sizeof(line) - size_t(offset), "%s%s=%s",
            offset > 0 ? " " : "", label, value[0] != '\0' ? value : "?");
        if (offset >= int(sizeof(line)))
            break;
    }

    if (offset < int(sizeof(line)))
    {
        const long pages = sysconf(_SC_PHYS_PAGES);
        const long pageSize = sysconf(_SC_PAGE_SIZE);
        snprintf(line + offset, sizeof(line) - size_t(offset), " ram_mb=%lld",
            (long long)(pages) * pageSize / (1024 * 1024));
    }

    WriteLogRecord("[device]", nullptr, line, strlen(line));
}

// ---------------------------------------------------------------------------
// os::logger interface
// ---------------------------------------------------------------------------

void os::logger::Init()
{
    int pipeFds[2];
    if (pipe(pipeFds) != 0)
        return;

    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(pipeFds[1], STDERR_FILENO);
    close(pipeFds[1]);

    s_stderrPipeReadFd = pipeFds[0];

    pthread_t thread;
    pthread_create(&thread, nullptr, StderrToLogcatThread, nullptr);
    pthread_detach(thread);

    // Create log.txt promptly (and roll the previous one) so a tester always finds a
    // fresh file, even if this run happens to log nothing else before a freeze.
    WriteLogRecord("[logger]", nullptr, "Unleashed Recomp log started", 28);
    static constexpr char BuildVersion[] = "=== APK VERSION: 1.5.1-roadmap-v37 (2026-07-12) ===";
    static constexpr char BuildId[] = "ANDROID_BUILD_ID=1.5.1-roadmap-v37-diag10-clonefix";
    WriteLogRecord("[build]", nullptr, BuildVersion, sizeof(BuildVersion) - 1);
    WriteLogRecord("[build]", nullptr, BuildId, sizeof(BuildId) - 1);
    LogDeviceInfo();
    InstallCrashHandler();
}

void os::logger::Log(const std::string_view str, ELogType type, const char* func)
{
    android_LogPriority priority = ANDROID_LOG_INFO;
    const char* fileTag = "";
    switch (type)
    {
    case ELogType::Warning:
        priority = ANDROID_LOG_WARN;
        fileTag = "[warn]";
        break;
    case ELogType::Error:
        priority = ANDROID_LOG_ERROR;
        fileTag = "[error]";
        break;
    default:
        break;
    }

    if (func)
    {
        __android_log_print(priority, ANDROID_LOG_TAG, "[%s] %.*s", func, (int)(str.size()), str.data());
    }
    else
    {
        __android_log_print(priority, ANDROID_LOG_TAG, "%.*s", (int)(str.size()), str.data());
    }

    WriteLogRecord(fileTag, func, str.data(), str.size());
}

void os::logger::SetWatchdogSuspended(bool suspended)
{
    if (!suspended)
    {
        // Fresh grace period so the frames missed while frozen don't read as a hang.
        s_lastHeartbeat.store(MonotonicSeconds() - s_startSeconds, std::memory_order_relaxed);
    }

    s_watchdogSuspended.store(suspended, std::memory_order_relaxed);
}

void os::logger::Heartbeat()
{
    s_lastHeartbeat.store(MonotonicSeconds() - s_startSeconds, std::memory_order_relaxed);
    s_frameCount.fetch_add(1, std::memory_order_relaxed);

    // Start the watchdog only once frames are actually being presented, so the long,
    // frame-less startup/first-load phase can't trip a false hang.
    std::call_once(s_watchdogOnce, []()
    {
        pthread_t thread;
        if (pthread_create(&thread, nullptr, WatchdogThread, nullptr) == 0)
            pthread_detach(thread);
    });
}
