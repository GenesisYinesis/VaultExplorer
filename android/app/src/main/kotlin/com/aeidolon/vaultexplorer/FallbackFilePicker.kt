package com.aeidolon.vaultexplorer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.ArrayAdapter
import androidx.core.app.ActivityCompat
import java.io.File

/**
 * Minimal in-app file browser used only when the device has no system
 * document picker (for example the Xiaomi Mi TV Box on Android 9, where
 * ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT resolve to a stub that just
 * says "no app can handle this"). It is a plain AlertDialog list, so it works
 * with a TV remote (D-pad) out of the box. It hands back raw java.io.File
 * objects; callers turn them into file:// URIs, which the existing
 * RawFileResolver / UriToPath code already understands.
 */
const val FALLBACK_STORAGE_PERMISSION_REQUEST_CODE = 9825

object FallbackFilePicker {

    /**
     * True when [intent] cannot be served by a real system picker. The stub
     * activity that TV builds ship is recognised by its package name. A null
     * resolution is only trusted on API <= 29: from API 30 on, package
     * visibility rules can make resolveActivity return null even when a
     * picker exists, and we must never replace a working picker.
     */
    fun isSystemPickerMissing(activity: Activity, intent: Intent): Boolean {
        val component = try {
            intent.resolveActivity(activity.packageManager)
        } catch (_: Exception) {
            null
        }
        if (component != null) {
            return component.packageName.contains("frameworkpackagestubs")
        }
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
    }

    private var pendingGranted: (() -> Unit)? = null
    private var pendingDenied: (() -> Unit)? = null

    /**
     * Runs [onGranted] once raw storage access is available, otherwise
     * [onDenied]. Below API 30 the READ/WRITE_EXTERNAL_STORAGE runtime
     * permissions are requested and the outcome arrives through
     * [onPermissionResult]. From API 30 on, all-files access cannot be
     * requested with a runtime dialog, so a short explanation is shown and
     * [onDenied] runs.
     */
    fun withStoragePermission(activity: Activity, onGranted: () -> Unit, onDenied: () -> Unit) {
        if (RawFileResolver.hasExternalStoragePermission(activity)) {
            onGranted()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            pendingDenied?.invoke()
            pendingGranted = onGranted
            pendingDenied = onDenied
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                FALLBACK_STORAGE_PERMISSION_REQUEST_CODE,
            )
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.fallback_picker_permission_title)
            .setMessage(R.string.fallback_picker_permission_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        onDenied()
    }

    /** Called from MainActivity.onRequestPermissionsResult. Returns true when the code was ours. */
    fun onPermissionResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != FALLBACK_STORAGE_PERMISSION_REQUEST_CODE) return false
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val onGranted = pendingGranted
        val onDenied = pendingDenied
        pendingGranted = null
        pendingDenied = null
        if (granted) onGranted?.invoke() else onDenied?.invoke()
        return true
    }

    fun pickFile(activity: Activity, onResult: (File?) -> Unit) {
        browse(activity, null, false, "", onResult)
    }

    fun pickSaveLocation(activity: Activity, fileName: String, onResult: (File?) -> Unit) {
        browse(activity, null, true, fileName, onResult)
    }

    private fun roots(): List<File> {
        val list = ArrayList<File>()
        list.add(Environment.getExternalStorageDirectory())
        try {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "emulated" && f.name != "self" && f.canRead()) {
                    list.add(f)
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..9999) {
            val candidate = File(dir, base + "_" + i + ext)
            if (!candidate.exists()) return candidate
        }
        return File(dir, base + "_" + System.currentTimeMillis() + ext)
    }

    private fun browse(
        activity: Activity,
        dir: File?,
        saveMode: Boolean,
        fileName: String,
        onResult: (File?) -> Unit,
    ) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val rootList = roots()
        val title: String

        if (dir == null) {
            title = activity.getString(R.string.fallback_picker_choose_storage)
            for (root in rootList) {
                val label = if (root == Environment.getExternalStorageDirectory()) {
                    activity.getString(R.string.fallback_picker_internal_storage)
                } else {
                    root.name
                }
                labels.add(label + "/")
                actions.add { browse(activity, root, saveMode, fileName, onResult) }
            }
        } else {
            title = dir.absolutePath
            if (saveMode) {
                labels.add(activity.getString(R.string.fallback_picker_save_here, fileName))
                actions.add { onResult(uniqueFile(dir, fileName)) }
            }
            labels.add(activity.getString(R.string.fallback_picker_parent))
            actions.add {
                val parent = if (rootList.contains(dir)) null else dir.parentFile
                browse(activity, parent, saveMode, fileName, onResult)
            }
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                null
            }
            if (children == null) {
                labels.add(activity.getString(R.string.fallback_picker_unreadable))
                actions.add { browse(activity, dir, saveMode, fileName, onResult) }
            } else {
                val sorted = children.sortedWith(
                    compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
                )
                for (child in sorted) {
                    if (child.isDirectory) {
                        labels.add(child.name + "/")
                        actions.add { browse(activity, child, saveMode, fileName, onResult) }
                    } else if (!saveMode) {
                        labels.add(child.name)
                        actions.add { onResult(child) }
                    }
                }
            }
        }

        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setAdapter(adapter) { dialog, which ->
                dialog.dismiss()
                actions[which]()
            }
            .setNegativeButton(R.string.fallback_picker_cancel) { _, _ -> onResult(null) }
            .setOnCancelListener { onResult(null) }
            .show()
    }
}
