#include <api/SWA.h>
#include <os/logger.h>
#include <ui/game_window.h>
#include <kernel/heap.h>

#include <atomic>
#include <deque>
#include <mutex>

#ifdef __ANDROID__
#include <os/android/page_watch.h>
#include <os/android/storage_android.h>
#include <cstdlib>
#include <dlfcn.h>
#include <unistd.h>
#endif
#include <user/achievement_manager.h>
#include <user/persistent_storage_manager.h>
#include <user/config.h>

void AchievementManagerUnlockMidAsmHook(PPCRegister& id)
{
    AchievementManager::Unlock(id.u32);
}

bool DisableHintsMidAsmHook()
{
    return !Config::Hints;
}

// Disable Perfect Dark Gaia hints.
PPC_FUNC_IMPL(__imp__sub_82AC36E0);
PPC_FUNC(sub_82AC36E0)
{
    auto pPerfectDarkGaiaChipHintName = (xpointer<char>*)g_memory.Translate(0x8338EF10);

    strcpy(pPerfectDarkGaiaChipHintName->get(), Config::Hints ? "V_CHP_067\0" : "end\0");

    __imp__sub_82AC36E0(ctx, base);
}

bool DisableControlTutorialMidAsmHook()
{
    return !Config::ControlTutorial;
}

bool DisableEvilControlTutorialMidAsmHook(PPCRegister& r4, PPCRegister& r5)
{
    if (Config::ControlTutorial)
        return true;

    // Only allow enemy QTE prompts to get through.
    return r4.u32 == 1 && r5.u32 == 1;
}

bool DisableDLCIconMidAsmHook()
{
    return Config::DisableDLCIcon;
}

void WerehogBattleMusicMidAsmHook(PPCRegister& r11)
{
    if (Config::BattleTheme)
        return;

    // Swap CStateBattle for CStateNormal.
    if (r11.u8 == 4)
        r11.u8 = 3;
}

bool UseAlternateTitleMidAsmHook()
{
    auto isSWA = Config::Language == ELanguage::Japanese;

    if (Config::UseAlternateTitle)
        isSWA = !isSWA;

    return isSWA;
}

/* Hook function that gets the game region
   and force result to zero for Japanese
   to display the correct logos. */
PPC_FUNC_IMPL(__imp__sub_825197C0);
PPC_FUNC(sub_825197C0)
{
    if (Config::Language == ELanguage::Japanese)
    {
        ctx.r3.u64 = 0;
        return;
    }

    __imp__sub_825197C0(ctx, base);
}

// Logo skip
PPC_FUNC_IMPL(__imp__sub_82547DF0);
PPC_FUNC(sub_82547DF0)
{
    if (Config::SkipIntroLogos)
    {
        ctx.r4.u64 = 0;
        ctx.r5.u64 = 0;
        ctx.r6.u64 = 1;
        ctx.r7.u64 = 0;
        sub_825517C8(ctx, base);
    }
    else
    {
        __imp__sub_82547DF0(ctx, base);
    }
}

/* Ignore xercesc::EmptyStackException to
   allow DLC stages with invalid XML to load. */
PPC_FUNC_IMPL(__imp__sub_8305D5B8);
PPC_FUNC(sub_8305D5B8)
{
    auto value = PPC_LOAD_U32(ctx.r3.u32 + 4);

    if (!value)
        return;

    __imp__sub_8305D5B8(ctx, base);
}

// Disable auto save warning.
PPC_FUNC_IMPL(__imp__sub_82586698);
PPC_FUNC(sub_82586698)
{
    if (Config::DisableAutoSaveWarning)
        *(bool*)g_memory.Translate(0x83367BC2) = true;

    __imp__sub_82586698(ctx, base);
}

// SWA::CObjHint::MsgNotifyObjectEvent::Impl
// Disable only certain hints from hint volumes.
// This hook should be used to allow hint volumes specifically to also prevent them from affecting the player.
PPC_FUNC_IMPL(__imp__sub_82736E80);
PPC_FUNC(sub_82736E80)
{
    // GroupID parameter text
    auto* groupId = (const char*)(base + PPC_LOAD_U32(ctx.r3.u32 + 0x100));
    
    if (!Config::Hints)
    {
        // WhiteIsland_ACT1_001: "Your friend went off that way, Sonic. Quick, let's go after him!"
        // s20n_mykETF_c_navi_2: "Huh? Weird! We can't get through here anymore. We were able to earlier!"
        if (strcmp(groupId, "WhiteIsland_ACT1_001") != 0 && strcmp(groupId, "s20n_mykETF_c_navi_2") != 0)
            return;
    }

    __imp__sub_82736E80(ctx, base);
}

// SWA::CHelpWindow::MsgRequestHelp::Impl
// Disable only certain hints from other sequences.
// This hook should be used to block hint messages from unknown sources.
PPC_FUNC_IMPL(__imp__sub_824C1E60);
PPC_FUNC(sub_824C1E60)
{
    auto pMsgRequestHelp = (SWA::Message::MsgRequestHelp*)(base + ctx.r4.u32);

    if (!Config::Hints)
    {
        // s10d_mykETF_c_navi: "Looks like we can get to a bunch of places in the village from here!"
        if (strcmp(pMsgRequestHelp->m_Name.c_str(), "s10d_mykETF_c_navi") == 0)
            return;
    }

    __imp__sub_824C1E60(ctx, base);
}

// This function is called in various places but primarily for the boost filter
// when the second argument (r4) is set to "boost". Whilst boosting the third argument (f1)
// will go up to 1.0f and then down to 0.0f as the player lets off of the boost button.
// To avoid the boost filter from kicking in at all if the function is called with "boost"
// we set the third argument to zero no matter what (if the code is on).
PPC_FUNC_IMPL(__imp__sub_82B4DB48);
PPC_FUNC(sub_82B4DB48)
{
    if (Config::DisableBoostFilter && strcmp((const char*)(base + ctx.r4.u32), "boost") == 0)
    {
        ctx.f1.f64 = 0.0;
    }

    __imp__sub_82B4DB48(ctx, base);
}

// DLC save data flag check.
// 
// The DLC checks are fundamentally broken in this game, resulting in this method always
// returning true and displaying the DLC info message when it shouldn't be.
// 
// The original intent here seems to have been to display the message every time new DLC
// content is installed, but the flags in the save data never get written to properly,
// causing this function to always pass in some way.
//
// We bypass the save data completely and write to external persistent storage to store
// whether we've seen the DLC info message instead. This way we can retain the original
// broken game behaviour, whilst also providing a fix for this issue that is safe.
PPC_FUNC_IMPL(__imp__sub_824EE620);
PPC_FUNC(sub_824EE620)
{
    __imp__sub_824EE620(ctx, base);

    ctx.r3.u32 = PersistentStorageManager::ShouldDisplayDLCMessage(true);
}

#ifdef __ANDROID__

// issue #27 diag8: lifetime tracking for the game's small-block allocator.
//
// sub_82EA8A30 is the guest bucketed freelist allocator (Havok hkThreadMemory
// layout: per-thread instance fetched from TLS, lock-free bucket pop) and
// sub_82EA8AB0 is its free counterpart. The diag7 page watch showed a buffer
// allocated through sub_82EA8A30 (by sub_82BB5870, count*48 bytes) being
// copied right over a live animation node 16 bytes above it — i.e. the
// allocator handed out overlapping blocks. This tracker records every
// alloc/free in a ring buffer and keeps a live-block bitmap so the moment a
// block is handed out twice we log both owners, and the evaluator guard can
// print the full lifetime history of the broken node's memory.

namespace
{
    constexpr uint32_t TRACK_LIMIT = 0x20000000; // 360 had 512 MB; game heap lives below this
    constexpr size_t EVT_CAP = 1 << 16;

    struct AllocEvent
    {
        uint32_t addr;
        uint32_t size;
        uint32_t ra;  // host return address as libmain offset (symbolizable to sub_XXXXXXXX)
        uint16_t tid;
        uint8_t op;   // 1 = alloc, 2 = free
        uint8_t pad;
    };

    AllocEvent s_events[EVT_CAP];
    std::atomic<uint64_t> s_evtIdx{ 0 };

    // One bit per 8-byte granule (allocator rounds sizes to 8): 8 MB.
    std::atomic<uint64_t> s_liveBits[TRACK_LIMIT / 8 / 64];

    uintptr_t TrackModuleBase()
    {
        static uintptr_t s_base = []
        {
            Dl_info info{};
            dladdr((void*)&TrackModuleBase, &info);
            return (uintptr_t)info.dli_fbase;
        }();
        return s_base;
    }

    uint16_t TrackTid()
    {
        static thread_local uint16_t t_tid = (uint16_t)gettid();
        return t_tid;
    }

    void RecordAllocEvent(uint32_t addr, uint32_t size, uint32_t ra, uint8_t op)
    {
        uint64_t i = s_evtIdx.fetch_add(1, std::memory_order_relaxed);
        AllocEvent& e = s_events[i & (EVT_CAP - 1)];
        e.addr = addr;
        e.size = size;
        e.ra = ra;
        e.tid = TrackTid();
        e.op = op;
    }

    // Returns true when setting bits that were already set: the allocator handed
    // out memory overlapping a still-live tracked block.
    bool MarkLiveRange(uint32_t addr, uint32_t size, bool set)
    {
        if (addr == 0 || size == 0 || size > 0x100000 || addr >= TRACK_LIMIT || size > TRACK_LIMIT - addr)
            return false;

        uint32_t g0 = addr >> 3;
        uint32_t g1 = (addr + size - 1) >> 3;
        bool overlap = false;

        for (uint32_t w = g0 / 64; w <= g1 / 64; w++)
        {
            uint32_t bs = (w == g0 / 64) ? g0 % 64 : 0;
            uint32_t be = (w == g1 / 64) ? g1 % 64 : 63;
            uint64_t mask = (be == 63 ? ~0ull : ((1ull << (be + 1)) - 1)) & ~((1ull << bs) - 1);

            if (set)
            {
                if (s_liveBits[w].fetch_or(mask, std::memory_order_relaxed) & mask)
                    overlap = true;
            }
            else
            {
                s_liveBits[w].fetch_and(~mask, std::memory_order_relaxed);
            }
        }

        return overlap;
    }

    void DumpAllocHistory(uint32_t target, uint32_t range, const char* tag)
    {
        uint64_t end = s_evtIdx.load(std::memory_order_acquire);
        uint64_t begin = end > EVT_CAP ? end - EVT_CAP : 0;
        int printed = 0;

        for (uint64_t i = begin; i < end && printed < 24; i++)
        {
            const AllocEvent& e = s_events[i & (EVT_CAP - 1)];
            uint32_t sz = e.size ? e.size : 1;
            if (e.addr < target + range && target < e.addr + sz)
            {
                LOGF_ERROR("ALLOCTRACK[{}] #{} {} addr={:08X} size={} tid={} ra=libmain+0x{:X}",
                    tag, i, e.op == 1 ? "alloc" : "free ", e.addr, e.size, e.tid, e.ra);
                printed++;
            }
        }

        if (printed == 0)
            LOGF_ERROR("ALLOCTRACK[{}] no recorded events touch {:08X}", tag, target);
    }

    void TrackAlloc(uint32_t addr, uint32_t size, uint32_t ra)
    {
        RecordAllocEvent(addr, size, ra, 1);

        if (MarkLiveRange(addr, size, true))
        {
            static std::atomic<uint32_t> s_dblCount{ 0 };
            if (s_dblCount.fetch_add(1, std::memory_order_relaxed) < 16)
            {
                LOGF_ERROR("ALLOCTRACK double allocation: {:08X}+{} handed out while still live "
                    "(tid={} ra=libmain+0x{:X})", addr, size, TrackTid(), ra);
                DumpAllocHistory(addr, size, "double");
            }
        }
    }

    void TrackFree(uint32_t addr, uint32_t size, uint32_t ra)
    {
        RecordAllocEvent(addr, size, ra, 2);
        MarkLiveRange(addr, size, false);
    }

    // diag9: free quarantine. The diag8 logs proved the crash chain on every
    // affected device: a 512-byte node (factory sub_822EBF60) is destroyed by
    // its own deallocating destructor (sub_82ED4BB8) while a live parent blend
    // node still references it, and within a dozen allocator events the memory
    // is reused by sub_82BB5870's transform buffers whose float data then gets
    // read through the dangling pointer. Hold small freed blocks in a per-thread
    // FIFO before really freeing them, and poison the payload with 0xFF so any
    // dangling reader sees -1 - which the evaluator guard already treats as
    // invalid and skips deterministically. If the ring crash disappears (or its
    // frequency collapses) with this build, the use-after-free chain is proven
    // end to end.
    // All sizes this allocator serves are quarantined: tester logs kept finding
    // stale references into blocks above the previous 512-byte cutoff (the
    // count*48 transform buffers reach 13792 bytes), showing up as float data
    // overwriting live nodes and poison pointers landing in CRT lock lists.
    // A byte budget bounds the held memory per guest thread.
    constexpr size_t QUAR_CAP = 4096;
    constexpr uint32_t QUAR_MAX_SIZE = 64 * 1024;
    constexpr uint64_t QUAR_MAX_BYTES = 8ull * 1024 * 1024;

    struct QuarantinedFree
    {
        uint32_t heap;
        uint32_t addr;
        uint32_t size;
    };

    thread_local QuarantinedFree t_quarantine[QUAR_CAP];
    thread_local size_t t_quarantineHead = 0;
    thread_local size_t t_quarantineCount = 0;
    thread_local uint64_t t_quarantineBytes = 0;

    // Global quarantine membership bitmap (one bit per 8-byte granule of the
    // first 512MB, like s_liveBits). The collision-pair hooks below use it to
    // recognise pointers into destroyed objects without touching the poison.
    std::atomic<uint64_t> s_quarBits[TRACK_LIMIT / 8 / 64];

    void MarkQuarantined(uint32_t addr, uint32_t size, bool set)
    {
        if (addr == 0 || size == 0 || addr >= TRACK_LIMIT || size > TRACK_LIMIT - addr)
            return;

        uint32_t g0 = addr >> 3;
        uint32_t g1 = (addr + size - 1) >> 3;
        for (uint32_t w = g0 / 64; w <= g1 / 64; w++)
        {
            uint32_t bs = (w == g0 / 64) ? g0 % 64 : 0;
            uint32_t be = (w == g1 / 64) ? g1 % 64 : 63;
            uint64_t mask = (be == 63 ? ~0ull : ((1ull << (be + 1)) - 1)) & ~((1ull << bs) - 1);
            if (set)
                s_quarBits[w].fetch_or(mask, std::memory_order_relaxed);
            else
                s_quarBits[w].fetch_and(~mask, std::memory_order_relaxed);
        }
    }

    bool IsQuarantined(uint32_t addr)
    {
        if (addr == 0 || addr >= TRACK_LIMIT)
            return false;
        uint32_t g = addr >> 3;
        return (s_quarBits[g / 64].load(std::memory_order_relaxed) >> (g % 64)) & 1;
    }
}

// Guest small-block allocate(heap r3, size r4) -> r3.
PPC_FUNC_IMPL(__imp__sub_82EA8A30);
PPC_FUNC(sub_82EA8A30)
{
    uint32_t size = ctx.r4.u32;
    uintptr_t ra = (uintptr_t)__builtin_return_address(0);

    __imp__sub_82EA8A30(ctx, base);

    TrackAlloc(ctx.r3.u32, size, (uint32_t)(ra - TrackModuleBase()));
}

// Guest small-block free(heap r3, ptr r4, size r5).
PPC_FUNC_IMPL(__imp__sub_82EA8AB0);
PPC_FUNC(sub_82EA8AB0)
{
    uint32_t heap = ctx.r3.u32;
    uint32_t addr = ctx.r4.u32;
    uint32_t size = ctx.r5.u32;
    uintptr_t ra = (uintptr_t)__builtin_return_address(0);

    if (addr != 0)
        TrackFree(addr, size, (uint32_t)(ra - TrackModuleBase()));

    // The heap instance is per-thread (fetched from TLS by the guest code), so
    // deferring a free and completing it later from the same thread is safe.
    //
    // Poison and the arena absorber work as a pair. Without poison, freed
    // blocks keep plausible stale pointers and racing teardown code follows
    // those chains for multiple hops, eventually writing through memory that
    // was reused at another allocator level (o1heap fragment headers died
    // this way). Poison cuts every stale chain at its first hop, and the
    // absorber region past the arena end turns that cut - a dereference of
    // 0xFFFFFFFF plus a field offset - into a harmless read of zeros or a
    // write into scratch instead of a crash.
    if (addr != 0 && size > 0 && size <= QUAR_MAX_SIZE)
    {
        memset(base + addr, 0xFF, size);
        MarkQuarantined(addr, size, true);

        // Evict oldest entries until both the slot and the byte budget fit.
        while (t_quarantineCount == QUAR_CAP ||
               (t_quarantineCount > 0 && t_quarantineBytes + size > QUAR_MAX_BYTES))
        {
            const QuarantinedFree oldest = t_quarantine[t_quarantineHead];
            t_quarantineHead = (t_quarantineHead + 1) % QUAR_CAP;
            t_quarantineCount--;
            t_quarantineBytes -= oldest.size;
            MarkQuarantined(oldest.addr, oldest.size, false);

            ctx.r3.u32 = oldest.heap;
            ctx.r4.u32 = oldest.addr;
            ctx.r5.u32 = oldest.size;
            __imp__sub_82EA8AB0(ctx, base);
        }

        t_quarantine[(t_quarantineHead + t_quarantineCount) % QUAR_CAP] = { heap, addr, size };
        t_quarantineCount++;
        t_quarantineBytes += size;
        return;
    }

    __imp__sub_82EA8AB0(ctx, base);
}

// ---------------------------------------------------------------------------
// Collision-pair drain hooks (issue #27, the mechanism-level fix).
//
// During a physics step, pair add/removes are queued as raw collidable
// pointers (sub_82EF18B0 / sub_82EF19F0) and drained later by sub_82EF2348.
// Nothing pins the entities, so a pair member can be destroyed while its
// entries still sit in the queues. The drain then does two fatal things:
//
//  - sub_82EF5680 CREATES an agent for a queued add whose collidable is
//    already freed - the agent is born dangling, gets registered into the
//    live partner's slot table, is evaluated immediately (the original ring
//    crash) and can never be removed, because its identity lives in freed
//    memory. These undead agents are the "references that outlive seconds".
//
//  - sub_82F760A8 looks an agent up for a queued remove by walking the
//    collidable's slot table at +76/+80; on a freed collidable the walk
//    reads poison and silently returns "not found".
//
// Both drain call sites handle a null result gracefully, so recognising
// destroyed pair members via the quarantine bitmap and returning null endows
// the drain with exactly the tolerance the Xbox 360's intact-memory timing
// used to provide. The Xbox behaviour: lookups on freed-but-intact memory
// still succeeded. Ours: lookups on freed memory correctly no-op.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Race reproduction harness (local testing only, marker-file gated).
//
// The issue #27 race window is "an entity is destroyed while its pair entries
// sit in the drain queues" - normally microseconds wide, which is why it
// never reproduces on our devices. repro_race.txt stretches the window by
// sleeping a few milliseconds at the drain entry, letting a mid-range tablet
// reproduce what AYN handhelds hit naturally. repro_nofilter.txt disables the
// pair filter for A/B validation: race+nofilter should crash exactly like the
// tester devices; race+filter should survive with "dropped agent creation"
// lines. Both markers are read once, from the external driver_import folder
// or the internal files dir (the latter is creatable via run-as).
// ---------------------------------------------------------------------------

static bool ReproMarker(const char* name)
{
    std::error_code ec;
    return std::filesystem::exists(os::android::GetExternalFilesDir() / "driver_import" / name, ec) ||
           std::filesystem::exists(os::android::GetInternalFilesDir() / name, ec);
}

static bool ReproRaceEnabled()
{
    static bool s_enabled = []
    {
        bool enabled = ReproMarker("repro_race.txt");
        if (enabled)
            LOG_ERROR("REPRO: drain delay perturbation enabled (repro_race.txt).");
        return enabled;
    }();
    return s_enabled;
}

static bool ReproFilterDisabled()
{
    static bool s_disabled = []
    {
        bool disabled = ReproMarker("repro_nofilter.txt");
        if (disabled)
            LOG_ERROR("REPRO: pair filter DISABLED (repro_nofilter.txt).");
        return disabled;
    }();
    return s_disabled;
}

// Pair-queue drain: the perturbation point. Sleeping here stretches the
// enqueue-to-drain window from microseconds to milliseconds.
PPC_FUNC_IMPL(__imp__sub_82EF2348);
PPC_FUNC(sub_82EF2348)
{
    if (ReproRaceEnabled())
        usleep(2000 + (rand() % 6000));

    __imp__sub_82EF2348(ctx, base);
}

// Agent factory (r3/r4 = shape entries inside the two collidables). Never
// create an agent for a destroyed pair member. diag19's six tester logs
// proved the entry pointers themselves are never quarantined (0 hits), while
// diag8 event ordering proved agents are still born after a node's death:
// the collidable outlives its animation/physics node, and the factory takes
// that node from entry+184 (the same field the enqueue dedup compares). Check
// those node pointers, not just the entries.
PPC_FUNC_IMPL(__imp__sub_82EF5680);
PPC_FUNC(sub_82EF5680)
{
    if (!ReproFilterDisabled())
    {
        auto entryNodeDead = [&](uint32_t entry)
        {
            if (IsQuarantined(entry))
                return true;
            if (entry < 0x1000 || entry >= 0xFFFF0000)
                return false; // malformed entry: let the original code decide
            uint32_t node = PPC_LOAD_U32(entry + 184);
            return node != 0 && IsQuarantined(node);
        };

        if (entryNodeDead(ctx.r3.u32) || entryNodeDead(ctx.r4.u32))
        {
            static std::atomic<uint32_t> s_dropCount{ 0 };
            if (s_dropCount.fetch_add(1, std::memory_order_relaxed) < 16)
                LOGFN_ERROR("sub_82EF5680: dropped agent creation for destroyed pair (a={:08X} b={:08X})", ctx.r3.u32, ctx.r4.u32);
            ctx.r3.u32 = 0;
            return;
        }
    }

    __imp__sub_82EF5680(ctx, base);
}

// Agent lookup by pair (walks u32[coll+76] slot table, count at +80). Miss
// fast on destroyed members instead of walking poisoned tables.
PPC_FUNC_IMPL(__imp__sub_82F760A8);
PPC_FUNC(sub_82F760A8)
{
    if (!ReproFilterDisabled() &&
        (IsQuarantined(ctx.r3.u32) || IsQuarantined(ctx.r4.u32)))
    {
        ctx.r3.u32 = 0;
        return;
    }

    __imp__sub_82F760A8(ctx, base);
}

#endif

#ifdef __ANDROID__

// The destructor-deferral experiments (diag17/diag18: 100ms and 1s) are
// deliberately NOT part of this build. They disproved their own premise -
// the dangling references are not a finite timing window but undead agents
// born from the drain (see the pair hooks above) - and deferring the
// destructor onto whichever thread later drains the queue is a novel
// concurrency risk the game never had.

// Node child repair (issue #27). Three routines dereference a node's children
// (+16/+20) and write to each child's slot table through u32[child+76]: the
// evaluator, the single-node clone (sub_82F768E0) and the pool swap-remove
// flusher (sub_82F76698). When a child was destroyed early (the proven
// use-after-free), that dereference reads the quarantine poison 0xFFFFFFFF and
// faults past the 4GB guest arena - or, before the quarantine existed, wrote
// through reused garbage and corrupted the heap at random. Repair the node
// before those routines run: a dead child is replaced with its surviving
// sibling (the type-4 blend degrades to blending a clip with itself), and if
// both children are dead they are pointed at a synthetic "sink" node whose
// data pointer reads as destroyed (so the evaluator skips it) and whose slot
// table is a private scratch buffer (so registrations land harmlessly).

// Only reject pointers that cannot be dereferenced safely (null page, 0/-1
// destructed/poisoned markers, or so high that +76 leaves the 4GB guest
// arena). Slot tables may live in the static image, so no upper heap bound.
static bool NodePtrUsable(uint32_t ptr)
{
    return ptr >= 0x1000 && ptr < 0xFFFF0000;
}

// A child is dead when its pointer is unusable or its payload reads as
// destructed/poisoned (data pointer at +8, slot table pointer at +76).
static bool NodeChildDead(uint8_t* base, uint32_t child)
{
    if (!NodePtrUsable(child))
        return true;
    uint32_t data = PPC_LOAD_U32(child + 8);
    if (data == 0 || data == 0xFFFFFFFF)
        return true;
    return !NodePtrUsable(PPC_LOAD_U32(child + 76));
}

// Lazily built guest-side stand-in child: evaluator-invisible, registration-safe.
// Slot indices come from u16 fields scaled by 8, so the scratch table covers the
// full 16-bit range.
static uint32_t GetSinkChild(uint8_t* base)
{
    static std::atomic<uint32_t> s_sink{ 0 };
    uint32_t sink = s_sink.load(std::memory_order_acquire);
    if (sink != 0)
        return sink;

    static std::mutex s_mutex;
    std::lock_guard lock(s_mutex);
    sink = s_sink.load(std::memory_order_relaxed);
    if (sink != 0)
        return sink;

    constexpr uint32_t NODE_SIZE = 128;
    constexpr uint32_t TABLE_SIZE = 0x10000 * 8;
    void* host = g_userHeap.AllocPhysical(NODE_SIZE + TABLE_SIZE, 16);
    memset(host, 0, NODE_SIZE + TABLE_SIZE);
    uint32_t guest = g_memory.MapVirtual(host);

    PPC_STORE_U32(guest + 8, 0xFFFFFFFF);        // data: reads as destroyed
    PPC_STORE_U32(guest + 76, guest + NODE_SIZE); // slot table: private scratch

    s_sink.store(guest, std::memory_order_release);
    return guest;
}

// Returns true when the node needed repair. Dead children are always replaced
// with the sink, never with the surviving sibling: the sink keeps registration
// writes safe while its destroyed-reading data pointer keeps the evaluator
// guard skipping the node. Substituting the sibling was tried and re-armed the
// evaluation with a mismatched clip, which tripped the game's own pose
// consistency assert (KeBugCheck from sub_82BB5870).
static bool RepairNodeChildren(uint8_t* base, uint32_t node, const char* who, bool& bothDead)
{
    bothDead = false;
    if (!NodePtrUsable(node))
        return false;

    uint32_t childA = PPC_LOAD_U32(node + 16);
    uint32_t childB = PPC_LOAD_U32(node + 20);
    bool deadA = NodeChildDead(base, childA);
    bool deadB = NodeChildDead(base, childB);

    if (!deadA && !deadB)
        return false;

    bothDead = deadA && deadB;
    uint32_t sink = GetSinkChild(base);
    if (deadA)
        PPC_STORE_U32(node + 16, sink);
    if (deadB)
        PPC_STORE_U32(node + 20, sink);

    static std::atomic<uint32_t> s_repairCount{ 0 };
    if (s_repairCount.fetch_add(1, std::memory_order_relaxed) < 8)
    {
        LOGF_ERROR("{}: sink substituted for dead child{}{} of {:08X} (childA={:08X} childB={:08X})",
            who, deadA ? "A" : "", deadB ? "B" : "", node, childA, childB);
    }
    return true;
}

// Single-node clone: copies 128 bytes from the source node (r4), including the
// child pointers, then registers the clone in each child's slot table.
PPC_FUNC_IMPL(__imp__sub_82F768E0);
PPC_FUNC(sub_82F768E0)
{
    bool bothDead = false;
    if (RepairNodeChildren(base, ctx.r4.u32, "sub_82F768E0", bothDead) && bothDead)
    {
        // Callers iterate a batch and do not consume the return value; skipping
        // a fully dead clone is cleaner than registering the sink twice more.
        ctx.r3.u32 = 0;
        return;
    }

    __imp__sub_82F768E0(ctx, base);
}

// Pool swap-remove (r3 = pool {offset, chunk array, chunk count}, r4 = node
// being removed): the pool's last node is copied over r4 and then re-registered
// through its child slot tables. Repair that node before the move; skipping is
// not an option here because the pool bookkeeping must advance.
PPC_FUNC_IMPL(__imp__sub_82F76698);
PPC_FUNC(sub_82F76698)
{
    uint32_t pool = ctx.r3.u32;
    if (NodePtrUsable(pool))
    {
        uint32_t offset = PPC_LOAD_U32(pool + 0);
        uint32_t chunks = PPC_LOAD_U32(pool + 4);
        uint32_t count = PPC_LOAD_U32(pool + 8);
        if (NodePtrUsable(chunks) && count > 0 && offset >= 128)
        {
            uint32_t lastChunk = PPC_LOAD_U32(chunks + (count - 1) * 4);
            if (NodePtrUsable(lastChunk))
            {
                bool bothDead = false;
                RepairNodeChildren(base, lastChunk + offset - 128, "sub_82F76698", bothDead);
            }
        }
    }

    __imp__sub_82F76698(ctx, base);
}

#endif

// Animation node evaluator. The function dispatches on a type byte at +0; the type-4
// branch interpolates between two child nodes (+16 / +20) and dereferences each child's
// data pointer at +8 without validating it. On some devices (issue #27: Snapdragon
// 8 Gen 2 handhelds, 100% on collecting a ring) one of those data pointers is 0 or -1,
// which reads guest address 0x5B..0x5C and crashes. Root cause unknown (likely an
// unbound animation track); skip the evaluation instead of crashing and log the state
// so reports tell us how often it fires.
PPC_FUNC_IMPL(__imp__sub_82F77188);
PPC_FUNC(sub_82F77188)
{
    if (PPC_LOAD_U8(ctx.r3.u32 + 0) == 4)
    {
        auto invalidPtr = [](uint32_t ptr) { return ptr == 0 || ptr == 0xFFFFFFFF; };

        uint32_t childA = PPC_LOAD_U32(ctx.r3.u32 + 16);
        uint32_t childB = PPC_LOAD_U32(ctx.r3.u32 + 20);
        uint32_t dataA = invalidPtr(childA) ? 0 : PPC_LOAD_U32(childA + 8);
        uint32_t dataB = invalidPtr(childB) ? 0 : PPC_LOAD_U32(childB + 8);

        if (invalidPtr(dataA) || invalidPtr(dataB))
        {
#ifdef __ANDROID__
            // Watchpoint the broken data field: whoever writes that page next gets
            // its pc logged by the crash handler (PAGEWATCH HIT lines in log.txt).
            uint32_t brokenChild = invalidPtr(dataB) ? childB : childA;
            if (!invalidPtr(brokenChild))
                os::android::ArmPageWatch(base + brokenChild + 8, base + brokenChild + 8);
#endif

            static std::atomic<uint32_t> s_reportCount{ 0 };
            if (s_reportCount.fetch_add(1, std::memory_order_relaxed) < 8)
            {
                LOGFN_ERROR("sub_82F77188: skipping type-4 node with invalid data "
                    "(this={:08X} childA={:08X} dataA={:08X} childB={:08X} dataB={:08X})",
                    ctx.r3.u32, childA, dataA, childB, dataB);

#ifdef __ANDROID__
                // diag8: print the allocation lifetime of the broken child node
                // and its parent, so we can tell use-after-free (freed, then the
                // memory re-handed to someone else) from double allocation.
                uint32_t brokenNode = invalidPtr(dataB) ? childB : childA;
                if (!invalidPtr(brokenNode))
                    DumpAllocHistory(brokenNode, 48, "node");
                DumpAllocHistory(ctx.r3.u32, 48, "parent");
#endif
            }

            return;
        }
    }

    __imp__sub_82F77188(ctx, base);
}
