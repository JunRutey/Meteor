package com.meteor.memory.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.system.NotificationFeature
import com.meteor.memory.system.UpdateNotificationProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updater (ADR-072).
 *
 * Two phases, both user-driven: [checkForUpdate] asks GitHub which release is
 * newest and parks the answer in [UpdateState.Available]; [startDownload] is
 * what the update dialog / settings button calls after the person agrees to
 * download. Nothing is downloaded without that consent.
 *
 * Downloads try the official GitHub URL and the built-in community mirrors in
 * latency order ([UpdateSources]); whatever source serves the bytes, the file
 * is accepted only if its SHA-256 matches the digest GitHub computed at
 * upload time. A mirror can only be fast or slow, never wrong.
 */
class UpdateService(
    context: Context,
    currentVersionName: String,
    private val notificationFeature: NotificationFeature,
    private val appLog: AppLog,
) {
    private val appContext = context.applicationContext
    private val currentVersion = requireNotNull(ReleaseVersion.parse(currentVersionName))
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    internal val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            // A bug in the updater must never take down the diary: log it and
            // surface a normal download failure instead.
            appLog.error(TAG, "unexpected update coroutine failure", failure)
            _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
        },
    )
    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()
    private val launchCheckStarted = AtomicBoolean(false)

    /** Release found by the latest check; kept so the dialog can start its download. */
    @Volatile
    private var pendingRelease: ReleaseMetadata? = null

    @Volatile
    private var downloadedUpdate: Pair<String, File>? = null

    fun checkOnLaunch() {
        if (launchCheckStarted.compareAndSet(false, true)) checkForUpdate()
    }

    /** Phase one: ask GitHub for the latest release, never download anything. */
    fun checkForUpdate() {
        scope.launch {
            if (!checkMutex.tryLock()) {
                appLog.debug(TAG, "update check already running")
                return@launch
            }
            try {
                notificationFeature.hideUpdateNotification()
                _state.value = UpdateState.Checking
                pendingRelease = null
                appLog.info(TAG, "update check started, current=$currentVersion")
                val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
                pendingRelease = release
                if (release.version <= currentVersion) {
                    appLog.info(TAG, "up to date, latest=${release.version}")
                    _state.value = UpdateState.UpToDate
                    return@launch
                }

                val versionName = release.version.toString()
                appLog.info(TAG, "update available: $versionName")
                _state.value = UpdateState.Available(versionName)
            } catch (failure: UpdateException) {
                appLog.warn(TAG, "update check failed: ${failure.reason}", failure.cause ?: failure)
                notificationFeature.hideUpdateNotification()
                _state.value = UpdateState.Failed(failure.reason)
            } catch (failure: SecurityException) {
                appLog.warn(TAG, "update check blocked by security policy", failure)
                notificationFeature.hideUpdateNotification()
                _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
            } finally {
                checkMutex.unlock()
            }
        }
    }

    /**
     * Phase two: download the release announced by [checkForUpdate]. Called
     * from the update dialog's confirm button and from settings; a download is
     * never started by a timer or by the check itself.
     */
    fun startDownload() {
        val release = pendingRelease ?: run {
            appLog.warn(TAG, "download requested without release metadata")
            _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
            return
        }
        when (_state.value) {
            is UpdateState.Downloading,
            is UpdateState.ReadyToInstall,
            is UpdateState.InstallPermissionRequired,
            -> {
                appLog.debug(TAG, "download already running or finished")
                return
            }
            else -> Unit
        }
        scope.launch {
            if (!downloadMutex.tryLock()) {
                appLog.debug(TAG, "download already running")
                return@launch
            }
            try {
                val versionName = release.version.toString()
                _state.value = UpdateState.Downloading(versionName)
                notificationFeature.showUpdateDownload(
                    UpdateNotificationProgress(versionName, downloadedBytes = 0, totalBytes = 0),
                )
                val apk = withContext(Dispatchers.IO) { downloadApk(release) }
                downloadedUpdate = versionName to apk
                appLog.info(TAG, "update downloaded: $versionName ${apk.length()} bytes")
                _state.value = UpdateState.ReadyToInstall(versionName)
                notificationFeature.showUpdateReady(versionName)
            } catch (failure: UpdateException) {
                appLog.warn(TAG, "update download failed: ${failure.reason}", failure.cause ?: failure)
                notificationFeature.hideUpdateNotification()
                _state.value = UpdateState.Failed(failure.reason)
            } catch (failure: SecurityException) {
                appLog.warn(TAG, "update download blocked by security policy", failure)
                notificationFeature.hideUpdateNotification()
                _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
            } finally {
                downloadMutex.unlock()
            }
        }
    }

    fun installDownloadedUpdate() {
        val (versionName, apk) = downloadedUpdate ?: run {
            appLog.warn(TAG, "install requested without a downloaded update")
            _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
            return
        }
        if (!apk.isFile || apk.length() == 0L || !looksLikeApk(apk)) {
            appLog.warn(TAG, "downloaded update is invalid, deleting it")
            downloadedUpdate = null
            runCatching { apk.delete() }
            notificationFeature.hideUpdateNotification()
            _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
            return
        }

        try {
            if (!appContext.packageManager.canRequestPackageInstalls()) {
                appLog.info(TAG, "install permission required for $versionName")
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", appContext.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                _state.value = UpdateState.InstallPermissionRequired(versionName)
                return
            }

            val apkUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.updates",
                apk,
            )
            // ACTION_INSTALL_PACKAGE is deprecated since Android 10; the system
            // package installer handles ACTION_VIEW with an APK content URI.
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, APK_MIME_TYPE)
                    clipData = ClipData.newRawUri(null, apkUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            appLog.info(TAG, "installer opened for $versionName")
            notificationFeature.hideUpdateNotification()
        } catch (failure: ActivityNotFoundException) {
            appLog.warn(TAG, "install failed: no package installer", failure)
            _state.value = UpdateState.Failed(UpdateError.INSTALL)
        } catch (failure: IllegalArgumentException) {
            appLog.warn(TAG, "install failed: invalid apk uri", failure)
            _state.value = UpdateState.Failed(UpdateError.INSTALL)
        } catch (failure: SecurityException) {
            appLog.warn(TAG, "install failed: security policy", failure)
            _state.value = UpdateState.Failed(UpdateError.INSTALL)
        }
    }

    fun refreshInstallPermission() {
        val current = _state.value
        if (
            current is UpdateState.InstallPermissionRequired &&
            appContext.packageManager.canRequestPackageInstalls()
        ) {
            appLog.info(TAG, "install permission granted for ${current.versionName}")
            _state.value = UpdateState.ReadyToInstall(current.versionName)
        }
    }

    /**
     * Release metadata, official API first. If GitHub's API is unreachable
     * (the usual reason being a network that cannot reach it directly), the
     * same request is retried once through a mirror that relays the API. The
     * response body is identical JSON, so parsing and its URL pinning are
     * shared for both paths.
     */
    private fun fetchLatestRelease(): ReleaseMetadata {
        return try {
            fetchReleaseMetadata(LATEST_RELEASE_URL)
        } catch (failure: UpdateException) {
            if (failure.reason != UpdateError.NETWORK) throw failure
            appLog.warn(TAG, "release metadata direct fetch failed, retrying via mirror")
            fetchReleaseMetadata(UpdateSources.mirrorUrl(UpdateSources.METADATA_MIRROR_PREFIX, LATEST_RELEASE_URL))
        }
    }

    private fun fetchReleaseMetadata(metadataUrl: String): ReleaseMetadata {
        appLog.debug(TAG, "fetching release metadata")
        val connection = try {
            (URL(metadataUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }
        } catch (failure: IOException) {
            throw UpdateException(UpdateError.NETWORK, failure)
        }

        try {
            if (connection.responseCode !in 200..299) {
                appLog.warn(TAG, "release metadata returned HTTP ${connection.responseCode}")
                throw UpdateException(UpdateError.NETWORK)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parseReleaseMetadata(body)
        } catch (failure: UpdateException) {
            throw failure
        } catch (failure: IOException) {
            throw UpdateException(UpdateError.NETWORK, failure)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the APK from the fastest reachable source and falls back
     * through the rest in latency order. Every attempt is digest-verified on
     * its own, so a corrupt or tampered mirror only costs one retry.
     */
    private suspend fun downloadApk(release: ReleaseMetadata): File {
        val candidates = UpdateSources.candidatesFor(release.apkUrl)
        val latencies = UpdateSources.probeLatencies(candidates)
        val ranked = UpdateSources.rankedCandidates(candidates, latencies)
        appLog.info(
            TAG,
            "download sources: " + ranked.mapIndexed { index, url ->
                "$index:${hostOf(url)}=${latencies[url]?.toString() ?: "-"}ms"
            }.joinToString(" "),
        )

        var lastFailure = UpdateException(UpdateError.DOWNLOAD)
        for (url in ranked) {
            try {
                return downloadFrom(url, release)
            } catch (failure: UpdateException) {
                appLog.warn(TAG, "download from ${hostOf(url)} failed: ${failure.reason}")
                lastFailure = failure
            }
        }
        throw lastFailure
    }

    private fun downloadFrom(sourceUrl: String, release: ReleaseMetadata): File {
        val versionName = release.version.toString()
        appLog.debug(TAG, "downloading apk $versionName from ${hostOf(sourceUrl)}")
        var partialFile: File? = null
        val connection = try {
            (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
        } catch (failure: IOException) {
            throw UpdateException(UpdateError.DOWNLOAD, failure)
        }

        try {
            if (connection.responseCode !in 200..299) {
                appLog.warn(TAG, "apk download returned HTTP ${connection.responseCode}")
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            // Mirrors sometimes drop the content length; the release metadata
            // carries the exact size, so either source of truth may drive the
            // progress bar and the completeness check.
            val serverLength = connection.contentLengthLong
            val expectedSize = if (serverLength > 0) serverLength else release.sizeBytes
            if (expectedSize == 0L || expectedSize > MAX_APK_BYTES) {
                appLog.warn(TAG, "apk download has invalid size: $expectedSize")
                throw UpdateException(UpdateError.DOWNLOAD)
            }

            val updateDirectory = File(appContext.cacheDir, UPDATE_CACHE_DIRECTORY)
            if (!updateDirectory.isDirectory && !updateDirectory.mkdirs()) {
                appLog.warn(TAG, "could not create update cache directory")
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            val fileName = "$UPDATE_FILE_PREFIX$versionName.apk"
            val apkFile = File(updateDirectory, fileName)
            // A previous launch may have downloaded this exact release already;
            // re-checking is cheap, re-downloading a few megabytes every start
            // is not. The cached file must still match GitHub's digest.
            if (
                apkFile.isFile &&
                apkFile.length() == expectedSize &&
                looksLikeApk(apkFile) &&
                digestMatches(apkFile, release.sha256)
            ) {
                appLog.info(TAG, "reused cached apk $versionName")
                pruneOtherUpdates(updateDirectory, keep = apkFile)
                return apkFile
            }
            pruneOtherUpdates(updateDirectory, keep = apkFile)

            val temporaryFile = File(updateDirectory, "$fileName.part")
            partialFile = temporaryFile
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                appLog.warn(TAG, "could not replace partial apk")
                throw UpdateException(UpdateError.DOWNLOAD)
            }

            var downloadedBytes = 0L
            var lastPercent = -1
            var lastNotifiedBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloadedBytes += read
                        if (downloadedBytes > MAX_APK_BYTES || downloadedBytes > expectedSize) {
                            throw UpdateException(UpdateError.DOWNLOAD)
                        }
                        output.write(buffer, 0, read)

                        // Keep the notification live without flooding the binder:
                        // one update per percent, or per chunk when the size is unknown.
                        val percent = ((downloadedBytes * 100) / expectedSize).toInt()
                        val report = if (serverLength > 0 || release.sizeBytes > 0) {
                            percent != lastPercent
                        } else {
                            downloadedBytes - lastNotifiedBytes >= PROGRESS_NOTIFY_BYTES
                        }
                        if (report) {
                            lastPercent = percent
                            lastNotifiedBytes = downloadedBytes
                            notificationFeature.showUpdateDownload(
                                UpdateNotificationProgress(
                                    versionName = versionName,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = expectedSize,
                                ),
                            )
                        }
                    }
                }
            }
            if (downloadedBytes != expectedSize) {
                appLog.warn(TAG, "apk download incomplete: $downloadedBytes/$expectedSize bytes")
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            if (apkFile.exists() && !apkFile.delete()) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            if (!temporaryFile.renameTo(apkFile)) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            partialFile = null
            verifyDigest(apkFile, release)
            appLog.info(TAG, "apk saved $versionName: $downloadedBytes bytes")
            notificationFeature.showUpdateDownload(
                UpdateNotificationProgress(
                    versionName = versionName,
                    downloadedBytes = downloadedBytes,
                    totalBytes = expectedSize,
                ),
            )
            return apkFile
        } catch (failure: UpdateException) {
            partialFile?.delete()
            throw failure
        } catch (failure: IOException) {
            partialFile?.delete()
            throw UpdateException(UpdateError.DOWNLOAD, failure)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The last word on whether a downloaded file may be installed: its SHA-256
     * must equal the digest GitHub published with the release. Runs after the
     * rename, so a rejected file is deleted under its final name too.
     */
    private fun verifyDigest(apkFile: File, release: ReleaseMetadata) {
        val expected = release.sha256 ?: run {
            appLog.warn(TAG, "release carries no digest; relying on size validation")
            return
        }
        val actual = runCatching { UpdateSources.sha256Hex(apkFile) }.getOrNull()
        if (actual == null || !actual.equals(expected, ignoreCase = true)) {
            appLog.warn(TAG, "apk digest mismatch: expected=$expected actual=$actual")
            runCatching { apkFile.delete() }
            throw UpdateException(UpdateError.CHECKSUM)
        }
        appLog.info(TAG, "apk digest verified")
    }

    /** Cached APKs are only reused when they still match GitHub's digest. */
    private fun digestMatches(file: File, expected: String?): Boolean {
        if (expected == null) return true
        val actual = runCatching { UpdateSources.sha256Hex(file) }.getOrNull()
        return actual != null && actual.equals(expected, ignoreCase = true)
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    /** The cache is ours; a half-downloaded or older release must not pile up. */
    private fun pruneOtherUpdates(directory: File, keep: File) {
        val files = directory.listFiles() ?: return
        files.forEach { file ->
            if (file.name == keep.name) return@forEach
            if (!file.name.startsWith(UPDATE_FILE_PREFIX)) return@forEach
            if (file.isFile) runCatching { file.delete() }
        }
    }

    /** Rejects a truncated or non-zip file before handing it to the installer. */
    private fun looksLikeApk(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            if (input.read(header) != 4) {
                false
            } else {
                val magic = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                magic == ZIP_MAGIC
            }
        }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "AppUpdate"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/JunRutey/Meteor/releases/latest"
        const val USER_AGENT = "Meteor-Android"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val NETWORK_TIMEOUT_MS = 15_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        const val MAX_APK_BYTES = 512L * 1024L * 1024L
        const val PROGRESS_NOTIFY_BYTES = 512L * 1024L
        const val UPDATE_CACHE_DIRECTORY = "updates"
        const val UPDATE_FILE_PREFIX = "meteor-"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val ZIP_MAGIC = 0x504B0304
    }
}
