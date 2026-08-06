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
            // Az Outlook Web App belső OWA végpontja
            val url = URL("https://outlook.cloud.microsoft/owa/service.svc?action=GetUnreadCount")
            val connection = url.openConnection() as HttpURLConnection
            
            val cookies = CookieManager.getInstance().getCookie("https://outlook.cloud.microsoft")
            if (cookies.isNullOrEmpty()) {
                return false // Ha nincs süti (még nincs bejelentkezve), nem tud ellenőrizni
            }

            connection.requestMethod = "POST"
            connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            // Üres JSON body küldése az OWA kéréshez
            val outputStream = connection.outputStream
            outputStream.write("{}".toByteArray())
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                // Ha a válaszban a számláló nagyobb mint 0
                response.contains("\"UnreadCount\":") && !response.contains("\"UnreadCount\":0")
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sendNotification() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "outlook_mail_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, 
                "Outlook Új Levelek", 
                NotificationManager.IMPORTANCE_HIGH // HIGH kell, hogy feldobja a kijelzőre
            ).apply {
                description = "Értesítés új olvasatlan levelekről"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Outlook")
            .setContentText("Új olvasatlan leveled érkezett!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
