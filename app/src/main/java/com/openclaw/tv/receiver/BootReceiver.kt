package com.openclaw.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.net.conn.CONNECTIVITY_CHANGE",
            null,
            -> ReceiverService.start(context, refresh = true)
        }
    }
}
