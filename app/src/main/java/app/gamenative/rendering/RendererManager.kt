package app.gamenative.rendering

import android.content.Context
import app.gamenative.ui.util.ScreenEffectsConfig
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import timber.log.Timber
import java.io.File

/**
 * Injects renderer-specific environment variables and optional custom Mesa paths
 * before Wine starts. Integrates with [com.winlator.xenvironment.components.BionicProgramLauncherComponent]
 * via merged [EnvVars] (merged after base paths are set in Java).
 *
 * Env vars controlled here are mutually exclusive: one [RendererMode] owns the pipeline after
 * [clearExclusiveRendererEnv].
 */
object RendererManager {
    private const val TAG = "RendererManager"
    const val EXTRA_RENDERER = "gamenative_renderer"
    private const val LAUNCH_ARG_PATTERN = """--gamenative-renderer=(\w+)"""

    /**
     * Keys exclusively owned by renderer switching — cleared before applying exactly one profile.
     * (Does not strip unrelated Mesa/driver tuning from [extractGraphicsDriverFiles].)
     */
    private val EXCLUSIVE_RENDERER_KEYS = arrayOf(
        "PROTON_USE_WINED3D",
        "DXVK",
        "DXVK_ASYNC",
        "MESA_LOADER_DRIVER_OVERRIDE",
        "LIBGL_KOPPER_DRI2",
    )

    fun resolveExplicitMode(container: Container, gameConfig: GameLaunchConfig?): RendererMode? {
        parseFromExecArgs(container.execArgs)?.let {
            Timber.tag(TAG).i("Renderer from launch args: ${it.wireValue}")
            return it
        }
        gameConfig?.rendererMode?.let {
            Timber.tag(TAG).i("Renderer from game JSON: ${it.wireValue}")
            return it
        }
        RendererMode.fromStringOrNull(container.getExtra(EXTRA_RENDERER))?.let {
            Timber.tag(TAG).i("Renderer from container extra $EXTRA_RENDERER: ${it.wireValue}")
            return it
        }
        return null
    }

    /**
     * Effective mode after vendor defaults: Mali → WineD3D when nothing explicit.
     * Other GPUs keep “inherit” ([null]) when unset so existing driver extraction stays authoritative.
     */
    fun resolveEffectiveMode(context: Context, container: Container, gameConfig: GameLaunchConfig?): RendererMode? {
        resolveExplicitMode(container, gameConfig)?.let { return it }
        return if (GpuRuntimeInfo.isMali(context)) {
            Timber.tag(TAG).i("No explicit renderer; Mali GPU — defaulting to WineD3D")
            RendererMode.WINED3D
        } else {
            null
        }
    }

    fun parseFromExecArgs(execArgs: String): RendererMode? {
        val m = Regex(LAUNCH_ARG_PATTERN, RegexOption.IGNORE_CASE).find(execArgs) ?: return null
        return RendererMode.fromStringOrNull(m.groupValues[1])
    }

    fun isCustomMesaPresent(context: Context): Boolean {
        val root = GameLaunchConfig.customMesaLibRoot(context)
        if (!root.isDirectory) return false
        val hasGl = File(root, "libGL.so.1").isFile ||
            File(root, "libGL.so").isFile ||
            root.list()?.any { it.startsWith("libGL.so") } == true
        val dri = File(root, "dri")
        val hasDri = dri.isDirectory && (dri.list()?.isNotEmpty() == true)
        return hasGl || hasDri
    }

    /**
     * Avoid prepending a broken tree to [LD_LIBRARY_PATH] (missing GL / DRI would cause Wine loader failures).
     */
    fun customMesaBundleLooksValid(context: Context): Boolean {
        val root = GameLaunchConfig.customMesaLibRoot(context)
        if (!root.isDirectory) return false
        return try {
            when {
                File(root, "libGL.so.1").isFile || File(root, "libGL.so").isFile -> true
                root.list()?.any { it.startsWith("libGL.so") } == true -> true
                else -> {
                    val dri = File(root, "dri")
                    dri.isDirectory && dri.list()?.any { it.endsWith(".so") } == true
                }
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "customMesaBundleLooksValid failed")
            false
        }
    }

    /**
     * Applies resolution / FSR hints from [GameLaunchConfig] to the container (persisted).
     */
    fun applyPerGameHints(container: Container, cfg: GameLaunchConfig?) {
        if (cfg == null) return
        var changed = false
        cfg.resolution?.let { token ->
            val size = GameLaunchConfig.resolveResolutionToken(token)
            if (size != null && size != container.getScreenSize()) {
                container.setScreenSize(size)
                changed = true
                Timber.tag(TAG).i("Per-game resolution %s -> screenSize=%s", token, size)
            } else if (size == null) {
                Timber.tag(TAG).w("Unknown resolution token: %s", token)
            }
        }
        if (cfg.fsr == true) {
            container.putExtra(
                ScreenEffectsConfig.KEY_SCALING_MODE,
                ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT.toString(),
            )
            changed = true
            Timber.tag(TAG).i("Per-game FSR enabled (scaling mode FSR aspect)")
        }
        if (changed) container.saveData()
    }

    /**
     * After graphics driver env is assembled, applies renderer profile and optional custom Mesa paths.
     *
     * @return the mode actually applied (after gates/fallbacks).
     */
    fun applyToLaunchEnv(
        context: Context,
        imageFs: ImageFs,
        envVars: EnvVars,
        container: Container,
        gameConfig: GameLaunchConfig?,
    ): RendererMode? {
        injectCustomMesaPathsIfSafe(context, imageFs, envVars, container)

        var mode = resolveEffectiveMode(context, container, gameConfig)

        if (mode == null) {
            Timber.tag(TAG).d("No renderer profile (non-Mali default); driver env unchanged by RendererManager")
            logMesaDiagnostics(context, envVars, null)
            return null
        }

        // Zink requires Vulkan in the host stack.
        if (mode == RendererMode.ZINK && !GpuRuntimeInfo.isVulkanAvailableForZink()) {
            Timber.tag(TAG).e("Zink requested but Vulkan is unavailable — falling back to WineD3D")
            mode = RendererMode.WINED3D
        }

        if (mode == RendererMode.ZINK && gameConfig?.requireCustomMesa == true && !isCustomMesaPresent(context)) {
            Timber.tag(TAG).e(
                "Zink with require_custom_mesa but custom Mesa missing under %s — falling back to WineD3D",
                GameLaunchConfig.customMesaLibRoot(context).path,
            )
            mode = RendererMode.WINED3D
        }

        if (mode == RendererMode.ZINK && GpuRuntimeInfo.isMali(context)) {
            Timber.tag(TAG).w(
                "Zink on Mali GPU is experimental; Vulkan and a compatible Mesa/Zink build are required",
            )
        }

        clearExclusiveRendererEnv(envVars)
        applyExclusiveProfile(mode, envVars)

        envVars.put("GAMENATIVE_RENDERER", mode.wireValue)
        envVars.put(
            "GAMENATIVE_GPU_CLASS",
            GpuRuntimeInfo.classifyVendor(context).name.lowercase(),
        )
        envVars.put(
            "GAMENATIVE_VULKAN_OK",
            if (GpuRuntimeInfo.isVulkanAvailableForZink()) "1" else "0",
        )

        Timber.tag(TAG).i(
            "Active renderer mode: %s (GPU=%s, vulkan=%s)",
            mode.wireValue,
            GpuRuntimeInfo.classifyVendor(context),
            envVars.get("GAMENATIVE_VULKAN_OK"),
        )
        logMesaDiagnostics(context, envVars, mode)
        return mode
    }

    /**
     * Removes env vars that would overlap across renderer modes; call once before applying one mode.
     */
    fun clearExclusiveRendererEnv(envVars: EnvVars) {
        for (k in EXCLUSIVE_RENDERER_KEYS) {
            envVars.remove(k)
        }
    }

    private fun injectCustomMesaPathsIfSafe(context: Context, imageFs: ImageFs, envVars: EnvVars, container: Container) {
        val customRoot = GameLaunchConfig.customMesaLibRoot(context)
        if (!customRoot.isDirectory || customRoot.list().isNullOrEmpty()) {
            Timber.tag(TAG).d("Custom Mesa root missing or empty: %s", customRoot.path)
            return
        }
        if (!customMesaBundleLooksValid(context)) {
            Timber.tag(TAG).w(
                "Skipping custom Mesa LD_LIBRARY_PATH prefix — bundle under %s does not look loadable (avoid missing libGL / symbols)",
                customRoot.path,
            )
            return
        }
        val root = imageFs.rootDir
        val bionic = container.containerVariant.equals(Container.BIONIC, ignoreCase = true)
        val baseLd = if (bionic) {
            "${root.path}/usr/lib:/system/lib64"
        } else {
            "${root.path}/usr/lib"
        }
        val customPath = customRoot.absolutePath
        envVars.put("LD_LIBRARY_PATH", "$customPath:$baseLd")
        if (!bionic) {
            val boxPath = "${root.path}/usr/lib/x86_64-linux-gnu"
            envVars.put("BOX64_LD_LIBRARY_PATH", "$customPath:$boxPath")
        }
        val dri = File(customRoot, "dri")
        envVars.put(
            "LIBGL_DRIVERS_PATH",
            if (dri.isDirectory) dri.absolutePath else customPath,
        )
        Timber.tag(TAG).i(
            "Custom Mesa paths: LD_LIBRARY_PATH prefix=%s LIBGL_DRIVERS_PATH=%s",
            customPath,
            envVars.get("LIBGL_DRIVERS_PATH"),
        )
    }

    private fun applyExclusiveProfile(mode: RendererMode, envVars: EnvVars) {
        when (mode) {
            RendererMode.WINED3D -> {
                envVars.put("PROTON_USE_WINED3D", "1")
                // Avoid simultaneous Gallium “zink” loader routing while forcing Wine’s GL driver path.
                envVars.remove("GALLIUM_DRIVER")
                envVars.remove("MESA_LOADER_DRIVER_OVERRIDE")
            }
            RendererMode.DXVK -> {
                envVars.put("DXVK", "1")
                envVars.put("DXVK_ASYNC", "1")
            }
            RendererMode.ZINK -> {
                envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
                envVars.put("GALLIUM_DRIVER", "zink")
                envVars.put("LIBGL_KOPPER_DRI2", "1")
                envVars.remove("LIBGL_KOPPER_DISABLE")
            }
        }
    }

    private fun logMesaDiagnostics(context: Context, envVars: EnvVars, mode: RendererMode?) {
        val custom = GameLaunchConfig.customMesaLibRoot(context)
        Timber.tag(TAG).d(
            "Mesa diagnostics: mode=%s gpu=%s customDir=%s bundleOk=%s LD_LIBRARY_PATH(head)=%s LIBGL_DRIVERS_PATH=%s",
            mode?.wireValue ?: "(inherit)",
            GpuRuntimeInfo.classifyVendor(context),
            custom.path,
            customMesaBundleLooksValid(context),
            envVars.get("LD_LIBRARY_PATH").take(180),
            envVars.get("LIBGL_DRIVERS_PATH"),
        )
    }
}
