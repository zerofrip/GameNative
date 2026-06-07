package app.gamenative.utils

import com.winlator.container.Container

/**
 * Compositor-native frame generation (Gamescope Phase 1 style).
 *
 * Runs inside GameNative's VulkanRenderer after X11 compositing — not a Vulkan
 * layer inside the container. Works on any Bionic container using the modern
 * Vulkan renderer.
 */
object CompositorFrameGenManager {
    const val EXTRA_ENABLED = "compositorFrameGenEnabled"

    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    @JvmStatic
    fun isEnabled(container: Container): Boolean =
        isSupported(container) &&
            container.getExtra(EXTRA_ENABLED, "false").equals("true", ignoreCase = true)
}
