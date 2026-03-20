package com.sms.sync.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sms.sync.extractor.CodeExtractor
import com.sms.sync.network.WebSocketClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SyncForegroundService : Service() {
    companion object {
        private const val TAG = "SyncForegroundService"
        private const val CHANNEL_ID = "sms_sync_foreground"
        private const val DEBUG_CHANNEL_ID = "sms_sync_debug"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 4000L // 4 seconds

        const val ACTION_SEND_CODE = "com.sms.sync.SEND_CODE"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_CODE = "code"
        const val EXTRA_RAW_TEXT = "raw_text"
        const val EXTRA_APP_NAME = "app_name"

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }

        fun sendCode(context: Context, source: String, sender: String?, code: String, rawText: String, appName: String = "") {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_SEND_CODE
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_SENDER, sender ?: "")
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_RAW_TEXT, rawText)
                putExtra(EXTRA_APP_NAME, appName)
            }
            context.startForegroundService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null
    private val debugNotifId = AtomicInteger(100)

    // SMS polling state
    private var lastSmsTimestamp = 0L
    private val sentCodes = mutableSetOf<String>() // "code:timestamp" to avoid dups

    // ContentObserver for instant SMS detection
    private var smsObserver: ContentObserver? = null
    private val observerThread = HandlerThread("SmsObserver").apply { start() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Sync")
            .setContentText("Listening for verification codes...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        acquireWakeLock()
        WebSocketClient.init(this)

        // Initialize lastSmsTimestamp to now so we don't process old messages
        lastSmsTimestamp = System.currentTimeMillis()

        // Start both ContentObserver (instant) and polling (fallback)
        startSmsObserver()
        startSmsPollling()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND_CODE) {
            val source = intent.getStringExtra(EXTRA_SOURCE) ?: ""
            val sender = intent.getStringExtra(EXTRA_SENDER) ?: ""
            val code = intent.getStringExtra(EXTRA_CODE) ?: ""
            val rawText = intent.getStringExtra(EXTRA_RAW_TEXT) ?: ""
            val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: ""

            if (code.isNotBlank()) {
                handleCode(source, sender, code, rawText, appName)
            }
        }
        return START_STICKY
    }

    private fun handleCode(source: String, sender: String, code: String, rawText: String, appName: String) {
        // Dedup key
        val key = "$code:${System.currentTimeMillis() / 10000}" // 10-second window
        if (sentCodes.contains(key)) {
            Log.d(TAG, "Duplicate code $code, skipping")
            return
        }
        sentCodes.add(key)
        // Keep set small
        if (sentCodes.size > 50) {
            val iter = sentCodes.iterator()
            repeat(25) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }

        Log.i(TAG, "Processing code: $code from $source")
        showDebugNotification("Detected code $code from $source")

        executor.execute {
            try {
                WebSocketClient.sendCode(source, sender, code, rawText, appName)
                Log.i(TAG, "Code $code sent successfully")
                showDebugNotification("SUCCESS - code $code sent!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send code: ${e.message}")
                showDebugNotification("FAILED - ${e.message}")
            }
        }
    }

    // ── SMS ContentObserver: instant detection ──
    private fun startSmsObserver() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No READ_SMS permission, skipping ContentObserver")
            return
        }

        smsObserver = object : ContentObserver(Handler(observerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "SMS ContentObserver triggered")
                checkNewSms()
            }
        }

        contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            smsObserver!!
        )
        Log.i(TAG, "SMS ContentObserver registered")
    }

    // ── SMS Polling: fallback for when ContentObserver is deferred ──
    private fun startSmsPollling() {
        pollTask = scheduler.scheduleWithFixedDelay(
            { checkNewSms() },
            POLL_INTERVAL_MS,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        Log.i(TAG, "SMS polling started (every ${POLL_INTERVAL_MS}ms)")
    }

    private fun checkNewSms() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) return

        try {
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                ),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(lastSmsTimestamp.toString()),
                "${Telephony.Sms.DATE} ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val sender = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))

                    if (date > lastSmsTimestamp) {
                        lastSmsTimestamp = date
                    }

                    val code = CodeExtractor.extract(body)
                    if (code != null) {
                        Log.i(TAG, "Poll found code: $code from $sender")
                        handleCode("sms", sender, code, body, "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS poll error: ${e.message}")
        }
    }

    override fun onDestroy() {
        pollTask?.cancel(false)
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        observerThread.quitSafely()
        releaseWakeLock()
        executor.shutdown()
        scheduler.shutdown()
        super.onDestroy()
    }

    private fun showDebugNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, DEBUG_CHANNEL_ID)
            .setContentTitle("SMS Sync Debug")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(debugNotifId.incrementAndGet(), notification)
        Log.i(TAG, "DEBUG: $text")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SMSSync::ForegroundWakeLock"
        ).apply {
            acquire()
        }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SMS Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the verification code listener running"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                DEBUG_CHANNEL_ID,
                "SMS Sync Debug",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Debug notifications for troubleshooting"
            }
        )
    }
}
