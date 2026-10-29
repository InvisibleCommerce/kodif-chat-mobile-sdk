package ai.kodif.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * What the session needs from the fragment showing the chat: dismissing it, runtime permissions and the file chooser.
 * Create it in `onCreate` (activity result launchers must be registered before the host starts).
 */
internal class ChatHost(
    caller: ActivityResultCaller,
    private val activityProvider: () -> Activity?,
    private val onDismiss: () -> Unit,
) {
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photo: Pair<File, Uri>? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val fileLauncher = caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deliverFiles(if (it.resultCode == Activity.RESULT_OK) it.data ?: Intent() else null)
    }

    private val permissionLauncher = caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionCallback.also { permissionCallback = null }?.invoke(it)
    }

    val activity: Activity? get() = activityProvider()

    fun dismiss() = onDismiss()

    /** Asks for [permission] (one request at a time), answering [callback] with whether it was granted. */
    fun requestPermission(permission: String, callback: (Boolean) -> Unit) {
        if (permissionCallback != null) return callback(false)
        permissionCallback = callback
        try {
            permissionLauncher.launch(permission)
        } catch (_: IllegalStateException) {
            permissionCallback = null
            callback(false)
        }
    }

    /** Shows the system chooser for files, plus the camera when the request accepts images. */
    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        val activity = activity ?: return false
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val mimeTypes = mimeTypes(params)
        val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        val camera = (mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" || it.startsWith("image/") }) &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        // An app that declares CAMERA must hold it before another app can capture for it; one that doesn't needs nothing.
        if (camera && declaresCamera(activity) && !isGranted(activity, Manifest.permission.CAMERA)) {
            requestPermission(Manifest.permission.CAMERA) { launchChooser(activity, mimeTypes, multiple, withCamera = it) }
        } else {
            launchChooser(activity, mimeTypes, multiple, camera)
        }
        return true
    }

    /** Answers anything still waiting on this host (it is going away). */
    fun cancelPending() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        permissionCallback.also { permissionCallback = null }?.invoke(false)
    }

    private fun launchChooser(activity: Activity, mimeTypes: List<String>, multiple: Boolean, withCamera: Boolean) {
        if (fileCallback == null) return
        val content = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        if (mimeTypes.isNotEmpty()) content.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        val chooser = Intent.createChooser(content, null)
        if (withCamera) cameraIntent(activity)?.let { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
        try {
            fileLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.w("KodifChat", "No file chooser available", e)
            deliverFiles(null)
        }
    }

    private fun cameraIntent(context: Context): Intent? = try {
        val file = File.createTempFile("photo-", ".jpg", File(context.cacheDir, "kodifchat").apply { mkdirs() })
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.kodifchat.fileprovider", file)
        photo = file to uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(null, uri) }
    } catch (e: Exception) { // IOException, or a FileProvider missing from the merged manifest
        Log.w("KodifChat", "Can't offer the camera", e)
        null
    }

    /** [data] is the chooser's result, or null when it was cancelled. */
    private fun deliverFiles(data: Intent?) {
        val callback = fileCallback ?: return
        fileCallback = null
        val (photoFile, photoUri) = photo.also { photo = null } ?: (null to null)
        val clip = data?.clipData
        var uris = when {
            data == null -> emptyList()
            clip != null -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            else -> listOfNotNull(data.data)
        }
        if (data != null && uris.isEmpty() && photoUri != null && (photoFile?.length() ?: 0) > 0) uris = listOf(photoUri)
        if (photoUri !in uris) photoFile?.delete()
        callback.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    internal companion object {
        /** Whether the app declares CAMERA. It can't change while the app runs, so it's read once. */
        private var cameraDeclared: Boolean? = null

        fun isGranted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun declaresCamera(context: Context): Boolean = cameraDeclared ?: try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(Manifest.permission.CAMERA) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }.also { cameraDeclared = it }

        /** The MIME types an `<input type=file accept=...>` asks for (extensions mapped to types). */
        fun mimeTypes(params: FileChooserParams): List<String> = params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .mapNotNull { if (it.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) else it }
            .distinct()
    }
}
