#include "page_watch.h"

#include <os/logger.h>

#include <atomic>
#include <cinttypes>
#include <cstdio>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>

// The armed page address (page-aligned) and the exact watched field. Zero = disarmed.
// The crash handler reads these lock-free; arming uses a CAS so only one page is
// watched at a time.
static std::atomic<uintptr_t> s_watchPage{ 0 };
static std::atomic<uintptr_t> s_watchExact{ 0 };
static std::atomic<uint32_t> s_hitCount{ 0 };
static std::atomic<uint32_t> s_armCount{ 0 };

static constexpr uint32_t MAX_LOGGED_HITS = 64;
static constexpr uint32_t MAX_ARMS = 512;

// libmain.so load base, cached in normal context so the signal handler can log
// module-relative pcs without calling dladdr (not async-signal-safe).
static std::atomic<uintptr_t> s_moduleBase{ 0 };

namespace os::android
{

void ArmPageWatch(void* hostAddress, void* exactAddress)
{
    if (s_armCount.fetch_add(1, std::memory_order_relaxed) >= MAX_ARMS)
        return;

    if (s_moduleBase.load(std::memory_order_relaxed) == 0)
    {
        Dl_info info{};
        if (dladdr(reinterpret_cast<void*>(&ArmPageWatch), &info) != 0)
            s_moduleBase.store(reinterpret_cast<uintptr_t>(info.dli_fbase), std::memory_order_relaxed);
    }

    const uintptr_t page = reinterpret_cast<uintptr_t>(hostAddress) & ~uintptr_t(4095);

    uintptr_t expected = s_watchPage.load(std::memory_order_relaxed);
    if (expected == page)
    {
        // Same page: refresh protection (a hit may have restored RW).
        s_watchExact.store(reinterpret_cast<uintptr_t>(exactAddress), std::memory_order_relaxed);
        mprotect(reinterpret_cast<void*>(page), 4096, PROT_READ);
        return;
    }

    if (expected != 0)
        return; // another page is being watched; keep it

    if (!s_watchPage.compare_exchange_strong(expected, page, std::memory_order_acq_rel))
        return;

    s_watchExact.store(reinterpret_cast<uintptr_t>(exactAddress), std::memory_order_relaxed);
    if (mprotect(reinterpret_cast<void*>(page), 4096, PROT_READ) == 0)
    {
        LOGF_ERROR("PageWatch: armed write trap on host page {:X} (exact field {:X}).",
            page, reinterpret_cast<uintptr_t>(exactAddress));
    }
    else
    {
        s_watchPage.store(0, std::memory_order_release);
    }
}

bool PageWatchHandleFault(void* faultAddress, uint64_t pc, uint64_t lr, int tid)
{
    const uintptr_t page = s_watchPage.load(std::memory_order_acquire);
    if (page == 0)
        return false;

    const uintptr_t fault = reinterpret_cast<uintptr_t>(faultAddress);
    if (fault < page || fault >= page + 4096)
        return false;

    // Restore access first so the faulting instruction can complete on return.
    mprotect(reinterpret_cast<void*>(page), 4096, PROT_READ | PROT_WRITE);

    const uint32_t hit = s_hitCount.fetch_add(1, std::memory_order_relaxed);
    if (hit < MAX_LOGGED_HITS)
    {
        // Async-signal-safe logging: plain snprintf + write to stderr, which the
        // logger already mirrors into log.txt via the stderr pipe thread.
        const uintptr_t exact = s_watchExact.load(std::memory_order_relaxed);
        const uintptr_t moduleBase = s_moduleBase.load(std::memory_order_relaxed);
        const uint64_t pcOff = (moduleBase != 0 && pc >= moduleBase) ? pc - moduleBase : 0;
        const uint64_t lrOff = (moduleBase != 0 && lr >= moduleBase) ? lr - moduleBase : 0;
        char line[256];
        int n = snprintf(line, sizeof(line),
            "PAGEWATCH HIT%s: tid=%d writes %" PRIxPTR " (page+0x%03x) pc=libmain+0x%" PRIx64 " lr=libmain+0x%" PRIx64 "\n",
            fault <= exact && exact < fault + 16 ? " (EXACT)" : "",
            tid, fault, unsigned(fault & 4095), pcOff, lrOff);
        if (n > 0)
            write(STDERR_FILENO, line, size_t(n));
    }

    return true;
}

} // namespace os::android
