package com.aeidolon.vaultexplorer.saf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Root folder of a directory vault. Normally a SAF tree URI; on devices
 * without a system folder picker it is the file:// URI handed back by
 * [com.aeidolon.vaultexplorer.FallbackFilePicker], which
 * [DocumentFile.fromTreeUri] rejects with an exception.
 */
object VaultRootDocument {
    fun isFileUri(uri: Uri): Boolean = uri.scheme == "file"

    fun from(context: Context, uri: Uri): DocumentFile? {
        if (!isFileUri(uri)) return DocumentFile.fromTreeUri(context, uri)
        val dir = File(uri.path ?: return null)
        return if (dir.isDirectory) DocumentFile.fromFile(dir) else null
    }
}
