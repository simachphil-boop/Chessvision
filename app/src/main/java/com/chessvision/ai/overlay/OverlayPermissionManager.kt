package com.chessvision.ai.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** "Display over other apps" (SYSTEM_ALERT_WINDOW) permission helper. */
object OverlayPermissionManager {
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Opens the system screen where the user grants the permission for this app. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Fallback for OEM builds (some Huawei/Xiaomi) that lack the per-app screen. */
    fun fallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings(context: Context): Boolean {
        for (i in listOf(settingsIntent(context), fallbackIntent(), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            try { context.startActivity(i); return true } catch (_: Exception) { }
        }
        return false
    }
}
