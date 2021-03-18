package com.zoomrx.imagepicker

import android.content.ContentResolver
import android.provider.MediaStore

class GalleryCursor(val contentResolver: ContentResolver) {

    val numberToMonthMap = mapOf(
            "01" to "Jan",
            "02" to "Feb",
            "03" to "Mar",
            "04" to "Apr",
            "05" to "May",
            "06" to "Jun",
            "07" to "Jul",
            "08" to "Aug",
            "09" to "Sep",
            "10" to "Oct",
            "11" to "Nov",
            "12" to "Dec"
    )

    fun getMonthAndYear() {

    }
    fun queryMediaImages() {
        val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                        "strftime('%m', datetime(${MediaStore.Images.ImageColumns.DATE_ADDED}, 'unixepoch')) as month",
                        "strftime('%Y', datetime(${MediaStore.Images.ImageColumns.DATE_ADDED}, 'unixepoch')) as year",
                        MediaStore.Images.ImageColumns.DATE_ADDED,
                        MediaStore.Images.ImageColumns.ORIENTATION,
                        MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                        MediaStore.Images.ImageColumns.DISPLAY_NAME,
                        MediaStore.Images.ImageColumns.RELATIVE_PATH,
                        MediaStore.Images.ImageColumns.MIME_TYPE
                ),
                "month != strftime('%m', 'now') AND year != strftime('%Y', 'now)",
                null,
                "${MediaStore.Images.ImageColumns.DATE_ADDED} DESC"
        )
        cursor?.close()
    }

    fun queryMediaCountForEachMonth() {
        val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                        "strftime('%m', datetime(${MediaStore.Images.ImageColumns.DATE_ADDED}, 'unixepoch')) as month",
                        "strftime('%Y', datetime(${MediaStore.Images.ImageColumns.DATE_ADDED}, 'unixepoch')) as year",
                        "COUNT(${MediaStore.Images.ImageColumns.DISPLAY_NAME}) AS images_count"
                ),
                "1) GROUP BY month, year, (1",
                null,
                "${MediaStore.Images.ImageColumns.DATE_ADDED} DESC"
        )
        cursor?.close()
    }
}