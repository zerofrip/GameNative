package app.gamenative.rendering

import android.content.Context
import com.winlator.core.GPUHelper
import com.winlator.core.GPUInformation
import timber.log.Timber

/**
 * Runtime GPU classification and capability probes used by [RendererManager].
 * All probes are defensive so JNI / EGL quirks do not crash the host app.
 */
object GpuRuntimeInfo {
    private const val TAG = "GpuRuntimeInfo"

    enum class GpuVendorClass {
        MALI,
        ADRENO,
        OTHER,
    }

    fun classifyVendor(context: Context?): GpuVendorClass {
        return try {
            val r = GPUInformation.getRenderer(context).lowercase()
            when {
                r.contains("mali") -> GpuVendorClass.MALI
                r.contains("adreno") -> GpuVendorClass.ADRENO
                else -> GpuVendorClass.OTHER
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "GPU renderer classification failed")
            GpuVendorClass.OTHER
        }
    }

    fun isMali(context: Context?): Boolean = classifyVendor(context) == GpuVendorClass.MALI

    fun isAdreno(context: Context?): Boolean = classifyVendor(context) == GpuVendorClass.ADRENO

    /**
     * True when the bundled Vulkan probe reports a valid API version (major ≥ 1).
     * Uses [GPUHelper.vkGetApiVersion] directly — not [GPUHelper.vkGetApiVersionSafe], which can mask failures.
     */
    fun isVulkanAvailableForZink(): Boolean {
        return try {
            val v = GPUHelper.vkGetApiVersion()
            if (v == 0) {
                Timber.tag(TAG).w("Vulkan API version is 0 — treating as unavailable for Zink")
                return false
            }
            GPUHelper.vkVersionMajor(v) >= 1
        } catch (e: UnsatisfiedLinkError) {
            Timber.tag(TAG).w(e, "Vulkan native library unavailable")
            false
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Vulkan probe failed")
            false
        }
    }
}
