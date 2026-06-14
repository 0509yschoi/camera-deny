package com.example.studycapturehelper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.stopService(Intent(context, CaptureForegroundService::class.java))
    }
}
