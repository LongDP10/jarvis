package com.jarvis.assistant.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.jarvis.assistant.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(
        val versionName: String,
        val versionCode: Long,
        val notes: String,
        val downloadUrl: String,
    ) : UpdateStatus

    /** 0..1, or null while the server withholds a content length. */
    data class Downloading(val progress: Float?) : UpdateStatus
    data class ReadyToInstall(val file: File) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Self-update straight from GitHub Releases.
 *
 * CI publishes a signed APK on every push to main, tagged `v1.0.<n>` where `<n>`
 * is also the APK's versionCode. That single number is the whole comparison:
 * parsing the tag avoids downloading anything just to find out whether there is
 * anything worth downloading.
 *
 * Android will not let an app install an APK silently, and that is the right
 * behaviour -- the user sees the system's own install prompt every time. All
 * this does is remove the cable, the file transfer, and the hunting through
 * Downloads.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val currentVersionCode: Long by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
    }

    val currentVersionName: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    private val repoSlug: String get() = context.getString(R.string.github_repo)

    suspend fun check() {
        if (repoSlug.isBlank() || repoSlug.startsWith("OWNER/")) {
            _status.value = UpdateStatus.Failed(
                context.getString(R.string.update_repo_not_configured),
            )
            return
        }

        _status.value = UpdateStatus.Checking
        _status.value = withContext(Dispatchers.IO) { fetchLatest() }
    }

    private fun fetchLatest(): UpdateStatus {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repoSlug/releases/latest")
            .addHeader("Accept", "application/vnd.github+json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return UpdateStatus.Failed(
                        context.getString(R.string.update_no_release),
                    )
                }
                if (!response.isSuccessful) {
                    return UpdateStatus.Failed("GitHub returned HTTP ${response.code}.")
                }

                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject

                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?: return UpdateStatus.Failed("The latest release has no tag.")

                // "v1.0.42" -> 42. CI guarantees this matches the APK's
                // versionCode, so no download is needed to compare.
                val remoteCode = tag.substringAfterLast('.').toLongOrNull()
                    ?: return UpdateStatus.Failed(
                        "Release tag \"$tag\" does not end in a version number.",
                    )

                if (remoteCode <= currentVersionCode) return UpdateStatus.UpToDate

                val apkUrl = root["assets"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .firstOrNull {
                        it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true
                    }
                    ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                    ?: return UpdateStatus.Failed(
                        context.getString(R.string.update_no_apk_asset),
                    )

                UpdateStatus.Available(
                    versionName = tag.removePrefix("v"),
                    versionCode = remoteCode,
                    notes = root["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    downloadUrl = apkUrl,
                )
            }
        } catch (e: IOException) {
            UpdateStatus.Failed(context.getString(R.string.update_network_error, e.message.orEmpty()))
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            UpdateStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Downloads the APK and hands it to the system installer.
     *
     * Fails early and clearly when the "install unknown apps" grant is missing,
     * because otherwise the download succeeds and the install prompt silently
     * never appears.
     */
    suspend fun downloadAndInstall(update: UpdateStatus.Available) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _status.value = UpdateStatus.Failed(
                context.getString(R.string.update_needs_install_permission),
            )
            return
        }

        _status.value = UpdateStatus.Downloading(null)

        val file = withContext(Dispatchers.IO) { download(update) }
        if (file == null) return

        _status.value = UpdateStatus.ReadyToInstall(file)
        launchInstaller(file)
    }

    private fun download(update: UpdateStatus.Available): File? {
        val request = Request.Builder().url(update.downloadUrl).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _status.value = UpdateStatus.Failed("Download failed: HTTP ${response.code}.")
                    return null
                }
                val body = response.body ?: run {
                    _status.value = UpdateStatus.Failed("Download returned an empty body.")
                    return null
                }

                val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                // One file, overwritten each time: keeping old APKs around would
                // quietly accumulate tens of megabytes on the phone.
                val target = File(dir, "jarvis-update.apk")

                val total = body.contentLength()
                var written = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            _status.value = UpdateStatus.Downloading(
                                if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else null,
                            )
                        }
                    }
                }
                target
            }
        } catch (e: IOException) {
            _status.value = UpdateStatus.Failed(
                context.getString(R.string.update_network_error, e.message.orEmpty()),
            )
            null
        }
    }

    private fun launchInstaller(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Log.e(TAG, "Could not start the package installer", it)
            _status.value = UpdateStatus.Failed("The system installer could not be opened.")
        }
    }

    /** The settings screen where the user grants "install unknown apps". */
    fun installPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun releasesPageIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://github.com/$repoSlug/releases"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun reset() {
        _status.value = UpdateStatus.Idle
    }

    private companion object {
        const val TAG = "UpdateManager"
        const val DOWNLOAD_BUFFER = 64 * 1024
    }
}
