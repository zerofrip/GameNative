package app.gamenative.rendering

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import io.mockk.every
import io.mockk.mockk
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
@Config(sdk = [28])
class RendererManagerTest {

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
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
        env.put("LIBGL_KOPPER_DRI2", "1")
        env.put("DXVK_CONFIG", "should-remain")
        RendererManager.clearExclusiveRendererEnv(env)
        assertFalse(env.has("PROTON_USE_WINED3D"))
        assertFalse(env.has("DXVK"))
        assertFalse(env.has("MESA_LOADER_DRIVER_OVERRIDE"))
        assertFalse(env.has("LIBGL_KOPPER_DRI2"))
        assertTrue(env.has("DXVK_CONFIG"))
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
}
