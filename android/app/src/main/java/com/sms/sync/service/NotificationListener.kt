package com.sms.sync.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sms.sync.extractor.CodeExtractor

class NotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "NotificationListener"
        private val IGNORED_PACKAGES = setOf(
            "com.sms.sync",
            "com.android.systemui",
            "android",
            "com.android.providers.downloads"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in IGNORED_PACKAGES) return

        val extras = sbn.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return

        val code = CodeExtractor.extract(text)
        if (code != null) {
            Log.i(TAG, "Code from notification (${sbn.packageName}): $code, delegating to ForegroundService")
            SyncForegroundService.sendCode(
                context = this,
                source = "notification",
                sender = "",
                code = code,
                rawText = text,
                appName = sbn.packageName
            )
        }
    }
}
