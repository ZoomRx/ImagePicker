package com.zoomrx.imagepicker.modal

import java.io.File

data class CameraParams(val saveToGallery: Boolean) {
    var saveAsPublic: Boolean = false
    var targetFile: File? = null
}
