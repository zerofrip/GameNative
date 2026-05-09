package app.gamenative.utils

import android.content.Context
import android.net.Uri
import app.gamenative.R
import app.gamenative.service.SteamService
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.contents.PanVkDriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ManifestInstallResult(
    val success: Boolean,
    val message: String,
)

object ManifestInstaller {
    private suspend fun fetchFileWithRetries(
        url: String,
        destFile: File,
        onProgress: (Float) -> Unit,
        attempts: Int = 3,
    ) {
        var lastError: Throwable? = null
        repeat(attempts) { index ->
            try {
                SteamService.fetchFile(url, destFile, onProgress)
                return
            } catch (e: Exception) {
                lastError = e
                val msg = e.message.orEmpty()
                val isHttp5xx = msg.startsWith("HTTP 5")
                val shouldRetry = index < attempts - 1 && (isHttp5xx || e is java.io.IOException)
                if (!shouldRetry) throw e
                val backoffMs = (index + 1) * 1500L
                Timber.w(e, "ManifestInstaller: retrying driver download (${index + 1}/$attempts) in ${backoffMs}ms for $url")
                delay(backoffMs)
            }
        }
        throw lastError ?: IllegalStateException("Failed to download file: $url")
    }

    suspend fun downloadAndInstallDriver(
        context: Context,
        entry: ManifestEntry,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult = withContext(Dispatchers.IO) {
        var destFile: File? = null
        try {
            destFile = File(context.cacheDir, entry.url.substringAfterLast("/"))
            fetchFileWithRetries(entry.url, destFile, onProgress)
            val uri = Uri.fromFile(destFile)
            val stack = entry.driverStack?.lowercase()
            val name = if (stack == "panvk") {
                PanVkDriverManager(context).installDriver(uri)
            } else {
                AdrenotoolsManager(context).installDriver(uri)
            }
            if (name.isEmpty()) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }
            return@withContext ManifestInstallResult(
                success = true,
                message = context.getString(R.string.manifest_install_success, entry.name),
            )
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: driver install failed")
            return@withContext ManifestInstallResult(
                success = false,
                message = context.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName),
            )
        } finally {
            destFile?.delete()
        }
    }

    /**
     * Shared helper to install a single manifest entry (driver or content).
     *
     * UI layers should provide [onProgress] to update their own state and then
     * handle the returned [ManifestInstallResult] (e.g. to show a Toast or
     * refresh installed-content lists).
     */
    suspend fun installManifestEntry(
        context: Context,
        entry: ManifestEntry,
        isDriver: Boolean,
        contentType: ContentProfile.ContentType? = null,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult {
        return if (isDriver) {
            downloadAndInstallDriver(context, entry, onProgress)
        } else {
            val type = contentType
                ?: throw IllegalArgumentException("contentType must be provided when installing manifest content")
            downloadAndInstallContent(context, entry, type, onProgress)
        }
    }

    suspend fun downloadAndInstallContent(
        context: Context,
        entry: ManifestEntry,
        expectedType: ContentProfile.ContentType,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult = withContext(Dispatchers.IO) {
        var destFile: File? = null
        try {
            destFile = File(context.cacheDir, entry.url.substringAfterLast("/"))
            SteamService.fetchFile(entry.url, destFile, onProgress)
            val uri = Uri.fromFile(destFile)
            val mgr = ContentsManager(context)

            val (profile, fail, error) = extractContent(mgr, uri)
            if (profile == null) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }

            val installed = finishInstall(mgr, profile)
            if (!installed) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }

            return@withContext ManifestInstallResult(
                success = true,
                message = context.getString(R.string.manifest_install_success, entry.name),
            )
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: content install failed")
            return@withContext ManifestInstallResult(
                success = false,
                message = context.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName),
            )
        } finally {
            destFile?.delete()
        }
    }

    private suspend fun extractContent(
        mgr: ContentsManager,
        uri: Uri,
    ): Triple<ContentProfile?, ContentsManager.InstallFailedReason?, Exception?> = withContext(Dispatchers.IO) {
        var profile: ContentProfile? = null
        var failReason: ContentsManager.InstallFailedReason? = null
        var err: Exception? = null
        val latch = CountDownLatch(1)
        try {
            mgr.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    failReason = reason
                    err = e
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    profile = profileArg
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            err = e
            latch.countDown()
        }
        if (!latch.await(240, TimeUnit.SECONDS)) {
            err = Exception("Installation timed out")
        }
        Triple(profile, failReason, err)
    }

    private suspend fun finishInstall(
        mgr: ContentsManager,
        profile: ContentProfile,
    ): Boolean = withContext(Dispatchers.IO) {
        var success = false
        val latch = CountDownLatch(1)
        try {
            mgr.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    success = true
                    latch.countDown()
                }
            })
        } catch (_: Exception) {
            latch.countDown()
        }
        if (!latch.await(240, TimeUnit.SECONDS)) {
            Timber.w("ManifestInstaller: finishInstall timed out after 240 seconds")
            return@withContext false
        }
        return@withContext success
    }
}
