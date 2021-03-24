package com.zoomrx.imagepicker.modal

import android.graphics.Bitmap
import android.net.Uri

data class ImageProp(
        val filePath: String,
        val originalUri: Uri,
        var fullSizeBitmap: Bitmap?,
        var thumbnailBitmap: Bitmap?,
        var editedUri: Uri?,
        var captionText: String = ""
)