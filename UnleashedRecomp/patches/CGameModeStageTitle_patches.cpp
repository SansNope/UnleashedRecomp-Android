#include <api/SWA.h>
#include <patches/CTitleStateIntro_patches.h>
#include <ui/touch_controls.h>

// SWA::CGameModeStageTitle::Update
PPC_FUNC_IMPL(__imp__sub_825518B8);
PPC_FUNC(sub_825518B8)
{
    auto pGameModeStageTitle = (SWA::CGameModeStageTitle*)g_memory.Translate(ctx.r3.u32);

    __imp__sub_825518B8(ctx, base);

    // The title game mode covers the start screen, the main menu and the world
    // map: navigation surfaces where the touch stick should act as a D-pad.
    TouchControls::NotifyMenuVisible();

    if (g_quitMessageOpen)
        pGameModeStageTitle->m_AdvertiseMovieWaitTime = 0;
}
