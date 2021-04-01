package com.zoomrx.imagepicker.modal

data class ImageFileParams(val directoryToCopy: String) {
    var fileNamePrefix = "IMG"
    var relativeDirectory: String? = null
    var shouldCompress = false
    var compressQuality = 60
}
