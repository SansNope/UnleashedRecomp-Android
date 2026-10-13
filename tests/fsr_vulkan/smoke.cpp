#include <plume_render_interface.h>
#include <plume_vulkan.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include "../../UnleashedRecomp/gpu/shader/copy_vs.hlsl.spirv.h"
#include "../../UnleashedRecomp/gpu/shader/fsr_easu_ps.hlsl.spirv.h"
#include "../../UnleashedRecomp/gpu/shader/fsr_rcas_ps.hlsl.spirv.h"
#include "../../UnleashedRecomp/gpu/shader/fsr_easu_fp16_ps.hlsl.spirv.h"
#include "../../UnleashedRecomp/gpu/shader/fsr_rcas_fp16_ps.hlsl.spirv.h"

using namespace plume;
namespace plume { std::unique_ptr<RenderInterface> CreateVulkanInterface(); }

struct Constants
{
    uint32_t textureIndex, samplerIndex, inputWidth, inputHeight, outputWidth, outputHeight;
    float sharpness;
};
static_assert(sizeof(Constants) == 28);

int main(int argc, char** argv)
try
{
    const bool fp16 = argc == 2 && std::strcmp(argv[1], "--fp16") == 0;
    if (argc > 1 && !fp16) throw std::runtime_error("usage: fsr_vulkan_smoke [--fp16]");
    std::cout << std::unitbuf;
    auto api = CreateVulkanInterface();
    if (!api) throw std::runtime_error("Vulkan instance unavailable");
    auto device = api->createDevice();
    if (!device) throw std::runtime_error("Vulkan device unavailable");
    std::cout << "Device: " << device->getDescription().name << '\n';
    const auto& caps = device->getCapabilities();
    std::cout << "Precision: " << (fp16 ? "FP16" : "FP32") << " (enabled float16="
        << caps.shaderFloat16 << ", int16=" << caps.shaderInt16 << ")\n";
    if (fp16 && (!caps.shaderFloat16 || !caps.shaderInt16))
        throw std::runtime_error("Native FP16/Int16 arithmetic unavailable; use FP32");
    auto queue = device->createCommandQueue(RenderCommandListType::DIRECT);
    auto cmd = queue->createCommandList();
    auto fence = device->createCommandFence();
    constexpr uint32_t iw = 17, ih = 11, ow = 31, oh = 19;
    constexpr auto format = RenderFormat::R8G8B8A8_UNORM;
    auto input = device->createTexture(RenderTextureDesc::Texture2D(iw, ih, 1, format, RenderTextureFlag::RENDER_TARGET));
    auto easu = device->createTexture(RenderTextureDesc::Texture2D(ow, oh, 1, format, RenderTextureFlag::RENDER_TARGET));
    auto rcas = device->createTexture(RenderTextureDesc::Texture2D(ow, oh, 1, format, RenderTextureFlag::RENDER_TARGET));
    auto framebuffer = [&](RenderTexture* texture)
    {
        const RenderTexture* attachment = texture;
        RenderFramebufferDesc desc;
        desc.colorAttachments = &attachment;
        desc.colorAttachmentsCount = 1;
        return device->createFramebuffer(desc);
    };
    auto inputFb = framebuffer(input.get()), easuFb = framebuffer(easu.get()), rcasFb = framebuffer(rcas.get());
    RenderDescriptorSetBuilder texturesBuilder, samplersBuilder;
    texturesBuilder.begin(); texturesBuilder.addTexture(0, 2); texturesBuilder.end(true, 2);
    auto textures = texturesBuilder.create(device.get());
    textures->setTexture(0, input.get(), RenderTextureLayout::SHADER_READ);
    textures->setTexture(1, easu.get(), RenderTextureLayout::SHADER_READ);
    samplersBuilder.begin(); samplersBuilder.addSampler(0, 1); samplersBuilder.end(true, 1);
    auto samplers = samplersBuilder.create(device.get());
    RenderSamplerDesc samplerDesc;
    samplerDesc.addressU = samplerDesc.addressV = samplerDesc.addressW = RenderTextureAddressMode::CLAMP;
    auto sampler = device->createSampler(samplerDesc);
    samplers->setSampler(0, sampler.get());
    RenderPipelineLayoutBuilder layoutBuilder;
    layoutBuilder.begin(false, true);
    for (int i = 0; i < 3; ++i) layoutBuilder.addDescriptorSet(texturesBuilder);
    layoutBuilder.addDescriptorSet(samplersBuilder);
    layoutBuilder.addPushConstant(0, 4, 24, RenderShaderStageFlag::VERTEX | RenderShaderStageFlag::PIXEL);
    layoutBuilder.end();
    auto layout = layoutBuilder.create(device.get());
    auto vertex = device->createShader(g_copy_vs_spirv, sizeof(g_copy_vs_spirv), "main", RenderShaderFormat::SPIRV);
    auto easuShader = fp16 ? device->createShader(g_fsr_easu_fp16_ps_spirv, sizeof(g_fsr_easu_fp16_ps_spirv), "main", RenderShaderFormat::SPIRV)
        : device->createShader(g_fsr_easu_ps_spirv, sizeof(g_fsr_easu_ps_spirv), "main", RenderShaderFormat::SPIRV);
    auto rcasShader = fp16 ? device->createShader(g_fsr_rcas_fp16_ps_spirv, sizeof(g_fsr_rcas_fp16_ps_spirv), "main", RenderShaderFormat::SPIRV)
        : device->createShader(g_fsr_rcas_ps_spirv, sizeof(g_fsr_rcas_ps_spirv), "main", RenderShaderFormat::SPIRV);
    RenderGraphicsPipelineDesc pipelineDesc;
    pipelineDesc.pipelineLayout = layout.get(); pipelineDesc.vertexShader = vertex.get();
    pipelineDesc.renderTargetFormat[0] = format;
    pipelineDesc.renderTargetBlend[0] = RenderBlendDesc::Copy(); pipelineDesc.renderTargetCount = 1;
    pipelineDesc.pixelShader = easuShader.get(); auto easuPipeline = device->createGraphicsPipeline(pipelineDesc);
    pipelineDesc.pixelShader = rcasShader.get(); auto rcasPipeline = device->createGraphicsPipeline(pipelineDesc);
    auto constants = device->createBuffer(RenderBufferDesc::UploadBuffer(512, RenderBufferFlag::CONSTANT));
    // Row pitch is in pixels. Align to 256 bytes for both Vulkan and D3D-style footprints.
    constexpr uint32_t rowWidth = 64;
    constexpr uint32_t imageBytes = rowWidth * oh * 4;
    auto readback = device->createBuffer(RenderBufferDesc::ReadbackBuffer(imageBytes * 2));
    const std::array<std::array<float, 3>, 5> colors = {{{0, 0, 0}, {1, 1, 1}, {0.25f, 0.5f, 0.75f}, {1, 0, 0}, {0, 1, 0}}};
    for (uint32_t pattern = 0; pattern < colors.size() + 2; ++pattern)
    for (const float sharpness : {0.0f, 0.2f, 1.0f})
    {
        const auto color = pattern < colors.size() ? colors[pattern] : std::array<float, 3>{0, 0, 0};
        const Constants c0{0, 0, iw, ih, ow, oh, sharpness}, c1{1, 0, ow, oh, ow, oh, sharpness};
        auto memory = static_cast<uint8_t*>(constants->map());
        std::memcpy(memory, &c0, sizeof(c0)); std::memcpy(memory + 256, &c1, sizeof(c1)); constants->unmap();
        cmd->begin();
        cmd->barriers(RenderBarrierStage::GRAPHICS, RenderTextureBarrier(input.get(), RenderTextureLayout::COLOR_WRITE));
        cmd->setFramebuffer(inputFb.get());
        cmd->clearColor(0, RenderColor(color[0], color[1], color[2], 1.0f));
        if (pattern >= colors.size())
        {
            for (uint32_t y = 0; y < ih; ++y)
            for (uint32_t x = 0; x < iw; ++x)
            {
                const bool gradient = pattern == colors.size();
                const float value = ((x / 3 + y / 3) % 2) ? 0.8f : 0.2f;
                const RenderRect rect(x, y, x + 1, y + 1);
                cmd->clearColor(0, gradient ? RenderColor(float(x) / (iw - 1), float(y) / (ih - 1), 0.5f, 1.0f)
                    : RenderColor(value, value, value, 1.0f), &rect, 1);
            }
        }
        cmd->barriers(RenderBarrierStage::GRAPHICS, RenderTextureBarrier(input.get(), RenderTextureLayout::SHADER_READ));
        cmd->setGraphicsPipelineLayout(layout.get());
        for (int i = 0; i < 3; ++i) cmd->setGraphicsDescriptorSet(textures.get(), i);
        cmd->setGraphicsDescriptorSet(samplers.get(), 3);
        cmd->setViewports(RenderViewport(0, 0, float(ow), float(oh)));
        cmd->setScissors(RenderRect(0, 0, ow, oh));
        auto pass = [&](RenderTexture* output, RenderFramebuffer* fb, RenderPipeline* pipeline, uint64_t offset)
        {
            cmd->barriers(RenderBarrierStage::GRAPHICS, RenderTextureBarrier(output, RenderTextureLayout::COLOR_WRITE));
            cmd->setFramebuffer(fb);
            const uint64_t addresses[3] = {0, 0, constants->getDeviceAddress() + offset};
            cmd->setGraphicsPushConstants(0, addresses, 0, sizeof(addresses));
            cmd->setPipeline(pipeline); cmd->drawInstanced(3, 1, 0, 0);
        };
        pass(easu.get(), easuFb.get(), easuPipeline.get(), 0);
        cmd->barriers(RenderBarrierStage::GRAPHICS, RenderTextureBarrier(easu.get(), RenderTextureLayout::SHADER_READ));
        pass(rcas.get(), rcasFb.get(), rcasPipeline.get(), 256);
        for (uint32_t i = 0; i < 2; ++i)
        {
            auto texture = i == 0 ? easu.get() : rcas.get();
            cmd->barriers(RenderBarrierStage::COPY, RenderTextureBarrier(texture, RenderTextureLayout::COPY_SOURCE));
            // Plume currently implements buffer -> image and image -> image only.
            // Use Vulkan's readback operation directly in this Vulkan-only test.
            VkBufferImageCopy copy{};
            copy.bufferOffset = i * imageBytes;
            copy.bufferRowLength = rowWidth;
            copy.bufferImageHeight = oh;
            copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            copy.imageSubresource.layerCount = 1;
            copy.imageExtent = {ow, oh, 1};
            vkCmdCopyImageToBuffer(static_cast<VulkanCommandList*>(cmd.get())->vk,
                static_cast<VulkanTexture*>(texture)->vk, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                static_cast<VulkanBuffer*>(readback.get())->vk, 1, &copy);
        }
        cmd->barriers(RenderBarrierStage::COPY, RenderBufferBarrier(readback.get(), RenderBufferAccess::READ));
        cmd->end(); queue->executeCommandLists(cmd.get(), fence.get()); queue->waitForCommandFence(fence.get());
        auto pixels = static_cast<const uint8_t*>(readback->map());
        uint32_t sharpenedPixels = 0;
        for (uint32_t image = 0; image < 2; ++image)
        for (uint32_t y = 0; y < oh; ++y)
        for (uint32_t x = 0; x < ow; ++x)
        for (uint32_t channel = 0; channel < 4; ++channel)
        {
            int expected = channel < 3 ? int(std::round(color[channel] * 255)) : 255;
            int actual = pixels[image * imageBytes + (y * rowWidth + x) * 4 + channel];
            if (pattern < colors.size() && std::abs(actual - expected) > 1)
                throw std::runtime_error("FSR output differs from constant input (including image borders)");
            if (channel == 3 && actual != 255)
                throw std::runtime_error("FSR did not cover the complete output image");
            if (pattern == colors.size())
            {
                const float u = std::clamp(((x + 0.5f) * iw / ow - 0.5f) / (iw - 1), 0.0f, 1.0f);
                const float v = std::clamp(((y + 0.5f) * ih / oh - 0.5f) / (ih - 1), 0.0f, 1.0f);
                expected = channel == 0 ? int(std::round(u * 255)) : channel == 1 ? int(std::round(v * 255)) : channel == 2 ? 128 : 255;
                // Check coordinates on EASU's output. Full-strength RCAS can
                // amplify EASU's small quantization steps in an otherwise smooth
                // ramp; it is checked separately with colors and checker edges.
                if (image == 0 && std::abs(actual - expected) > 12)
                {
                    std::cerr << "image=" << image << " sharpness=" << sharpness << " xy=" << x << ',' << y
                        << " channel=" << channel << " expected=" << expected << " actual=" << actual << '\n';
                    throw std::runtime_error("FSR gradient orientation or sampling coordinates are wrong");
                }
            }
            if (pattern == colors.size() + 1 && image == 1 && channel == 0 &&
                actual != pixels[(y * rowWidth + x) * 4])
                ++sharpenedPixels;
        }
        readback->unmap();
        if (pattern == colors.size() + 1 && sharpenedPixels == 0)
            throw std::runtime_error("RCAS made no change to the checkerboard");
    }
    std::cout << "PASS: 5 constant colors, gradient orientation, checkerboard sharpening, 3 sharpness values and all odd-size borders.\n";
    return 0;
}
catch (const std::exception& error)
{
    std::cerr << "FAIL: " << error.what() << '\n';
    return 1;
}
