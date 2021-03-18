package com.zoomrx.imagepicker.ThumbnailList

import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.RecyclerView
import com.zoomrx.imagepicker.ThumbnailList.ThumbnailListAdapter

class CustomItemDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<String>() {
    override fun getItemDetails(event: MotionEvent): ItemDetails<String>? {
        val view = recyclerView.findChildViewUnder(event.x, event.y)
        if (view != null) {
            return (recyclerView.getChildViewHolder(view) as ThumbnailListAdapter.ViewHolder).getItemDetails()
        }
        return null
    }
}