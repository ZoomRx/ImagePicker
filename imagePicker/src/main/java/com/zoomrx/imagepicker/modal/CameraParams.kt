package com.zoomrx.imagepicker.modal

import java.io.File

data class CameraParams(val saveToGallery: Boolean) {
    var targetFile: File? = null
}
