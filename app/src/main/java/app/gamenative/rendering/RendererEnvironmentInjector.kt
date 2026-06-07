package app.gamenative.rendering

import com.winlator.core.envvars.EnvVars
import timber.log.Timber

/**
 * Applies mutually exclusive renderer environment variables for exactly one [RendererMode].
 * Called by [RendererManager] after graphics-driver extraction and before Wine starts.
 */
object RendererEnvironmentInjector {
    private const val TAG = "RendererEnvironmentInjector"

    /**
     * Keys exclusively owned by renderer switching — cleared before applying exactly one profile.
     */
    val EXCLUSIVE_RENDERER_KEYS = arrayOf(
        "PROTON_USE_WINED3D",
        "DXVK",
        "DXVK_ASYNC",
        "DXVK_GPLASYNCCACHE",
        "MESA_LOADER_DRIVER_OVERRIDE",
        "GALLIUM_DRIVER",
        "LIBGL_KOPPER_DRI2",
        "LIBGL_KOPPER_DISABLE",
        "ZINK_DESCRIPTORS",
        "ZINK_DEBUG",
    )

    fun clearExclusiveRendererEnv(envVars: EnvVars) {
        for (k in EXCLUSIVE_RENDERER_KEYS) {
            envVars.remove(k)
        }
    }

    /**
     * Applies the env profile for [mode]. Call [clearExclusiveRendererEnv] immediately before this.
     * DXVK async env is applied separately by [RendererManager] from container dxwrapper config.
     */
    fun applyProfile(mode: RendererMode, envVars: EnvVars) {
        when (mode) {
            RendererMode.WINED3D -> {
                envVars.put("PROTON_USE_WINED3D", "1")
            }
            RendererMode.DXVK -> {
                envVars.put("DXVK", "1")
            }
            RendererMode.ZINK -> {
                envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
                envVars.put("GALLIUM_DRIVER", "zink")
                envVars.put("LIBGL_KOPPER_DRI2", "1")
            }
        }
        Timber.tag(TAG).d(
            "Applied profile %s: PROTON_USE_WINED3D=%s DXVK=%s GALLIUM_DRIVER=%s MESA_OVERRIDE=%s",
            mode.wireValue,
            envVars.get("PROTON_USE_WINED3D"),
            envVars.get("DXVK"),
            envVars.get("GALLIUM_DRIVER"),
            envVars.get("MESA_LOADER_DRIVER_OVERRIDE"),
        )
    }
}
