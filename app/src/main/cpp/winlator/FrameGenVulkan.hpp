#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>

class VulkanRendererContext;

struct FrameGenImage {
    VkImage         img     = VK_NULL_HANDLE;
    VkDeviceMemory  mem     = VK_NULL_HANDLE;
    VkImageView     view    = VK_NULL_HANDLE;
    uint32_t        width   = 0;
    uint32_t        height  = 0;
    VkFormat        format  = VK_FORMAT_UNDEFINED;
    VkImageLayout   layout  = VK_IMAGE_LAYOUT_UNDEFINED;
};

class FrameGenVulkan {
public:
    explicit FrameGenVulkan(VulkanRendererContext* ctx);
    ~FrameGenVulkan();

    FrameGenVulkan(const FrameGenVulkan&) = delete;
    FrameGenVulkan& operator=(const FrameGenVulkan&) = delete;

    bool ensurePipeline();
    void destroyPipeline();

    bool createHistoryTexture(uint32_t width, uint32_t height, VkFormat format, FrameGenImage& out);
    bool createInterpOutput(uint32_t width, uint32_t height, VkFormat format, FrameGenImage& out);
    void destroyImage(FrameGenImage& img);

    bool copyImage(VkImage src, VkImageLayout srcLayout, FrameGenImage& dst);
    bool copyToSwapchainImage(const FrameGenImage& src, VkImage dst, VkImageLayout dstLayout);
    bool blend(const FrameGenImage& prev, const FrameGenImage& curr, FrameGenImage& dst);

private:
    VulkanRendererContext* ctx_;
    VkDescriptorSetLayout  dsLayout_     = VK_NULL_HANDLE;
    VkPipelineLayout       pipeLayout_   = VK_NULL_HANDLE;
    VkPipeline             pipeline_     = VK_NULL_HANDLE;
    VkDescriptorPool       descPool_     = VK_NULL_HANDLE;
    VkSampler              sampler_      = VK_NULL_HANDLE;
    bool                   pipelineReady_ = false;

    bool submitOneTime(const char* label, void (*record)(VkCommandBuffer, void*), void* userData);
    void transitionImage(VkCommandBuffer cb, VkImage img, VkImageLayout oldL, VkImageLayout newL,
                         VkAccessFlags srcA, VkAccessFlags dstA,
                         VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);

    struct CopyImageData;
    struct CopyToSwapchainData;
    struct BlendData;
    static void recordCopyImage(VkCommandBuffer cb, void* data);
    static void recordCopyToSwapchain(VkCommandBuffer cb, void* data);
    static void recordBlend(VkCommandBuffer cb, void* data);
};
