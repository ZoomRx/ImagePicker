package com.zoomrx.camera.ThumbnailList

import androidx.recyclerview.selection.ItemKeyProvider

class CustomItemKeyProvider(private val adapter: ThumbnailListAdapter) : ItemKeyProvider<String>(SCOPE_CACHED) {
    override fun getKey(position: Int): String {
        return adapter.imagePropArrayList[position].filePath
    }

    override fun getPosition(key: String): Int {
        return adapter.imagePropArrayList.withIndex().find {
            it.value.filePath == key
        }?.index ?: 0
    }
}