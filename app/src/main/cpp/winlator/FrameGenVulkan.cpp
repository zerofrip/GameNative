#include "FrameGenVulkan.hpp"
#include "VulkanRendererContext.h"
#include "framegen_blend.h"

#include <android/log.h>
#include <cstring>
#include <stdexcept>

#define FGLOG(...) __android_log_print(ANDROID_LOG_DEBUG, "Winlator_FrameGen", __VA_ARGS__)
#define FGLOG_E(...) __android_log_print(ANDROID_LOG_ERROR, "Winlator_FrameGen", __VA_ARGS__)

namespace {

struct OneTimeRecord {
    void (*fn)(VkCommandBuffer, void*);
    void* data;
};

void oneTimeTrampoline(VkCommandBuffer cb, void* data) {
    auto* rec = static_cast<OneTimeRecord*>(data);
    rec->fn(cb, rec->data);
}

} // namespace

FrameGenVulkan::FrameGenVulkan(VulkanRendererContext* ctx) : ctx_(ctx) {}

FrameGenVulkan::~FrameGenVulkan() {
    destroyPipeline();
}

bool FrameGenVulkan::submitOneTime(const char* label, void (*record)(VkCommandBuffer, void*), void* userData) {
    if (!ctx_ || !record) return false;
    auto& vk = ctx_->vk_;
    VkDevice device = ctx_->device;
    VkQueue queue = ctx_->graphicsQueue;

    VkCommandBufferAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandPool = ctx_->cmdPool;
    ai.commandBufferCount = 1;
    VkCommandBuffer cb = VK_NULL_HANDLE;
    if (vk.AllocateCommandBuffers(device, &ai, &cb) != VK_SUCCESS) {
        FGLOG_E("%s: AllocateCommandBuffers failed", label);
        return false;
    }

    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vk.BeginCommandBuffer(cb, &bi) != VK_SUCCESS) {
        vk.FreeCommandBuffers(device, ctx_->cmdPool, 1, &cb);
        return false;
    }

    record(cb, userData);

    if (vk.EndCommandBuffer(cb) != VK_SUCCESS) {
        vk.FreeCommandBuffers(device, ctx_->cmdPool, 1, &cb);
        return false;
    }

    VkFenceCreateInfo fi{};
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkFence fence = VK_NULL_HANDLE;
    if (vk.CreateFence(device, &fi, nullptr, &fence) != VK_SUCCESS) {
        vk.FreeCommandBuffers(device, ctx_->cmdPool, 1, &cb);
        return false;
    }

    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    VkResult sr = vk.QueueSubmit(queue, 1, &si, fence);
    vk.FreeCommandBuffers(device, ctx_->cmdPool, 1, &cb);
    if (sr != VK_SUCCESS) {
        vk.DestroyFence(device, fence, nullptr);
        FGLOG_E("%s: QueueSubmit failed %d", label, (int)sr);
        return false;
    }

    vk.WaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
    vk.DestroyFence(device, fence, nullptr);
    return true;
}

void FrameGenVulkan::transitionImage(VkCommandBuffer cb, VkImage img, VkImageLayout oldL, VkImageLayout newL,
                                     VkAccessFlags srcA, VkAccessFlags dstA,
                                     VkPipelineStageFlags srcS, VkPipelineStageFlags dstS) {
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = oldL;
    b.newLayout = newL;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.srcAccessMask = srcA;
    b.dstAccessMask = dstA;
    ctx_->vk_.CmdPipelineBarrier(cb, srcS, dstS, 0, 0, nullptr, 0, nullptr, 1, &b);
}

bool FrameGenVulkan::ensurePipeline() {
    if (pipelineReady_) return true;
    if (!ctx_) return false;

    auto& vk = ctx_->vk_;
    VkDevice device = ctx_->device;

    VkSamplerCreateInfo sci{};
    sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vk.CreateSampler(device, &sci, nullptr, &sampler_) != VK_SUCCESS)
        return false;

    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0] = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    bindings[1] = {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    bindings[2] = {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};

    VkDescriptorSetLayoutCreateInfo dslCi{};
    dslCi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslCi.bindingCount = 3;
    dslCi.pBindings = bindings;
    if (vk.CreateDescriptorSetLayout(device, &dslCi, nullptr, &dsLayout_) != VK_SUCCESS)
        return false;

    VkPipelineLayoutCreateInfo plCi{};
    plCi.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plCi.setLayoutCount = 1;
    plCi.pSetLayouts = &dsLayout_;
    if (vk.CreatePipelineLayout(device, &plCi, nullptr, &pipeLayout_) != VK_SUCCESS)
        return false;

    VkShaderModuleCreateInfo smCi{};
    smCi.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smCi.codeSize = sizeof(framegen_blend_code);
    smCi.pCode = framegen_blend_code;
    VkShaderModule shader = VK_NULL_HANDLE;
    if (vk.CreateShaderModule(device, &smCi, nullptr, &shader) != VK_SUCCESS)
        return false;

    VkComputePipelineCreateInfo cpCi{};
    cpCi.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpCi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpCi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpCi.stage.module = shader;
    cpCi.stage.pName = "main";
    cpCi.layout = pipeLayout_;
    VkResult pr = vk.CreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpCi, nullptr, &pipeline_);
    vk.DestroyShaderModule(device, shader, nullptr);
    if (pr != VK_SUCCESS)
        return false;

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2};
    VkDescriptorPoolSize ps2{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1};
    VkDescriptorPoolSize poolSizes[] = {ps, ps2};
    VkDescriptorPoolCreateInfo dpCi{};
    dpCi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpCi.poolSizeCount = 2;
    dpCi.pPoolSizes = poolSizes;
    dpCi.maxSets = 1;
    if (vk.CreateDescriptorPool(device, &dpCi, nullptr, &descPool_) != VK_SUCCESS)
        return false;

    pipelineReady_ = true;
    FGLOG("compute pipeline ready");
    return true;
}

void FrameGenVulkan::destroyPipeline() {
    if (!ctx_) return;
    auto& vk = ctx_->vk_;
    VkDevice device = ctx_->device;
    if (descPool_ != VK_NULL_HANDLE) { vk.DestroyDescriptorPool(device, descPool_, nullptr); descPool_ = VK_NULL_HANDLE; }
    if (pipeline_ != VK_NULL_HANDLE) { vk.DestroyPipeline(device, pipeline_, nullptr); pipeline_ = VK_NULL_HANDLE; }
    if (pipeLayout_ != VK_NULL_HANDLE) { vk.DestroyPipelineLayout(device, pipeLayout_, nullptr); pipeLayout_ = VK_NULL_HANDLE; }
    if (dsLayout_ != VK_NULL_HANDLE) { vk.DestroyDescriptorSetLayout(device, dsLayout_, nullptr); dsLayout_ = VK_NULL_HANDLE; }
    if (sampler_ != VK_NULL_HANDLE) { vk.DestroySampler(device, sampler_, nullptr); sampler_ = VK_NULL_HANDLE; }
    pipelineReady_ = false;
}

bool FrameGenVulkan::createHistoryTexture(uint32_t width, uint32_t height, VkFormat format, FrameGenImage& out) {
    destroyImage(out);
    if (!ctx_) return false;
    auto& vk = ctx_->vk_;
    VkDevice device = ctx_->device;

    VkImageCreateInfo ii{};
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.extent = {width, height, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.format = format;
    ii.tiling = VK_IMAGE_TILING_LINEAR;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
               VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vk.CreateImage(device, &ii, nullptr, &out.img) != VK_SUCCESS)
        return false;

    VkMemoryRequirements req;
    vk.GetImageMemoryRequirements(device, out.img, &req);
    uint32_t memType = ctx_->findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = memType;
    if (vk.AllocateMemory(device, &ai, nullptr, &out.mem) != VK_SUCCESS) {
        vk.DestroyImage(device, out.img, nullptr);
        out.img = VK_NULL_HANDLE;
        return false;
    }
    vk.BindImageMemory(device, out.img, out.mem, 0);

    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = out.img;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vk.CreateImageView(device, &vi, nullptr, &out.view) != VK_SUCCESS) {
        destroyImage(out);
        return false;
    }

    out.width = width;
    out.height = height;
    out.format = format;
    out.layout = VK_IMAGE_LAYOUT_UNDEFINED;
    return true;
}

bool FrameGenVulkan::createInterpOutput(uint32_t width, uint32_t height, VkFormat format, FrameGenImage& out) {
    return createHistoryTexture(width, height, format, out);
}

void FrameGenVulkan::destroyImage(FrameGenImage& img) {
    if (!ctx_) return;
    auto& vk = ctx_->vk_;
    VkDevice device = ctx_->device;
    if (img.view != VK_NULL_HANDLE) { vk.DestroyImageView(device, img.view, nullptr); img.view = VK_NULL_HANDLE; }
    if (img.img != VK_NULL_HANDLE) { vk.DestroyImage(device, img.img, nullptr); img.img = VK_NULL_HANDLE; }
    if (img.mem != VK_NULL_HANDLE) { vk.FreeMemory(device, img.mem, nullptr); img.mem = VK_NULL_HANDLE; }
    img.width = img.height = 0;
    img.format = VK_FORMAT_UNDEFINED;
    img.layout = VK_IMAGE_LAYOUT_UNDEFINED;
}

struct FrameGenVulkan::CopyImageData {
    FrameGenVulkan* self;
    VkImage src;
    VkImageLayout srcLayout;
    FrameGenImage* dst;
};

void FrameGenVulkan::recordCopyImage(VkCommandBuffer cb, void* data) {
    auto* d = static_cast<CopyImageData*>(data);
    FrameGenImage& dst = *d->dst;
    if (dst.layout == VK_IMAGE_LAYOUT_UNDEFINED)
        d->self->transitionImage(cb, dst.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               0, VK_ACCESS_TRANSFER_WRITE_BIT,
                               VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    else if (dst.layout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
        d->self->transitionImage(cb, dst.img, dst.layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                               VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageLayout srcLayout = d->srcLayout;
    if (srcLayout != VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        d->self->transitionImage(cb, d->src, srcLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               VK_ACCESS_MEMORY_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                               VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageCopy region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.extent = {dst.width, dst.height, 1};
    d->self->ctx_->vk_.CmdCopyImage(cb, d->src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                    dst.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    d->self->transitionImage(cb, dst.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                           VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    dst.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    if (d->srcLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
        d->self->transitionImage(cb, d->src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                               VK_ACCESS_TRANSFER_READ_BIT, 0,
                               VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
}

bool FrameGenVulkan::copyImage(VkImage src, VkImageLayout srcLayout, FrameGenImage& dst) {
    CopyImageData data{this, src, srcLayout, &dst};
    return submitOneTime("copyImage", recordCopyImage, &data);
}

struct FrameGenVulkan::CopyToSwapchainData {
    FrameGenVulkan* self;
    FrameGenImage src;
    VkImage dst;
    VkImageLayout dstLayout;
};

void FrameGenVulkan::recordCopyToSwapchain(VkCommandBuffer cb, void* data) {
    auto* d = static_cast<CopyToSwapchainData*>(data);
    FrameGenImage src = d->src;

    if (src.layout != VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        d->self->transitionImage(cb, src.img, src.layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageLayout dstLayout = d->dstLayout;
    if (dstLayout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
        d->self->transitionImage(cb, d->dst, dstLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               0, VK_ACCESS_TRANSFER_WRITE_BIT,
                               VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageCopy region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.extent = {src.width, src.height, 1};
    d->self->ctx_->vk_.CmdCopyImage(cb, src.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                    d->dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    d->self->transitionImage(cb, d->dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                           VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
}

bool FrameGenVulkan::copyToSwapchainImage(const FrameGenImage& src, VkImage dst, VkImageLayout dstLayout) {
    CopyToSwapchainData data{this, src, dst, dstLayout};
    return submitOneTime("copyToSwapchain", recordCopyToSwapchain, &data);
}

struct FrameGenVulkan::BlendData {
    FrameGenVulkan* self;
    FrameGenImage prev;
    FrameGenImage curr;
    FrameGenImage* dst;
    VkDescriptorSet ds;
};

void FrameGenVulkan::recordBlend(VkCommandBuffer cb, void* data) {
    auto* d = static_cast<BlendData*>(data);
    FrameGenImage& dst = *d->dst;

    if (dst.layout == VK_IMAGE_LAYOUT_UNDEFINED)
        d->self->transitionImage(cb, dst.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                               0, VK_ACCESS_SHADER_WRITE_BIT,
                               VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    else if (dst.layout != VK_IMAGE_LAYOUT_GENERAL)
        d->self->transitionImage(cb, dst.img, dst.layout, VK_IMAGE_LAYOUT_GENERAL,
                               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                               VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    auto& vk = d->self->ctx_->vk_;
    vk.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, d->self->pipeline_);
    vk.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, d->self->pipeLayout_, 0, 1, &d->ds, 0, nullptr);

    uint32_t gx = (dst.width + 7) / 8;
    uint32_t gy = (dst.height + 7) / 8;
    vk.CmdDispatch(cb, gx, gy, 1);

    d->self->transitionImage(cb, dst.img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                           VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    dst.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
}

bool FrameGenVulkan::blend(const FrameGenImage& prev, const FrameGenImage& curr, FrameGenImage& dst) {
    if (!ensurePipeline()) return false;

    auto& vk = ctx_->vk_;
    VkDevice device = ctx_->device;

    // Reset descriptor pool each blend (single set)
    vk.DestroyDescriptorPool(device, descPool_, nullptr);
    descPool_ = VK_NULL_HANDLE;
    VkDescriptorPoolSize poolSizes[] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
    };
    VkDescriptorPoolCreateInfo dpCi{};
    dpCi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpCi.poolSizeCount = 2;
    dpCi.pPoolSizes = poolSizes;
    dpCi.maxSets = 1;
    if (vk.CreateDescriptorPool(device, &dpCi, nullptr, &descPool_) != VK_SUCCESS)
        return false;

    VkDescriptorSetAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = descPool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &dsLayout_;
    VkDescriptorSet ds = VK_NULL_HANDLE;
    if (vk.AllocateDescriptorSets(device, &ai, &ds) != VK_SUCCESS)
        return false;

    VkDescriptorImageInfo prevInfo{sampler_, prev.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo currInfo{sampler_, curr.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo dstInfo{VK_NULL_HANDLE, dst.view, VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet writes[3]{};
    writes[0] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, ds, 0, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &prevInfo, nullptr, nullptr};
    writes[1] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, ds, 1, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &currInfo, nullptr, nullptr};
    writes[2] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, ds, 2, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &dstInfo, nullptr, nullptr};
    vk.UpdateDescriptorSets(device, 3, writes, 0, nullptr);

    BlendData data{this, prev, curr, &dst, ds};
    return submitOneTime("blend", recordBlend, &data);
}
