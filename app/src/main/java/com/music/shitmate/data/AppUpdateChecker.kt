package com.music.shitmate.data

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.music.shitmate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ShitMate ships as a sideloaded APK off GitHub Releases rather than through
 * a store, so there's nothing to push an update notice on its own — this
 * checks GitHub's latest release on launch and on manual request, then handles
 * downloading and launching Android's package installer in-app.
 */
object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
    )

    sealed interface CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult
        data class UpToDate(val currentVersion: String) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    private const val CACHE_SUBDIR = "updates"
    private const val REPO_PATH = "Dustu-Hub/ShitMate"
    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$REPO_PATH/releases/latest"
    private const val LATEST_RELEASE_WEB_URL =
        "https://github.com/$REPO_PATH/releases/latest"
    private const val MIN_VALID_APK_BYTES = 1_000_000L

    private val json = Json { ignoreUnknownKeys = true }

    private val downloadClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    private val _hasChecked = MutableStateFlow(false)
    val hasChecked = _hasChecked.asStateFlow()

    /** Where this update's APK download currently stands, for the dialog's progress row. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    /**
     * Remembers that the user clicked Install Now and was bounced to Android's
     * "Install unknown apps" Settings screen so returning to the app can finish
     * launching the installer automatically once permission is granted.
     */
    @Volatile
    private var pendingInstallOnResume = false

    suspend fun check() {
        checkForUpdate()
    }

    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        val fetched = runCatching {
            fetchLatestViaApi() ?: fetchLatestViaWeb()
        }
        fetched.fold(
            onSuccess = { remote ->
                _hasChecked.value = true
                _lastError.value = null
                val latestVersion = remote.tag.removePrefix("v").trim()
                if (isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
                    val info = UpdateInfo(
                        version = latestVersion,
                        releaseUrl = remote.releaseUrl,
                        apkUrl = remote.apkUrl,
                        notes = remote.notes,
                    )
                    if (_available.value?.version != latestVersion) {
                        _download.value = DownloadState.Idle
                    }
                    _available.value = info
                    CheckResult.UpdateAvailable(info)
                } else {
                    _available.value = null
                    _download.value = DownloadState.Idle
                    CheckResult.UpToDate(BuildConfig.VERSION_NAME)
                }
            },
            onFailure = { error ->
                _hasChecked.value = true
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "Couldn't reach GitHub"
                _lastError.value = message
                CheckResult.Failed(message)
            },
        )
    }

    private data class RemoteRelease(
        val tag: String,
        val releaseUrl: String,
        val apkUrl: String?,
        val notes: String?,
    )

    private fun fetchLatestViaApi(): RemoteRelease? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ShitMate/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .build()

        return runCatching {
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                val release = json.parseToJsonElement(body) as? JsonObject ?: return null
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
                val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: LATEST_RELEASE_WEB_URL
                val notes = release["body"]?.jsonPrimitive?.contentOrNull
                val assets = release["assets"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject }
                    ?.mapNotNull { asset ->
                        val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val uploaded = (asset["state"]?.jsonPrimitive?.contentOrNull ?: "uploaded") == "uploaded"
                        val downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        if (uploaded) name to downloadUrl else null
                    }
                    .orEmpty()
                RemoteRelease(
                    tag = tag,
                    releaseUrl = url,
                    apkUrl = selectApkUrl(assets),
                    notes = notes,
                )
            }
        }.getOrNull()
    }

    /**
     * Rate-limit-free fallback via GitHub's normal release redirect and
     * expanded_assets fragment. Shared mobile carrier IPs frequently hit the
     * 60 req/hr unauthenticated GitHub REST API limit, so this ensures updates
     * are still discovered reliably on every device.
     */
    private fun fetchLatestViaWeb(): RemoteRelease {
        val latestReq = Request.Builder()
            .url(LATEST_RELEASE_WEB_URL)
            .header("User-Agent", "ShitMate/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .build()

        val tag: String
        val releaseUrl: String
        downloadClient.newCall(latestReq).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
            val finalUrl = response.request.url.toString()
            val extracted = extractTagFromWebResponse(finalUrl, if (finalUrl.contains("/releases/tag/")) "" else response.body.string())
                ?: error("Could not read latest release tag from GitHub")
            tag = extracted
            releaseUrl = if (finalUrl.contains("/releases/tag/")) {
                finalUrl
            } else {
                "https://github.com/$REPO_PATH/releases/tag/$tag"
            }
        }

        val assetsUrl = "https://github.com/$REPO_PATH/releases/expanded_assets/$tag"
        val assetsReq = Request.Builder()
            .url(assetsUrl)
            .header("User-Agent", "ShitMate/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .build()

        val assets = runCatching {
            downloadClient.newCall(assetsReq).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                extractApkAssetsFromHtml(response.body.string())
            }
        }.getOrDefault(emptyList())

        return RemoteRelease(
            tag = tag,
            releaseUrl = releaseUrl,
            apkUrl = selectApkUrl(assets),
            notes = null,
        )
    }

    internal fun extractTagFromWebResponse(finalUrl: String, html: String): String? {
        val urlTag = finalUrl.substringAfter("/releases/tag/", "")
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        if (urlTag.isNotEmpty() && !urlTag.contains('/')) return urlTag
        return Regex("""/$REPO_PATH/releases/tag/([^"/?#<>\s]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
    }

    internal fun extractApkAssetsFromHtml(html: String): List<Pair<String, String>> =
        Regex("""href="(/$REPO_PATH/releases/download/[^"]+\.apk)"""")
            .findAll(html)
            .map { match ->
                val path = match.groupValues[1]
                val fileName = path.substringAfterLast('/')
                fileName to "https://github.com$path"
            }
            .distinctBy { it.first }
            .toList()

    /**
     * Wipes stale APKs left over from an earlier run. Keeps a valid APK that
     * is newer than the currently installed build so an OS process kill while
     * granting "Install unknown apps" does not discard an already-downloaded
     * update.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp") || !isApkNewerThanInstalled(context, file)) {
                file.delete()
            }
        }
    }

    internal fun selectApkUrl(
        assets: List<Pair<String, String>>,
        isDevBuild: Boolean = BuildConfig.APPLICATION_ID.contains(".dev", ignoreCase = true),
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        apkPrefix: String = BuildConfig.UPDATE_APK_PREFIX,
    ): String? {
        val filtered = assets.filter { (name, _) ->
            val flavorMatches = if (isDevBuild) {
                name.contains("-dev-", ignoreCase = true)
            } else {
                !name.contains("-dev-", ignoreCase = true)
            }
            name.endsWith(".apk", ignoreCase = true) &&
                (name.startsWith(apkPrefix, ignoreCase = true) ||
                    name.startsWith("ShitMate", ignoreCase = true)) &&
                flavorMatches
        }

        fun findByName(vararg patterns: String): String? {
            for (p in patterns) {
                val match = filtered.firstOrNull { (name, _) ->
                    name.contains(p, ignoreCase = true)
                }
                if (match != null) return match.second
            }
            return null
        }

        val abiUrl = supportedAbis.firstNotNullOfOrNull { abi ->
            when {
                abi.contains("arm64", ignoreCase = true) -> findByName("arm64")
                abi.contains("v7a", ignoreCase = true) -> findByName("v7a", "armeabi")
                abi.contains("x86_64", ignoreCase = true) -> findByName("x86_64")
                else -> null
            }
        }

        return abiUrl ?: findByName("universal") ?: findByName("ShitMate.apk")
    }

    /**
     * Streams the current update's APK into the app cache, reporting progress
     * through [download]. A finished verified file survives a cancelled dialog:
     * until the state is reset, "Install Now" comes straight back without a
     * second download.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value
        val url = info?.apkUrl
        if (info == null || url == null) {
            _download.value = DownloadState.Failed("No downloadable APK found for this release")
            return@withContext
        }

        val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val target = File(dir, "shitmate-${info.version}.apk")
        // Reuse already-downloaded valid APK for this exact version if present.
        if (target.exists() && isValidApkForThisApp(context, target)) {
            _download.value = DownloadState.Ready(target)
            return@withContext
        }

        downloadCancelled = false
        _download.value = DownloadState.Downloading(-1f)

        runCatching {
            dir.listFiles()?.forEach { it.delete() }
            val tmpTarget = File(dir, "shitmate-${info.version}.apk.tmp")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ShitMate/${BuildConfig.VERSION_NAME}")
                .build()

            downloadClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    tmpTarget.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var lastEmitMs = 0L
                        while (true) {
                            if (downloadCancelled) {
                                tmpTarget.delete()
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            val now = SystemClock.uptimeMillis()
                            if (now - lastEmitMs >= 80L) {
                                lastEmitMs = now
                                _download.value = DownloadState.Downloading(
                                    total?.let { (readTotal.toFloat() / it).coerceIn(0f, 1f) } ?: -1f,
                                )
                            }
                        }
                    }
                }
                check(tmpTarget.length() >= MIN_VALID_APK_BYTES) {
                    "Downloaded APK was incomplete (${tmpTarget.length()} bytes)"
                }
                total?.let { expected ->
                    check(tmpTarget.length() == expected) {
                        "Downloaded size mismatch (${tmpTarget.length()} / $expected bytes)"
                    }
                }
            }

            check(isValidApkForThisApp(context, tmpTarget)) {
                "Downloaded file is corrupted or does not match ${context.packageName}"
            }
            if (target.exists()) target.delete()
            check(tmpTarget.renameTo(target)) { "Could not save update file" }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            File(dir, "shitmate-${info.version}.apk.tmp").delete()
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    /** Stops an in-flight download; the next read loop sees this and bails. */
    fun cancelDownload() {
        downloadCancelled = true
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        pendingInstallOnResume = false
        _download.value = DownloadState.Idle
    }

    /**
     * Called when the host Activity resumes after bouncing the user to
     * "Install unknown apps" in Android Settings.
     */
    fun retryPendingInstallIfAllowed(context: Context): Boolean {
        if (!pendingInstallOnResume) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return false
        }
        pendingInstallOnResume = false
        val ready = _download.value as? DownloadState.Ready ?: return false
        return installApk(context, ready.file)
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that switch first and resumes installation automatically
     * upon returning.
     */
    fun installApk(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_APK_BYTES) {
            _download.value = DownloadState.Failed("Update file missing or incomplete. Tap Try Again.")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallOnResume = true
            Toast.makeText(
                context,
                "Enable 'Allow from this source', then come back to install",
                Toast.LENGTH_LONG,
            ).show()
            val opened = runCatching {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .apply {
                        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(settingsIntent)
            }.isSuccess
            if (opened) return false
            pendingInstallOnResume = false
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            grantInstallerUriPermissions(context, viewIntent, uri)

            val viewStarted = runCatching {
                context.startActivity(viewIntent)
            }.isSuccess

            if (!viewStarted) {
                @Suppress("DEPRECATION")
                val fallbackIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                grantInstallerUriPermissions(context, fallbackIntent, uri)
                context.startActivity(fallbackIntent)
            }
            true
        }.getOrElse { error ->
            _download.value = DownloadState.Failed(error.message ?: "Unable to launch package installer")
            false
        }
    }

    private fun grantInstallerUriPermissions(context: Context, intent: Intent, uri: Uri) {
        val resolveInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        for (resolveInfo in resolveInfoList) {
            runCatching {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        for (pkg in listOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.oplus.appdetail",
            "com.coloros.packageinstaller",
        )) {
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun isValidApkForThisApp(context: Context, file: File): Boolean = runCatching {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return@runCatching false
        pkgInfo.packageName == context.packageName
    }.getOrDefault(false)

    private fun isApkNewerThanInstalled(context: Context, file: File): Boolean = runCatching {
        if (!file.exists() || file.length() < MIN_VALID_APK_BYTES) return@runCatching false
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return@runCatching false
        if (pkgInfo.packageName != context.packageName) return@runCatching false
        val versionName = pkgInfo.versionName ?: return@runCatching false
        isNewer(versionName, BuildConfig.VERSION_NAME)
    }.getOrDefault(false)

    /** A version split into its numeric dotted parts and whether it carries a "-suffix" (e.g. "-beta2"). */
    private data class ParsedVersion(val parts: List<Int>, val isPreRelease: Boolean)

    private fun parseVersion(raw: String): ParsedVersion {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return ParsedVersion(base.split(".").map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /**
     * Numeric, dot-separated comparison — "1.10" outranks "1.9" — with one
     * extra rule: a "-betaN" build is treated as older than a plain release
     * of the same numbers.
     */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.isPreRelease && !l.isPreRelease
    }
}
