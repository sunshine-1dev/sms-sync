package com.sms.sync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.sms.sync.extractor.CodeExtractor
import com.sms.sync.network.WebSocketClient

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (msg in messages) {
            val body = msg.messageBody ?: continue
            val sender = msg.originatingAddress
            Log.d(TAG, "SMS from $sender: $body")

            val code = CodeExtractor.extract(body)
            if (code != null) {
                Log.i(TAG, "Verification code detected: $code")
                WebSocketClient.sendCode(
                    source = "sms",
                    sender = sender,
                    code = code,
                    rawText = body
                )
            }
        }
    }
}
