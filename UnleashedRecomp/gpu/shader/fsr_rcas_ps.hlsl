#include "fsr_common.hlsli"

#ifdef FSR_FP16
#define FSR_RCAS_H 1
AH4 FsrRcasLoadH(ASW2 p)
{
    int2 pixel = clamp(int2(p), int2(0, 0), int2(fsrOutputSize) - 1);
    return AH4(g_Texture2DDescriptorHeap[fsrTextureIndex].Load(int3(pixel, 0)));
}
void FsrRcasInputH(inout AH1 r, inout AH1 g, inout AH1 b) {}
#else
#define FSR_RCAS_F 1
AF4 FsrRcasLoadF(ASU2 p)
{
    // RCAS reads a cross of neighbours; Load alone is undefined out of bounds.
    int2 pixel = clamp(p, int2(0, 0), int2(fsrOutputSize) - 1);
    return g_Texture2DDescriptorHeap[fsrTextureIndex].Load(int3(pixel, 0));
}
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}
#endif
#include "../../../thirdparty/FidelityFX-FSR/ffx_fsr1.h"

float4 main(float4 position : SV_Position) : SV_Target
{
    uint4 con;
    // AMD uses stops: zero is strongest, two is mild. UI increases strength.
    FsrRcasCon(con, 2.0 * (1.0 - saturate(fsrSharpness)));
#ifdef FSR_FP16
    AH3 color;
    FsrRcasH(color.r, color.g, color.b, uint2(position.xy), con);
#else
    float3 color;
    FsrRcasF(color.r, color.g, color.b, uint2(position.xy), con);
#endif
    return float4(saturate(color), 1.0);
}
