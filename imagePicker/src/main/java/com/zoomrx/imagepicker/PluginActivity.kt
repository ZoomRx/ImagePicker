package com.zoomrx.camera

import android.os.Bundle
import android.os.PersistableBundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity

class PluginActivity : AppCompatActivity() {

    object Constants {
        const val ACTIVITY_CREATED_CALLBACK_KEY = "activityCreatedCallbackId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.extras?.getInt(Constants.ACTIVITY_CREATED_CALLBACK_KEY)?.let {
            (applicationContext as CustomApplication).executeActivityCreatedCallback(it, this)
        }
    }
}