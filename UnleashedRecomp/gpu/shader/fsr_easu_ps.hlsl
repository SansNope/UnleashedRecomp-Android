#include "fsr_common.hlsli"

#ifdef FSR_FP16
#define FSR_EASU_H 1
AH4 FsrEasuRH(AF2 p) { return AH4(g_Texture2DDescriptorHeap[fsrTextureIndex].GatherRed(g_SamplerDescriptorHeap[fsrSamplerIndex], p)); }
AH4 FsrEasuGH(AF2 p) { return AH4(g_Texture2DDescriptorHeap[fsrTextureIndex].GatherGreen(g_SamplerDescriptorHeap[fsrSamplerIndex], p)); }
AH4 FsrEasuBH(AF2 p) { return AH4(g_Texture2DDescriptorHeap[fsrTextureIndex].GatherBlue(g_SamplerDescriptorHeap[fsrSamplerIndex], p)); }
#else
#define FSR_EASU_F 1
AF4 FsrEasuRF(AF2 p) { return g_Texture2DDescriptorHeap[fsrTextureIndex].GatherRed(g_SamplerDescriptorHeap[fsrSamplerIndex], p); }
AF4 FsrEasuGF(AF2 p) { return g_Texture2DDescriptorHeap[fsrTextureIndex].GatherGreen(g_SamplerDescriptorHeap[fsrSamplerIndex], p); }
AF4 FsrEasuBF(AF2 p) { return g_Texture2DDescriptorHeap[fsrTextureIndex].GatherBlue(g_SamplerDescriptorHeap[fsrSamplerIndex], p); }
#endif
#include "../../../thirdparty/FidelityFX-FSR/ffx_fsr1.h"

float4 main(float4 position : SV_Position) : SV_Target
{
    uint4 con0, con1, con2, con3;
    FsrEasuCon(con0, con1, con2, con3, fsrInputSize.x, fsrInputSize.y,
        fsrInputSize.x, fsrInputSize.y, fsrOutputSize.x, fsrOutputSize.y);
#ifdef FSR_FP16
    AH3 color;
    FsrEasuH(color, uint2(position.xy), con0, con1, con2, con3);
#else
    float3 color;
    FsrEasuF(color, uint2(position.xy), con0, con1, con2, con3);
#endif
    return float4(saturate(color), 1.0);
}
