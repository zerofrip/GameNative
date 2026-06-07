package app.gamenative.rendering

import android.content.Context
import app.gamenative.ui.util.ScreenEffectsConfig
import com.winlator.container.Container
import com.winlator.core.DefaultVersion
import com.winlator.core.DXVKHelper
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * Injects renderer-specific environment variables and optional custom Mesa paths
 * before Wine starts. Integrates with [com.winlator.xenvironment.components.BionicProgramLauncherComponent]
 * via merged [EnvVars] (merged after base paths are set in Java).
 *
 * Env vars controlled here are mutually exclusive: one [RendererMode] owns the pipeline after
 * [RendererEnvironmentInjector.clearExclusiveRendererEnv].
 */
object RendererManager {
    private const val TAG = "RendererManager"
    const val EXTRA_RENDERER = "gamenative_renderer"
    private const val LAUNCH_ARG_PATTERN = """--gamenative-renderer=(\w+)"""

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
     * Effective mode after explicit config, persisted fallback, and GPU-aware defaults.
     */
    fun resolveEffectiveMode(context: Context, container: Container, gameConfig: GameLaunchConfig?): RendererMode {
        parseFromExecArgs(container.execArgs)?.let { return it }

        if (RendererFallbackCoordinator.shouldUsePersistedMode(container)) {
            RendererFallbackCoordinator.getPersistedActiveMode(container)?.let {
                Timber.tag(TAG).i("Renderer from persisted fallback active mode: ${it.wireValue}")
                return it
            }
        }

        resolveExplicitMode(container, gameConfig)?.let { return it }

        return gpuDefault(context)
    }

    private fun gpuDefault(context: Context): RendererMode {
        return when (GpuRuntimeInfo.classifyVendor(context)) {
            GpuRuntimeInfo.GpuVendorClass.MALI -> {
                Timber.tag(TAG).i("No explicit renderer; Mali GPU — defaulting to WineD3D")
                RendererMode.WINED3D
            }
            GpuRuntimeInfo.GpuVendorClass.ADRENO -> {
                Timber.tag(TAG).i("No explicit renderer; Adreno GPU — defaulting to DXVK")
                RendererMode.DXVK
            }
            GpuRuntimeInfo.GpuVendorClass.OTHER -> {
                Timber.tag(TAG).i("No explicit renderer; unknown/other GPU — defaulting to Zink")
                RendererMode.ZINK
            }
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
    ): RendererMode {
        injectCustomMesaPathsIfSafe(context, imageFs, envVars, container)

        var mode = resolveEffectiveMode(context, container, gameConfig)

        val isPanVkRuntime = isPanVkGraphicsRuntime(container, envVars)
        val honorWineD3dPath =
            resolveExplicitMode(container, gameConfig) == RendererMode.WINED3D ||
                container.dxWrapper.lowercase(Locale.US).let { w ->
                    w == "wined3d" || w.startsWith("wined3d-") || w == "original-wined3d"
                }
        if (mode == RendererMode.WINED3D && isPanVkRuntime && !honorWineD3dPath) {
            Timber.tag(TAG).i("PanVK runtime detected; switching renderer mode from WineD3D to DXVK")
            mode = RendererMode.DXVK
        }
        if (mode == RendererMode.WINED3D && isPanVkRuntime && honorWineD3dPath) {
            Timber.tag(TAG).w(
                "PanVK + WineD3D path requested — not upgrading to DXVK (OpenGL/Zink may still fail on some devices)",
            )
        }

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

        RendererEnvironmentInjector.clearExclusiveRendererEnv(envVars)
        RendererEnvironmentInjector.applyProfile(mode, envVars)
        if (mode == RendererMode.DXVK) {
            applyDxvkAsyncPipelineEnvFromContainer(container, envVars)
            applyDxvkCompileLayerHints(envVars)
        }

        envVars.put("GAMENATIVE_RENDERER", mode.wireValue)
        envVars.put(
            "GAMENATIVE_GPU_CLASS",
            GpuRuntimeInfo.classifyVendor(context).name.lowercase(),
        )
        envVars.put(
            "GAMENATIVE_VULKAN_OK",
            if (GpuRuntimeInfo.isVulkanAvailableForZink()) "1" else "0",
        )

        RendererFallbackCoordinator.recordActiveMode(container, mode)

        Timber.tag(TAG).i(
            "Active renderer mode: %s (GPU=%s, vulkan=%s)",
            mode.wireValue,
            GpuRuntimeInfo.classifyVendor(context),
            envVars.get("GAMENATIVE_VULKAN_OK"),
        )
        logExclusiveEnvSnapshot(envVars)
        logMesaDiagnostics(context, envVars, mode)
        return mode
    }

    internal fun isPanVkGraphicsRuntime(container: Container, envVars: EnvVars): Boolean {
        if (container.graphicsDriver.equals("panvk", ignoreCase = true)) return true
        val icd = envVars.get("VK_ICD_FILENAMES") ?: return false
        val lower = icd.lowercase(Locale.US)
        return lower.contains("panvk_manifest") ||
            lower.contains("panfrost_icd") ||
            lower.contains("/panvk/")
    }

    /** @deprecated Use [RendererEnvironmentInjector.clearExclusiveRendererEnv] */
    fun clearExclusiveRendererEnv(envVars: EnvVars) {
        RendererEnvironmentInjector.clearExclusiveRendererEnv(envVars)
    }

    private fun logExclusiveEnvSnapshot(envVars: EnvVars) {
        val snapshot = RendererEnvironmentInjector.EXCLUSIVE_RENDERER_KEYS
            .mapNotNull { key -> envVars.get(key)?.let { key to it } }
            .joinToString(", ") { "${it.first}=${it.second}" }
        Timber.tag(TAG).d("Exclusive renderer env after injection: %s", snapshot.ifEmpty { "(none)" })
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

    private fun applyDxvkAsyncPipelineEnvFromContainer(container: Container, envVars: EnvVars) {
        val cfg = DXVKHelper.parseConfig(container.dxWrapperConfig)
        val async = cfg.get("async", DefaultVersion.ASYNC)
        if (async.isNotEmpty() && async != "0") {
            envVars.put("DXVK_ASYNC", "1")
        } else {
            envVars.remove("DXVK_ASYNC")
        }
        val asyncCache = cfg.get("asyncCache", DefaultVersion.ASYNC_CACHE)
        if (asyncCache.isNotEmpty() && asyncCache != "0") {
            envVars.put("DXVK_GPLASYNCCACHE", "1")
        } else {
            envVars.remove("DXVK_GPLASYNCCACHE")
        }
    }

    private fun applyDxvkCompileLayerHints(envVars: EnvVars) {
        if (!envVars.has("MESA_SHADER_CACHE_DISABLE")) {
            envVars.put("MESA_SHADER_CACHE_DISABLE", "false")
        }
        if (!envVars.has("MESA_SHADER_CACHE_MAX_SIZE")) {
            envVars.put("MESA_SHADER_CACHE_MAX_SIZE", "512MB")
        }
    }

    private fun logMesaDiagnostics(context: Context, envVars: EnvVars, mode: RendererMode) {
        val custom = GameLaunchConfig.customMesaLibRoot(context)
        Timber.tag(TAG).d(
            "Mesa diagnostics: mode=%s gpu=%s customDir=%s bundleOk=%s LD_LIBRARY_PATH(head)=%s LIBGL_DRIVERS_PATH=%s",
            mode.wireValue,
            GpuRuntimeInfo.classifyVendor(context),
            custom.path,
            customMesaBundleLooksValid(context),
            envVars.get("LD_LIBRARY_PATH").take(180),
            envVars.get("LIBGL_DRIVERS_PATH"),
        )
    }
}
