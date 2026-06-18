package com.jiangdg.utils

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

class HandlerThreadHandler private constructor(
    private val thread: HandlerThread,
    looper: Looper,
) : Handler(looper) {
    @Suppress("unused")
    fun quitSafely() {
        thread.quitSafely()
    }

    companion object {
        @JvmStatic
        fun createHandler(name: String): HandlerThreadHandler {
            val thread = HandlerThread(name).also { it.start() }
            return HandlerThreadHandler(thread, thread.looper)
        }
    }
}
