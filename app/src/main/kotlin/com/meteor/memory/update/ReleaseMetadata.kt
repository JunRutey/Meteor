package com.meteor.memory.update

import org.json.JSONException
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException

internal data class ReleaseMetadata(
    val version: ReleaseVersion,
    val apkUrl: String,
)

internal enum class UpdateError {
    NETWORK,
    INVALID_RELEASE,
    NO_APK,
    DOWNLOAD,
    INSTALL,
}

internal class UpdateException(
    val reason: UpdateError,
    cause: Throwable? = null,
) : Exception(cause)

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val versionName: String) : UpdateState
    data class ReadyToInstall(val versionName: String) : UpdateState
    data class InstallPermissionRequired(val versionName: String) : UpdateState
    data class Failed(val reason: UpdateError) : UpdateState
}

internal fun parseReleaseMetadata(json: String): ReleaseMetadata {
    try {
        val release = JSONObject(json)
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) {
            throw UpdateException(UpdateError.INVALID_RELEASE)
        }

        val version = ReleaseVersion.parse(release.getString("tag_name"))
            ?: throw UpdateException(UpdateError.INVALID_RELEASE)
        if (version.isPrerelease) throw UpdateException(UpdateError.INVALID_RELEASE)

        val assets = release.getJSONArray("assets")
        val apkAssets = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        if (apkAssets.size != 1) throw UpdateException(UpdateError.NO_APK)

        val apkUrl = apkAssets.single().getString("browser_download_url")
        val uri = try {
            URI(apkUrl)
        } catch (failure: URISyntaxException) {
            throw UpdateException(UpdateError.INVALID_RELEASE, failure)
        }
        if (
            uri.scheme != "https" ||
            uri.host != RELEASE_HOST ||
            uri.userInfo != null ||
            uri.port != -1 ||
            uri.query != null ||
            uri.fragment != null ||
            uri.path?.startsWith(RELEASE_ASSET_PATH) != true
        ) {
            throw UpdateException(UpdateError.INVALID_RELEASE)
        }
        return ReleaseMetadata(version, apkUrl)
    } catch (failure: JSONException) {
        throw UpdateException(UpdateError.INVALID_RELEASE, failure)
    }
}

internal data class ReleaseVersion private constructor(
    private val major: BigInteger,
    private val minor: BigInteger,
    private val patch: BigInteger,
    private val prerelease: List<String>?,
    private val buildMetadata: String?,
) : Comparable<ReleaseVersion> {

    val isPrerelease: Boolean get() = prerelease != null

    override fun compareTo(other: ReleaseVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        val left = prerelease
        val right = other.prerelease
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1

        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = compareIdentifier(left[index], right[index])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        prerelease?.let { append("-${it.joinToString(".")}") }
        buildMetadata?.let { append("+$it") }
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)
        return when {
            leftNumeric && rightNumeric -> BigInteger(left).compareTo(BigInteger(right))
            leftNumeric -> -1
            rightNumeric -> 1
            else -> left.compareTo(right)
        }
    }

    companion object {
        private val VERSION_PATTERN = Regex(
            "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
        )

        fun parse(value: String): ReleaseVersion? {
            val match = VERSION_PATTERN.matchEntire(value) ?: return null
            return ReleaseVersion(
                major = match.groupValues[1].toBigIntegerOrNull() ?: return null,
                minor = match.groupValues[2].toBigIntegerOrNull() ?: return null,
                patch = match.groupValues[3].toBigIntegerOrNull() ?: return null,
                prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.'),
                buildMetadata = match.groupValues[5].takeIf(String::isNotEmpty),
            )
        }
    }
}

private const val RELEASE_HOST = "github.com"
private const val RELEASE_ASSET_PATH = "/JunRutey/Meteor/releases/download/"
