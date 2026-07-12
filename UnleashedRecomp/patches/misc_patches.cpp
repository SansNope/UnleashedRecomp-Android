#include <api/SWA.h>
#include <os/logger.h>
#include <ui/game_window.h>
#include <kernel/heap.h>

#include <atomic>
#include <mutex>
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

// ---------------------------------------------------------------------------
// The "ring crash" (issue #27) is a use-after-free in the game's own code that
// original hardware masked by timing: a small animation/physics node object is
// destroyed by its own deallocating destructor (sub_82ED4BB8) while a live
// parent blend node still references it through its child pointers at +16/+20.
// On this port the guest small-block allocator (sub_82EA8A30/sub_82EA8AB0,
// Havok hkThreadMemory-style per-thread freelists) reuses the freed block for
// animation transform data within milliseconds, and worker threads then read
// those floats through the dangling pointer - crashing, freezing, or silently
// corrupting the heap depending on scheduler timing, which is why some devices
// crashed on every ring pickup while others never did. Three cooperating
// patches make the lifetime bug harmless:
//   1. Freed small blocks are quarantined and poisoned instead of reused
//      immediately (the free hook below).
//   2. The node-clone routine repairs dead child pointers instead of spreading
//      them and writing through garbage registration tables (sub_82F768E0).
//   3. The evaluator skips nodes whose data reads as destroyed (sub_82F77188).
// ---------------------------------------------------------------------------

namespace
{
    // Deferred small-block frees. The heap instance is per-thread TLS state in
    // guest code, so completing a free later from the same thread preserves the
    // allocator's threading contract. 1024 blocks of at most 512 bytes bound the
    // held memory at 512 KB per guest thread.
    constexpr size_t QUAR_CAP = 1024;
    constexpr uint32_t QUAR_MAX_SIZE = 512;

    struct QuarantinedFree
    {
        uint32_t heap;
        uint32_t addr;
        uint32_t size;
    };

    thread_local QuarantinedFree t_quarantine[QUAR_CAP];
    thread_local size_t t_quarantineHead = 0;
    thread_local size_t t_quarantineCount = 0;
}

// Guest small-block free(heap r3, ptr r4, size r5): hold the block back and
// poison it with 0xFF so any dangling reader sees -1 deterministically, which
// the guards below already treat as "destroyed" and skip.
PPC_FUNC_IMPL(__imp__sub_82EA8AB0);
PPC_FUNC(sub_82EA8AB0)
{
    uint32_t heap = ctx.r3.u32;
    uint32_t addr = ctx.r4.u32;
    uint32_t size = ctx.r5.u32;

    if (addr != 0 && size > 0 && size <= QUAR_MAX_SIZE)
    {
        memset(base + addr, 0xFF, size);

        if (t_quarantineCount == QUAR_CAP)
        {
            const QuarantinedFree oldest = t_quarantine[t_quarantineHead];
            t_quarantineHead = (t_quarantineHead + 1) % QUAR_CAP;
            t_quarantineCount--;

            ctx.r3.u32 = oldest.heap;
            ctx.r4.u32 = oldest.addr;
            ctx.r5.u32 = oldest.size;
            __imp__sub_82EA8AB0(ctx, base);
        }

        t_quarantine[(t_quarantineHead + t_quarantineCount) % QUAR_CAP] = { heap, addr, size };
        t_quarantineCount++;
        return;
    }

    __imp__sub_82EA8AB0(ctx, base);
}

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

// Animation node evaluator. The function dispatches on a type byte at +0; the type-4
// branch interpolates between two child nodes (+16 / +20) and dereferences each child's
// data pointer at +8 without validating it. When a child was destroyed early (see the
// use-after-free notes above), that pointer reads 0 or the quarantine poison -1; skip
// the evaluation instead of crashing and log the state so reports tell us how often
// it fires.
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
            static std::atomic<uint32_t> s_reportCount{ 0 };
            if (s_reportCount.fetch_add(1, std::memory_order_relaxed) < 8)
            {
                LOGFN_ERROR("sub_82F77188: skipping type-4 node with invalid data "
                    "(this={:08X} childA={:08X} dataA={:08X} childB={:08X} dataB={:08X})",
                    ctx.r3.u32, childA, dataA, childB, dataB);
            }

            return;
        }
    }

    __imp__sub_82F77188(ctx, base);
}
