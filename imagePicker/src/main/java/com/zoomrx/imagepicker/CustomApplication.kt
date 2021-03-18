package com.zoomrx.camera

import android.app.Application
import android.content.Context

class CustomApplication : Application() {

    private val onActivityCreatedCallbacks = mutableMapOf<Int, (Context) -> Unit>()
    var activityCreatedCallbackIndex = 0

    fun executeActivityCreatedCallback(callbackId: Int, context: Context) {
        onActivityCreatedCallbacks[callbackId]?.let { it(context) }
    }

    fun registerActivityCreatedCallback(callback: (Context) -> Unit): Int {
        onActivityCreatedCallbacks[activityCreatedCallbackIndex] = callback
        return activityCreatedCallbackIndex++
    }
}