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
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * What the session needs from the activity or fragment showing the chat: dismissing it, runtime permissions and the
 * file chooser. Create it in `onCreate` (activity result launchers must be registered before the host starts).
 */
internal class ChatHost(
    caller: ActivityResultCaller,
    private val activityProvider: () -> Activity?,
    private val onDismiss: () -> Unit,
) {
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val fileLauncher = caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deliverFiles(it)
    }

    private val permissionLauncher = caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val callback = permissionCallback
        permissionCallback = null
        callback?.invoke(it)
    }

    val activity: Activity? get() = activityProvider()

    fun dismiss() = onDismiss()

    /** Asks for [permission] (one request at a time), answering [callback] with whether it was granted. */
    fun requestPermission(permission: String, callback: (Boolean) -> Unit) {
        if (permissionCallback != null) {
            callback(false)
            return
        }
        permissionCallback = callback
        try {
            permissionLauncher.launch(permission)
        } catch (e: IllegalStateException) {
            permissionCallback = null
            callback(false)
        }
    }

    /** Shows the system chooser for files, plus the camera when the request accepts images. */
    fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        val activity = activity ?: return false
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val acceptsImages = acceptsImages(params)
        val canUseCamera = acceptsImages && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        // An app that declares CAMERA must hold it before another app can capture for it; one that doesn't needs nothing.
        if (canUseCamera && declaresPermission(activity, Manifest.permission.CAMERA) &&
            !isGranted(activity, Manifest.permission.CAMERA)
        ) {
            requestPermission(Manifest.permission.CAMERA) { granted -> launchChooser(activity, params, granted) }
        } else {
            launchChooser(activity, params, canUseCamera)
        }
        return true
    }

    /** Answers anything still waiting on this host (it is going away). */
    fun cancelPending() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        val callback = permissionCallback
        permissionCallback = null
        callback?.invoke(false)
    }

    private fun launchChooser(activity: Activity, params: FileChooserParams, withCamera: Boolean) {
        if (fileCallback == null) return
        val content = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        val mimeTypes = mimeTypes(params)
        if (mimeTypes.isNotEmpty()) content.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        val chooser = Intent.createChooser(content, null)
        if (withCamera) cameraIntent(activity)?.let { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
        try {
            fileLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.w(KodifChatSession.TAG, "No file chooser available", e)
            deliverFiles(null)
        }
    }

    private fun cameraIntent(context: Context): Intent? = try {
        val directory = File(context.cacheDir, PHOTO_DIRECTORY).apply { mkdirs() }
        val file = File.createTempFile("photo-", ".jpg", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
        cameraFile = file
        cameraUri = uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(null, uri) }
    } catch (e: IOException) {
        Log.w(KodifChatSession.TAG, "Can't create a file for the camera", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(KodifChatSession.TAG, "The Kodif chat FileProvider is missing from the merged manifest", e)
        null
    }

    private fun deliverFiles(result: ActivityResult?) {
        val callback = fileCallback ?: return
        fileCallback = null
        val photo = cameraFile
        val photoUri = cameraUri
        cameraFile = null
        cameraUri = null

        var uris: List<Uri> = emptyList()
        if (result?.resultCode == Activity.RESULT_OK) {
            uris = result.data?.let { urisFrom(it) }.orEmpty()
            if (uris.isEmpty() && photo != null && photoUri != null && photo.length() > 0) uris = listOf(photoUri)
        }
        if (photo != null && photoUri !in uris) photo.delete()
        callback.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private fun urisFrom(data: Intent): List<Uri> {
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(data.data)
    }

    internal companion object {
        const val PHOTO_DIRECTORY = "kodifchat"
        const val AUTHORITY_SUFFIX = ".kodifchat.fileprovider"

        fun isGranted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun declaresPermission(context: Context, permission: String): Boolean = try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.contains(permission) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        /** The MIME types an `<input type=file accept=...>` asks for (extensions mapped to types). */
        fun mimeTypes(params: FileChooserParams): List<String> = params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .mapNotNull { type ->
                if (type.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1)) else type
            }
            .distinct()

        private fun acceptsImages(params: FileChooserParams): Boolean {
            val types = mimeTypes(params)
            return types.isEmpty() || types.any { it == "*/*" || it.startsWith("image/") }
        }
    }
}
