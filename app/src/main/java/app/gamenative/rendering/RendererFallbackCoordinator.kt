package app.gamenative.rendering

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects early guest-program failure and performs one automatic relaunch with
 * [GameLaunchConfig.fallbackRenderer], persisting the fallback mode for future launches.
 */
object RendererFallbackCoordinator {
    private const val TAG = "RendererFallback"
    const val EXTRA_ACTIVE = "gamenative_renderer_active"
    const val EXTRA_FALLBACK_USED = "gamenative_renderer_fallback_used"
    const val EXTRA_LAST_FAILURE = "gamenative_renderer_last_failure"
    const val EARLY_FAILURE_WINDOW_MS = 60_000L

    private val gameLaunchStartedAtMs = AtomicLong(0L)
    private var launchEnvSnapshot: EnvVars? = null

    fun markGameLaunchStarted() {
        gameLaunchStartedAtMs.set(System.currentTimeMillis())
    }

    fun saveLaunchEnvSnapshot(envVars: EnvVars) {
        launchEnvSnapshot = EnvVars().apply { putAll(envVars) }
    }

    fun elapsedSinceLaunchMs(): Long {
        val started = gameLaunchStartedAtMs.get()
        if (started == 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - started
    }

    fun shouldUsePersistedMode(container: Container): Boolean {
        return container.getExtra(EXTRA_FALLBACK_USED) == "1"
    }

    fun getPersistedActiveMode(container: Container): RendererMode? {
        return RendererMode.fromStringOrNull(container.getExtra(EXTRA_ACTIVE))
    }

    fun recordActiveMode(container: Container, mode: RendererMode) {
        container.putExtra(EXTRA_ACTIVE, mode.wireValue)
    }

    fun onSuccessfulRun(container: Container) {
        if (container.getExtra(EXTRA_FALLBACK_USED).isNotEmpty()) {
            container.putExtra(EXTRA_FALLBACK_USED, "")
            container.saveData()
            Timber.tag(TAG).i("Cleared fallback-used flag after successful run")
        }
    }

    /**
     * @return true when a fallback relaunch was scheduled (caller must not emit termination yet).
     */
    fun handleEarlyFailure(
        context: Context,
        container: Container,
        gameConfig: GameLaunchConfig?,
        guestLauncher: GuestProgramLauncherComponent,
        status: Int,
    ): Boolean {
        if (status == 0) return false
        val elapsed = elapsedSinceLaunchMs()
        if (elapsed > EARLY_FAILURE_WINDOW_MS) {
            Timber.tag(TAG).w(
                "Guest exit status=%d after %dms (outside early-failure window); not retrying renderer fallback",
                status,
                elapsed,
            )
            return false
        }

        val fallbackMode = gameConfig?.fallbackRenderer
        if (fallbackMode == null) {
            recordFailure(container, status, "no fallbackRenderer configured")
            return false
        }
        if (shouldUsePersistedMode(container)) {
            recordFailure(container, status, "fallback already used this session")
            return false
        }

        Timber.tag(TAG).i(
            "Early failure status=%d after %dms — switching to fallback renderer %s and relaunching once",
            status,
            elapsed,
            fallbackMode.wireValue,
        )
        container.putExtra(EXTRA_ACTIVE, fallbackMode.wireValue)
        container.putExtra(EXTRA_FALLBACK_USED, "1")
        recordFailure(container, status, "early exit → ${fallbackMode.wireValue}")
        container.saveData()

        val envVars = guestLauncher.envVars ?: EnvVars()
        launchEnvSnapshot?.let { snapshot ->
            envVars.clear()
            envVars.putAll(snapshot)
        }
        RendererManager.applyToLaunchEnv(
            context,
            ImageFs.find(context),
            envVars,
            container,
            gameConfig,
        )
        guestLauncher.setEnvVars(envVars)
        markGameLaunchStarted()
        guestLauncher.start()
        return true
    }

    private fun recordFailure(container: Container, status: Int, reason: String) {
        val entry = "status=$status reason=$reason at=${System.currentTimeMillis()}"
        container.putExtra(EXTRA_LAST_FAILURE, entry)
        container.saveData()
        Timber.tag(TAG).w("Renderer launch failure recorded: %s", entry)
    }
}
