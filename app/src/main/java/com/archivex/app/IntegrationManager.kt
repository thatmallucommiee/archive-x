package com.archivex.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object IntegrationManager {
    fun open(context: Context, uri: Uri, mimeType: String? = null): String {
        val type = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, if (type == "application/vnd.android.package-archive") "Install APK" else "Open with"))
            "Handed file to Android. The selected application controls the next step."
        } catch (_: ActivityNotFoundException) {
            "No compatible application was found for this file type."
        } catch (e: Exception) {
            "Could not open file: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun share(context: Context, uri: Uri, mimeType: String? = null): String {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(context.contentResolver, "ARCHIVE X file", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share file"))
            "Android share sheet opened."
        } catch (e: Exception) {
            "Could not share file: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
