#pragma once

#include <cstdint>

// Software watchpoint for the issue #27 corruption hunt: the animation-node guard arms
// a read-only trap on the page holding a broken node, and the SIGSEGV handler logs the
// PC of whoever writes there (the corruptor), restores access and lets execution
// continue. Diagnostic only.
namespace os::android
{
    // Makes the page containing hostAddress read-only and remembers exactAddress
    // (the field we care about) for hit classification. Re-arming an armed page is a
    // cheap no-op. Thread-safe.
    void ArmPageWatch(void* hostAddress, void* exactAddress);

    // Called from the crash handler for SIGSEGV. If the fault is a write to the
    // watched page: logs the writer's pc/lr (async-signal-safe), restores RW and
    // returns true (the faulting instruction restarts and proceeds). The next guard
    // hit re-arms the watch.
    bool PageWatchHandleFault(void* faultAddress, uint64_t pc, uint64_t lr, int tid);
}
