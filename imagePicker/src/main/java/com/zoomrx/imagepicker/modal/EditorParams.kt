package com.zoomrx.imagepicker.modal

import android.graphics.Color
import java.io.File

data class EditorParams(val editorFlow: Int) {
    object EditFlow {
        const val FROM_CUSTOM_CAMERA = 1
        const val FROM_GALLERY = 2
    }
    var allowCaption = true
    var captionPlaceHolder = "Enter a caption"
    var allowAddition = true
    var allowDeletion = true
    var maxSelection = 10
    var navButtonTint = Color.rgb(255, 255, 255)
    var navBarTint = Color.rgb(11, 110, 244)
}