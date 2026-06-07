package app.gamenative.rendering

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RendererFallbackCoordinatorTest {

    @Before
    fun setUp() {
        RendererFallbackCoordinator.markGameLaunchStarted()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun handleEarlyFailure_schedulesFallbackOnce() {
        val container = mockk<Container>(relaxed = true)
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_FALLBACK_USED) } returns ""
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_ACTIVE) } returns ""

        val launcher = mockk<GuestProgramLauncherComponent>(relaxed = true)
        val envVars = EnvVars()
        every { launcher.envVars } returns envVars

        val gameConfig = GameLaunchConfig(
            rendererMode = RendererMode.DXVK,
            fallbackRenderer = RendererMode.WINED3D,
        )

        mockkObject(RendererManager)
        every {
            RendererManager.applyToLaunchEnv(any(), any(), any(), any(), any())
        } returns RendererMode.WINED3D

        val scheduled = RendererFallbackCoordinator.handleEarlyFailure(
            context = mockk(relaxed = true),
            container = container,
            gameConfig = gameConfig,
            guestLauncher = launcher,
            status = 1,
        )

        assertTrue(scheduled)
        verify { container.putExtra(RendererFallbackCoordinator.EXTRA_ACTIVE, "wined3d") }
        verify { container.putExtra(RendererFallbackCoordinator.EXTRA_FALLBACK_USED, "1") }
        verify { launcher.start() }
    }

    @Test
    fun handleEarlyFailure_doesNotLoopWhenAlreadyUsed() {
        val container = mockk<Container>(relaxed = true)
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_FALLBACK_USED) } returns "1"

        val launcher = mockk<GuestProgramLauncherComponent>(relaxed = true)
        val gameConfig = GameLaunchConfig(fallbackRenderer = RendererMode.WINED3D)

        val scheduled = RendererFallbackCoordinator.handleEarlyFailure(
            context = mockk(relaxed = true),
            container = container,
            gameConfig = gameConfig,
            guestLauncher = launcher,
            status = 1,
        )

        assertFalse(scheduled)
        verify(exactly = 0) { launcher.start() }
    }

    @Test
    fun shouldUsePersistedMode_whenFallbackFlagSet() {
        val container = mockk<Container>(relaxed = true)
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_FALLBACK_USED) } returns "1"
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_ACTIVE) } returns "wined3d"

        assertTrue(RendererFallbackCoordinator.shouldUsePersistedMode(container))
        assertEquals(RendererMode.WINED3D, RendererFallbackCoordinator.getPersistedActiveMode(container))
    }

    @Test
    fun onSuccessfulRun_clearsFallbackUsedFlag() {
        val container = mockk<Container>(relaxed = true)
        every { container.getExtra(RendererFallbackCoordinator.EXTRA_FALLBACK_USED) } returns "1"

        RendererFallbackCoordinator.onSuccessfulRun(container)

        verify { container.putExtra(RendererFallbackCoordinator.EXTRA_FALLBACK_USED, "") }
        verify { container.saveData() }
    }
}
