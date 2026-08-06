package com.example.winedge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL

class MailCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): ListenableWorker.Result {
        val hasUnread = checkUnreadMail()

        if (hasUnread) {
            sendNotification()
        }

        return ListenableWorker.Result.success()
    }

    private fun checkUnreadMail(): Boolean {
        return try {
            val url = URL("https://outlook.cloud.microsoft/api/v2.0/me/messages?\$filter=IsRead eq false")
            val connection = url.openConnection() as HttpURLConnection
            
            val cookies = CookieManager.getInstance().getCookie("https://outlook.cloud.microsoft")
            if (cookies != null) {
                connection.setRequestProperty("Cookie", cookies)
            }
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                response.contains("\"value\":[") && !response.contains("\"value\":[]")
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sendNotification() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mail_notifications"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Új Levelek", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Outlook")
            .setContentText("Új olvasatlan leveled érkezett!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
