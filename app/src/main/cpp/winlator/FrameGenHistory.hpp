#pragma once

#include "FrameGenVulkan.hpp"
#include <cstdint>

class FrameGenHistory {
public:
    void reset(FrameGenVulkan* vulkan);
    bool ensureResources(FrameGenVulkan* vulkan, uint32_t width, uint32_t height, VkFormat format);
    bool hasPrevious() const { return hasPrevious_; }

    const FrameGenImage& previous() const { return prev_; }
    const FrameGenImage& current() const { return curr_; }
    const FrameGenImage& interpOutput() const { return interp_; }

    bool updateAndBlend(FrameGenVulkan* vulkan, VkImage composed, VkImageLayout composedLayout, bool* needsInterp);

private:
    bool copyFromSwapchain(FrameGenVulkan* vulkan, VkImage src, VkImageLayout srcLayout, FrameGenImage& dst);
    bool blendPreviousAndCurrent(FrameGenVulkan* vulkan);

    FrameGenImage prev_{};
    FrameGenImage curr_{};
    FrameGenImage interp_{};
    bool hasPrevious_ = false;
    uint32_t width_ = 0;
    uint32_t height_ = 0;
    VkFormat format_ = VK_FORMAT_UNDEFINED;
    FrameGenVulkan* vulkan_ = nullptr;
};
