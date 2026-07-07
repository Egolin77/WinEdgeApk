package com.example.winedge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

class UniversalWebNotificationBridge(
    private val context: Context
) {
    private var lastMessage: String = ""
    private var lastNotifyAt: Long = 0L

    @android.webkit.JavascriptInterface
    fun notifyFromWeb(title: String?, message: String?, url: String?) {
        val cleanTitle = title?.take(120)?.ifBlank { "Web notification" } ?: "Web notification"
        val cleanMessage = message?.take(240)?.ifBlank { url ?: "Activity detected" }
            ?: url
            ?: "Activity detected"

        val combined = "$cleanTitle|$cleanMessage|$url"
        val now = SystemClock.elapsedRealtime()

        // Duplikáció és túl gyakori jelzés szűrése
        if (combined == lastMessage && now - lastNotifyAt < 60_000) return
        if (now - lastNotifyAt < 8_000) return

        lastMessage = combined
        lastNotifyAt = now

        val hasPermission =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            NotificationHelper.showGenericWebNotification(
                context = context,
                title = cleanTitle,
                message = cleanMessage,
                url = url ?: ""
            )
        }
    }
}

