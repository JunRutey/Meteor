package com.meteor.memory.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.meteor.memory.system.NotificationFeature
import com.meteor.memory.system.UpdateNotificationProgress
import kotlinx.coroutines.CoroutineScope
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

class UpdateService(
    context: Context,
    currentVersionName: String,
    private val notificationFeature: NotificationFeature,
) {
    private val appContext = context.applicationContext
    private val currentVersion = requireNotNull(ReleaseVersion.parse(currentVersionName))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val launchCheckStarted = AtomicBoolean(false)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    internal val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var downloadedUpdate: Pair<String, File>? = null

    fun checkOnLaunch() {
        if (launchCheckStarted.compareAndSet(false, true)) checkAndDownload()
    }

    fun checkAndDownload() {
        scope.launch {
            if (!checkMutex.tryLock()) return@launch
            try {
                notificationFeature.hideUpdateNotification()
                _state.value = UpdateState.Checking
                val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
                if (release.version <= currentVersion) {
                    _state.value = UpdateState.UpToDate
                    return@launch
                }

                val versionName = release.version.toString()
                _state.value = UpdateState.Downloading(versionName)
                notificationFeature.showUpdateDownload(
                    UpdateNotificationProgress(versionName, downloadedBytes = 0, totalBytes = 0),
                )
                val apk = withContext(Dispatchers.IO) { downloadApk(release) }
                downloadedUpdate = versionName to apk
                _state.value = UpdateState.ReadyToInstall(versionName)
                notificationFeature.showUpdateReady(versionName)
            } catch (failure: UpdateException) {
                notificationFeature.hideUpdateNotification()
                _state.value = UpdateState.Failed(failure.reason)
            } catch (failure: SecurityException) {
                notificationFeature.hideUpdateNotification()
                _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
            } finally {
                checkMutex.unlock()
            }
        }
    }

    fun installDownloadedUpdate() {
        val (versionName, apk) = downloadedUpdate ?: run {
            _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
            return
        }
        if (!apk.isFile || apk.length() == 0L || !looksLikeApk(apk)) {
            downloadedUpdate = null
            runCatching { apk.delete() }
            notificationFeature.hideUpdateNotification()
            _state.value = UpdateState.Failed(UpdateError.DOWNLOAD)
            return
        }

        try {
            if (!appContext.packageManager.canRequestPackageInstalls()) {
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
            notificationFeature.hideUpdateNotification()
        } catch (failure: ActivityNotFoundException) {
            _state.value = UpdateState.Failed(UpdateError.INSTALL)
        } catch (failure: IllegalArgumentException) {
            _state.value = UpdateState.Failed(UpdateError.INSTALL)
        } catch (failure: SecurityException) {
            _state.value = UpdateState.Failed(UpdateError.INSTALL)
        }
    }

    fun refreshInstallPermission() {
        val current = _state.value
        if (
            current is UpdateState.InstallPermissionRequired &&
            appContext.packageManager.canRequestPackageInstalls()
        ) {
            _state.value = UpdateState.ReadyToInstall(current.versionName)
        }
    }

    private fun fetchLatestRelease(): ReleaseMetadata {
        val connection = try {
            (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
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

    private fun downloadApk(release: ReleaseMetadata): File {
        val versionName = release.version.toString()
        var partialFile: File? = null
        val connection = try {
            (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
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
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            val contentLength = connection.contentLengthLong
            if (contentLength == 0L || contentLength > MAX_APK_BYTES) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }

            val updateDirectory = File(appContext.cacheDir, UPDATE_CACHE_DIRECTORY)
            if (!updateDirectory.isDirectory && !updateDirectory.mkdirs()) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            val fileName = "$UPDATE_FILE_PREFIX$versionName.apk"
            val apkFile = File(updateDirectory, fileName)
            // A previous launch may have downloaded this exact release already;
            // re-checking is cheap, re-downloading a few megabytes every start is not.
            if (
                contentLength > 0 &&
                apkFile.isFile &&
                apkFile.length() == contentLength &&
                looksLikeApk(apkFile)
            ) {
                pruneOtherUpdates(updateDirectory, keep = apkFile)
                return apkFile
            }
            pruneOtherUpdates(updateDirectory, keep = apkFile)

            val temporaryFile = File(updateDirectory, "$fileName.part")
            partialFile = temporaryFile
            if (temporaryFile.exists() && !temporaryFile.delete()) {
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
                        if (
                            downloadedBytes > MAX_APK_BYTES ||
                            (contentLength > 0 && downloadedBytes > contentLength)
                        ) {
                            throw UpdateException(UpdateError.DOWNLOAD)
                        }
                        output.write(buffer, 0, read)

                        // Keep the notification live without flooding the binder:
                        // one update per percent, or per chunk when the size is unknown.
                        val percent = if (contentLength > 0) {
                            ((downloadedBytes * 100) / contentLength).toInt()
                        } else {
                            -1
                        }
                        val report = if (contentLength > 0) {
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
                                    totalBytes = contentLength,
                                ),
                            )
                        }
                    }
                }
            }
            if (
                downloadedBytes == 0L ||
                (contentLength > 0 && downloadedBytes != contentLength)
            ) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            if (apkFile.exists() && !apkFile.delete()) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            if (!temporaryFile.renameTo(apkFile)) {
                throw UpdateException(UpdateError.DOWNLOAD)
            }
            partialFile = null
            notificationFeature.showUpdateDownload(
                UpdateNotificationProgress(
                    versionName = versionName,
                    downloadedBytes = downloadedBytes,
                    totalBytes = contentLength,
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
