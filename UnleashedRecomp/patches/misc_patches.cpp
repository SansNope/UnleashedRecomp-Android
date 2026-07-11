#include <api/SWA.h>
#include <os/logger.h>
#include <ui/game_window.h>

#include <atomic>
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

// Animation node evaluator. The function dispatches on a type byte at +0; the type-4
// branch interpolates between two child nodes (+16 / +20) and dereferences each child's
// data pointer at +8 without validating it. On some devices (issue #27: Snapdragon
// 8 Gen 2 handhelds, 100% on collecting a ring) one of those data pointers is 0 or -1,
// which reads guest address 0x5B..0x5C and crashes. Root cause unknown (likely an
// unbound animation track); skip the evaluation instead of crashing and log the state
// so reports tell us how often it fires.
static std::string DumpGuestWords(uint8_t* base, uint32_t addr, size_t count)
{
    std::string words;
    for (size_t i = 0; i < count; i++)
        words += fmt::format("{:08X} ", PPC_LOAD_U32(addr + uint32_t(i * 4)));

    return words;
}

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
            uint32_t report = s_reportCount.fetch_add(1, std::memory_order_relaxed);
            if (report < 8)
            {
                LOGFN_ERROR("sub_82F77188: skipping type-4 node with invalid data "
                    "(this={:08X} childA={:08X} dataA={:08X} childB={:08X} dataB={:08X})",
                    ctx.r3.u32, childA, dataA, childB, dataB);
            }

            // Full node/child dumps for the first two reports: the leading words carry
            // vtable pointers and slot indices that identify the class and asset.
            if (report < 2)
            {
                LOGFN_ERROR("  this   @{:08X}: {}", ctx.r3.u32, DumpGuestWords(base, ctx.r3.u32, 12));
                if (!invalidPtr(childA))
                    LOGFN_ERROR("  childA @{:08X}: {}", childA, DumpGuestWords(base, childA, 12));
                if (!invalidPtr(childB))
                    LOGFN_ERROR("  childB @{:08X}: {}", childB, DumpGuestWords(base, childB, 12));
                if (!invalidPtr(ctx.r4.u32))
                    LOGFN_ERROR("  arg r4 @{:08X}: {}", ctx.r4.u32, DumpGuestWords(base, ctx.r4.u32, 12));

                // Follow every pointer-shaped field one level: heap objects begin with a
                // guest vtable (0x82xxxxxx), which statically identifies the class.
                auto followPtr = [&](const char* label, uint32_t value)
                {
                    if (value >= 0x1000 && value < 0x20000000)
                        LOGFN_ERROR("  {} -> @{:08X}: {}", label, value, DumpGuestWords(base, value, 8));
                };

                followPtr("this+8  ", PPC_LOAD_U32(ctx.r3.u32 + 8));
                if (!invalidPtr(childA))
                {
                    // Owner/registry candidate: dump further to cover the slot tables
                    // around +76 seen in sub_82F76698.
                    uint32_t ownerA = PPC_LOAD_U32(childA + 0);
                    if (ownerA >= 0x1000 && ownerA < 0x20000000)
                        LOGFN_ERROR("  childA+0 -> @{:08X}: {}", ownerA, DumpGuestWords(base, ownerA, 24));
                }
                if (!invalidPtr(childB))
                    followPtr("childB+0", PPC_LOAD_U32(childB + 0));
                if (!invalidPtr(ctx.r4.u32))
                {
                    followPtr("r4+0    ", PPC_LOAD_U32(ctx.r4.u32 + 0));
                    followPtr("r4+8    ", PPC_LOAD_U32(ctx.r4.u32 + 8));
                    followPtr("r4+12   ", PPC_LOAD_U32(ctx.r4.u32 + 12));
                }
            }

            return;
        }

        // Reference dumps from healthy runs: vtables and layouts are identical on every
        // device, so a non-crashing device supplies the class identification for the
        // broken dumps coming from testers.
        static std::atomic<uint32_t> s_healthyCount{ 0 };
        if (s_healthyCount.fetch_add(1, std::memory_order_relaxed) < 2)
        {
            LOG_ERROR("sub_82F77188: healthy type-4 reference dump");
            LOGFN_ERROR("  this   @{:08X}: {}", ctx.r3.u32, DumpGuestWords(base, ctx.r3.u32, 12));
            LOGFN_ERROR("  childA @{:08X}: {}", childA, DumpGuestWords(base, childA, 12));
            LOGFN_ERROR("  childB @{:08X}: {}", childB, DumpGuestWords(base, childB, 12));
            LOGFN_ERROR("  arg r4 @{:08X}: {}", ctx.r4.u32, DumpGuestWords(base, ctx.r4.u32, 12));

            auto followPtr = [&](const char* label, uint32_t value, size_t count)
            {
                if (value >= 0x1000 && value < 0x20000000)
                    LOGFN_ERROR("  {} -> @{:08X}: {}", label, value, DumpGuestWords(base, value, count));
            };

            followPtr("this+8  ", PPC_LOAD_U32(ctx.r3.u32 + 8), 8);
            followPtr("childA+0", PPC_LOAD_U32(childA + 0), 24);
            followPtr("childB+0", PPC_LOAD_U32(childB + 0), 24);
            followPtr("r4+0    ", PPC_LOAD_U32(ctx.r4.u32 + 0), 8);
            followPtr("r4+8    ", PPC_LOAD_U32(ctx.r4.u32 + 8), 8);
            followPtr("r4+12   ", PPC_LOAD_U32(ctx.r4.u32 + 12), 8);
        }
    }

    __imp__sub_82F77188(ctx, base);
}

// Node registration flusher: iterates a pending list on r3 and stores each node into
// its registry's slot tables (the tables read as null in the issue #27 crash dumps).
// Log the first registrations of a run to capture the registry object while healthy.
PPC_FUNC_IMPL(__imp__sub_82F76698);
PPC_FUNC(sub_82F76698)
{
    static std::atomic<uint32_t> s_dumpCount{ 0 };
    if (s_dumpCount.fetch_add(1, std::memory_order_relaxed) < 2)
    {
        LOGFN_ERROR("sub_82F76698: registration dump (r3={:08X} r4={:08X})", ctx.r3.u32, ctx.r4.u32);
        if (ctx.r3.u32 >= 0x1000 && ctx.r3.u32 < 0x20000000)
            LOGFN_ERROR("  r3 @{:08X}: {}", ctx.r3.u32, DumpGuestWords(base, ctx.r3.u32, 24));
        if (ctx.r4.u32 >= 0x1000 && ctx.r4.u32 < 0x20000000)
            LOGFN_ERROR("  r4 @{:08X}: {}", ctx.r4.u32, DumpGuestWords(base, ctx.r4.u32, 12));
    }

    __imp__sub_82F76698(ctx, base);
}
