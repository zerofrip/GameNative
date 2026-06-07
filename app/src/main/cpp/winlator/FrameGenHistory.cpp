#include "FrameGenHistory.hpp"

#include <algorithm>
#include <android/log.h>

#define FGLOG(...) __android_log_print(ANDROID_LOG_DEBUG, "Winlator_FrameGen", __VA_ARGS__)
#define FGLOG_E(...) __android_log_print(ANDROID_LOG_ERROR, "Winlator_FrameGen", __VA_ARGS__)

void FrameGenHistory::reset(FrameGenVulkan* vulkan) {
    if (vulkan) {
        vulkan->destroyImage(prev_);
        vulkan->destroyImage(curr_);
        vulkan->destroyImage(interp_);
    }
    prev_ = {};
    curr_ = {};
    interp_ = {};
    hasPrevious_ = false;
    width_ = 0;
    height_ = 0;
    format_ = VK_FORMAT_UNDEFINED;
    vulkan_ = vulkan;
}

bool FrameGenHistory::ensureResources(FrameGenVulkan* vulkan, uint32_t width, uint32_t height, VkFormat format) {
    if (!vulkan) return false;
    if (prev_.img && curr_.img && interp_.img &&
        width_ == width && height_ == height && format_ == format)
        return true;

    reset(vulkan);
    vulkan_ = vulkan;

    if (!vulkan->createHistoryTexture(width, height, format, prev_) ||
        !vulkan->createHistoryTexture(width, height, format, curr_) ||
        !vulkan->createInterpOutput(width, height, format, interp_)) {
        FGLOG_E("failed to allocate framegen textures");
        reset(vulkan);
        return false;
    }

    width_ = width;
    height_ = height;
    format_ = format;
    return true;
}

bool FrameGenHistory::copyFromSwapchain(FrameGenVulkan* vulkan, VkImage src, VkImageLayout srcLayout, FrameGenImage& dst) {
    return vulkan->copyImage(src, srcLayout, dst);
}

bool FrameGenHistory::blendPreviousAndCurrent(FrameGenVulkan* vulkan) {
    if (!hasPrevious_ || !prev_.img || !curr_.img || !interp_.img)
        return false;
    return vulkan->blend(prev_, curr_, interp_);
}

bool FrameGenHistory::updateAndBlend(FrameGenVulkan* vulkan, VkImage composed, VkImageLayout composedLayout, bool* needsInterp) {
    *needsInterp = false;
    if (!vulkan || !composed || !curr_.img || !prev_.img || !interp_.img)
        return false;

    if (!copyFromSwapchain(vulkan, composed, composedLayout, curr_))
        return false;

    if (!hasPrevious_) {
        if (!copyFromSwapchain(vulkan, composed, composedLayout, prev_))
            return false;
        hasPrevious_ = true;
        FGLOG("priming history");
        return true;
    }

    if (!blendPreviousAndCurrent(vulkan))
        return false;

    std::swap(prev_, curr_);
    *needsInterp = true;
    return true;
}
