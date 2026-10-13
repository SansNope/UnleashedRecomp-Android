#include "../../../tools/XenosRecomp/XenosRecomp/shader_common.h"

// Matches FsrConstants in video.cpp. Interfaces stay 32-bit for both variants;
// the FP16 shaders only use native 16-bit arithmetic in local variables.
#ifdef __spirv__
#define fsrTextureIndex vk::RawBufferLoad<uint>(g_PushConstants.SharedConstants + 0)
#define fsrSamplerIndex vk::RawBufferLoad<uint>(g_PushConstants.SharedConstants + 4)
#define fsrInputSize vk::RawBufferLoad<uint2>(g_PushConstants.SharedConstants + 8)
#define fsrOutputSize vk::RawBufferLoad<uint2>(g_PushConstants.SharedConstants + 16)
#define fsrSharpness vk::RawBufferLoad<float>(g_PushConstants.SharedConstants + 24)
#else
cbuffer FsrConstants : register(b2, space4)
{
    uint fsrTextureIndex;
    uint fsrSamplerIndex;
    uint2 fsrInputSize;
    uint2 fsrOutputSize;
    float fsrSharpness;
};
#endif

#define A_GPU 1
#define A_HLSL 1
#ifdef FSR_FP16
#define A_HALF 1
#define A_HLSL_6_2 1
#define A_NO_16_BIT_CAST 1
#endif
#include "../../../thirdparty/FidelityFX-FSR/ffx_a.h"
