#include <stdafx.h>
#include "memory.h"
#include <os/logger.h>

#include <atomic>

Memory::Memory()
{
#ifdef _WIN32
    base = (uint8_t*)VirtualAlloc((void*)0x100000000ull, PPC_MEMORY_SIZE, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);

    if (base == nullptr)
        base = (uint8_t*)VirtualAlloc(nullptr, PPC_MEMORY_SIZE, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);

    if (base == nullptr)
        return;

    DWORD oldProtect;
    VirtualProtect(base, 4096, PAGE_NOACCESS, &oldProtect);
#else
    base = (uint8_t*)mmap((void*)0x100000000ull, PPC_MEMORY_SIZE, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE, -1, 0);

    if (base == (uint8_t*)MAP_FAILED)
        base = (uint8_t*)mmap(NULL, PPC_MEMORY_SIZE, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE, -1, 0);

    if (base == nullptr)
        return;

#ifdef __ANDROID__
    // Keep the guest null page readable and writable. On some devices the game
    // evaluates a half-constructed animation subtree whose data pointers are 0/-1
    // (issue #27, 100% on Snapdragon 8 Gen 2 handhelds); testers confirmed the
    // reads are benign (zeros) with no visual artifacts, while faulting made the
    // game unplayable there. Indirect calls fetched through such zeros are guarded
    // separately in ppc_detail.h. Desktop builds keep the trap to catch new bugs.

    // Absorber region past the arena end. Destructors of the issue #27 objects
    // leave 0xFFFFFFFF in pointer fields; guest code that races object teardown
    // dereferences them with positive offsets, and recompiled 64-bit address
    // arithmetic (base + 0xFFFFFFFF + offset) lands past the 4GB arena instead
    // of wrapping like the 360 did. Map 16MB of anonymous RW right after the
    // arena so those accesses read zeros / scribble into a scratch area instead
    // of faulting; the pages cost nothing until touched.
    if (base != nullptr)
    {
        void* absorber = mmap(base + PPC_MEMORY_SIZE, 16ull * 1024 * 1024,
            PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE | MAP_FIXED_NOREPLACE, -1, 0);
        if (absorber == MAP_FAILED)
            LOG_ERROR("Failed to map the guest arena absorber region; -1-based accesses will fault.");
    }
#else
    mprotect(base, 4096, PROT_NONE);
#endif
#endif

    for (size_t i = 0; PPCFuncMappings[i].guest != 0; i++)
    {
        if (PPCFuncMappings[i].host != nullptr)
            InsertFunction(PPCFuncMappings[i].guest, PPCFuncMappings[i].host);
    }
}

void* MmGetHostAddress(uint32_t ptr)
{
    return g_memory.Translate(ptr);
}

// Called from the hardened PPC_CALL_INDIRECT_FUNC (UnleashedRecompLib/ppc/ppc_detail.h)
// when an indirect call's target is outside the recompiled code range or resolves to no
// host function - a wild jump the process could never survive. Skipping the call keeps
// the half-constructed-state races of issue #27 non-fatal; the log keeps them visible.
extern "C" void PPCIndirectCallMissing(PPCContext& ctx, uint8_t* base, uint32_t target)
{
    static std::atomic<uint32_t> s_reportCount{ 0 };
    if (s_reportCount.fetch_add(1, std::memory_order_relaxed) < 16)
    {
        LOGF_ERROR("Indirect call to unmapped guest address {:08X} skipped (r3={:08X}).",
            target, ctx.r3.u32);
    }
}
