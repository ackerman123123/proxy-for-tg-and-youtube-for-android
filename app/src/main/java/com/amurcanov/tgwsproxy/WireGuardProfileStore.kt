package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Keeps the imported WireGuard credential in Android private no-backup storage.
 * The original .conf is deliberately never copied to shared storage, the APK, or the repository.
 */
object WireGuardProfileStore {
    private const val PROFILE_FILE = "youtube-wireguard.conf"
    private const val PREFS = "youtube_wireguard_profile"
    private const val PREF_DISPLAY_NAME = "display_name"
    private const val MAX_PROFILE_BYTES = 128 * 1024

    fun import(context: Context, uri: Uri): String {
        val config = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            require(bytes.isNotEmpty() && bytes.size <= MAX_PROFILE_BYTES) {
                "Unsupported WireGuard profile size"
            }
            bytes.toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("Unable to read WireGuard profile")

        WireGuardYouTubeController.parseForYoutube(config)

        val destination = profileFile(context)
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        temporary.writeText(config, Charsets.UTF_8)
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }

        val displayName = displayName(context, uri)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DISPLAY_NAME, displayName)
            .apply()
        return displayName
    }

    fun read(context: Context): String? {
        val file = profileFile(context)
        return file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    fun profileName(context: Context): String? {
        return if (profileFile(context).isFile) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_DISPLAY_NAME, null) ?: "WireGuard profile"
        } else {
            null
        }
    }

    fun delete(context: Context) {
        profileFile(context).delete()
        File(profileFile(context).parentFile, PROFILE_FILE + ".tmp").delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_DISPLAY_NAME)
            .apply()
    }

    private fun profileFile(context: Context): File {
        return File(context.noBackupFilesDir, PROFILE_FILE)
    }

    private fun displayName(context: Context, uri: Uri): String {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?.takeIf { it.isNotBlank() }
            ?: "WireGuard profile"
    }
}
