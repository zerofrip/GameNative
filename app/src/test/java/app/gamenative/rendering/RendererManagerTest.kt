package app.gamenative.rendering

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric loads android.os stubs for [com.winlator.container.Container] static init. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RendererManagerTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun parseFromExecArgs_findsMode() {
        assertEquals(
            RendererMode.ZINK,
            RendererManager.parseFromExecArgs("foo --gamenative-renderer=zink bar"),
        )
        assertEquals(
            RendererMode.DXVK,
            RendererManager.parseFromExecArgs("--GAMENATIVE-RENDERER=dxvk"),
        )
    }

    @Test
    fun parseFromExecArgs_returnsNullWhenAbsent() {
        assertNull(RendererManager.parseFromExecArgs(""))
        assertNull(RendererManager.parseFromExecArgs("--other=1"))
    }

    @Test
    fun rendererMode_parsesUppercaseAliases() {
        assertEquals(RendererMode.DXVK, RendererMode.fromStringOrNull("DXVK"))
        assertEquals(RendererMode.ZINK, RendererMode.fromStringOrNull("ZINK"))
        assertEquals(RendererMode.WINED3D, RendererMode.fromStringOrNull("WINED3D"))
    }

    @Test
    fun resolveExplicitMode_prefersExecArgsOverJson() {
        val c = mockk<Container>(relaxed = true)
        every { c.execArgs } returns "--gamenative-renderer=wined3d"
        every { c.getExtra(RendererManager.EXTRA_RENDERER) } returns "dxvk"
        val json = GameLaunchConfig(rendererMode = RendererMode.ZINK)
        assertEquals(RendererMode.WINED3D, RendererManager.resolveExplicitMode(c, json))
    }

    @Test
    fun clearExclusiveRendererEnv_removesOverlappingKeys() {
        val env = EnvVars()
        env.put("PROTON_USE_WINED3D", "1")
        env.put("DXVK", "1")
        env.put("GALLIUM_DRIVER", "zink")
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
        env.put("LIBGL_KOPPER_DRI2", "1")
        env.put("ZINK_DESCRIPTORS", "1")
        env.put("DXVK_CONFIG", "should-remain")
        RendererEnvironmentInjector.clearExclusiveRendererEnv(env)
        assertFalse(env.has("PROTON_USE_WINED3D"))
        assertFalse(env.has("DXVK"))
        assertFalse(env.has("GALLIUM_DRIVER"))
        assertFalse(env.has("MESA_LOADER_DRIVER_OVERRIDE"))
        assertFalse(env.has("LIBGL_KOPPER_DRI2"))
        assertFalse(env.has("ZINK_DESCRIPTORS"))
        assertTrue(env.has("DXVK_CONFIG"))
    }

    @Test
    fun applyProfile_dxvk_leavesNoZinkOrWineD3dKeys() {
        val env = EnvVars()
        env.put("PROTON_USE_WINED3D", "1")
        env.put("GALLIUM_DRIVER", "zink")
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
        RendererEnvironmentInjector.clearExclusiveRendererEnv(env)
        RendererEnvironmentInjector.applyProfile(RendererMode.DXVK, env)
        assertEquals("1", env.get("DXVK"))
        assertFalse(env.has("PROTON_USE_WINED3D"))
        assertFalse(env.has("GALLIUM_DRIVER"))
        assertFalse(env.has("MESA_LOADER_DRIVER_OVERRIDE"))
    }

    @Test
    fun gameLaunchConfig_resolutionTokens() {
        assertEquals("1600x900", GameLaunchConfig.resolveResolutionToken("900p"))
        assertEquals("1920x1080", GameLaunchConfig.resolveResolutionToken("1080p"))
        assertEquals("1280x720", GameLaunchConfig.resolveResolutionToken("720p"))
        assertEquals("1440x900", GameLaunchConfig.resolveResolutionToken("1440x900"))
    }

    @Test
    fun gameLaunchConfig_parseJson() {
        val cfg = GameLaunchConfig.parse(
            org.json.JSONObject(
                """{"game_id":"example","renderer":"zink","resolution":"900p","fsr":true,"require_custom_mesa":true}""",
            ),
        )
        assertEquals("example", cfg.gameId)
        assertEquals(RendererMode.ZINK, cfg.rendererMode)
        assertEquals("900p", cfg.resolution)
        assertEquals(true, cfg.fsr)
        assertEquals(true, cfg.requireCustomMesa)
    }

    @Test
    fun gameLaunchConfig_parseJsonWithAliasesAndFallback() {
        val cfg = GameLaunchConfig.parse(
            org.json.JSONObject(
                """{"gameId":"steam_game","renderer":"DXVK","fallbackRenderer":"WINED3D","fsr":false}""",
            ),
        )
        assertEquals("steam_game", cfg.gameId)
        assertEquals(RendererMode.DXVK, cfg.rendererMode)
        assertEquals(RendererMode.WINED3D, cfg.fallbackRenderer)
    }

    @Test
    fun gameLaunchConfig_parseJsonSnakeCaseFallback() {
        val cfg = GameLaunchConfig.parse(
            org.json.JSONObject("""{"fallback_renderer":"zink"}"""),
        )
        assertEquals(RendererMode.ZINK, cfg.fallbackRenderer)
    }

    @Test
    fun resolveEffectiveMode_gpuDefaults() {
        mockkObject(GpuRuntimeInfo)
        val context = mockk<Context>(relaxed = true)
        val container = mockk<Container>(relaxed = true)
        every { container.execArgs } returns ""
        every { container.getExtra(RendererManager.EXTRA_RENDERER) } returns ""
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_FALLBACK_USED) } returns ""

        every { GpuRuntimeInfo.classifyVendor(context) } returns GpuRuntimeInfo.GpuVendorClass.MALI
        assertEquals(RendererMode.WINED3D, RendererManager.resolveEffectiveMode(context, container, null))

        every { GpuRuntimeInfo.classifyVendor(context) } returns GpuRuntimeInfo.GpuVendorClass.ADRENO
        assertEquals(RendererMode.DXVK, RendererManager.resolveEffectiveMode(context, container, null))

        every { GpuRuntimeInfo.classifyVendor(context) } returns GpuRuntimeInfo.GpuVendorClass.OTHER
        assertEquals(RendererMode.ZINK, RendererManager.resolveEffectiveMode(context, container, null))
    }

    @Test
    fun resolveEffectiveMode_usesPersistedFallbackWhenFlagSet() {
        mockkObject(GpuRuntimeInfo)
        val context = mockk<Context>(relaxed = true)
        val container = mockk<Container>(relaxed = true)
        every { container.execArgs } returns ""
        every { container.getExtra(RendererManager.EXTRA_RENDERER) } returns ""
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_FALLBACK_USED) } returns "1"
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_ACTIVE) } returns "wined3d"
        every { GpuRuntimeInfo.classifyVendor(context) } returns GpuRuntimeInfo.GpuVendorClass.ADRENO

        val json = GameLaunchConfig(rendererMode = RendererMode.DXVK)
        assertEquals(RendererMode.WINED3D, RendererManager.resolveEffectiveMode(context, container, json))
    }
}
