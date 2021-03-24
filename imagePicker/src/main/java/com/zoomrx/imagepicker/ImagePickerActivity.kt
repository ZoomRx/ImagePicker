package com.zoomrx.imagepicker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class ImagePickerActivity : AppCompatActivity() {

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