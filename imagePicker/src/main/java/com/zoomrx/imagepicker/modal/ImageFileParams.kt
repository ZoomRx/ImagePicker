package com.zoomrx.camera.modal

data class ImageFileParams(val directoryToCopy: String) {
    var fileNamePrefix = "IMG"
    var relativeDirectory: String? = null
}
