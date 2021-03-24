package com.zoomrx.imagepicker.ThumbnailList

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.RecyclerView
import com.zoomrx.imagepicker.BitmapHandler
import com.zoomrx.imagepicker.R
import com.zoomrx.imagepicker.modal.ImageProp
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class ThumbnailListAdapter(
        val imagePropArrayList: ArrayList<ImageProp>,
        private val context: Context, val coroutineIOScope: CoroutineScope, private val activatedListener: (Int) -> Unit)
    : RecyclerView.Adapter<ThumbnailListAdapter.ViewHolder>() {
    /**
     * Provide a reference to the type of views that you are using
     * (custom ViewHolder).
     */
    var selectionTracker: SelectionTracker<String>? = null
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.item_image)
        var imageProp: ImageProp? = null
        init {
            // Define click listener for the ViewHolder's View.

        }
        fun getItemDetails(): ItemDetailsLookup.ItemDetails<String> =
                object : ItemDetailsLookup.ItemDetails<String>() {
                    override fun getPosition(): Int = adapterPosition
                    override fun getSelectionKey(): String? = imageProp?.filePath
                }
        
    }

    // Create new views (invoked by the layout manager)
    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        // Create a new view, which defines the UI of the list item
        val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.item_layout, viewGroup, false)

        return ViewHolder(view)
    }

    // Replace the contents of a view (invoked by the layout manager)
    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

        // Get element from your data set at this position and replace the
        // contents of the view with that element

        selectionTracker?.isSelected(viewHolder.getItemDetails().selectionKey)?.let {
            val previousState = viewHolder.itemView.isActivated
            viewHolder.itemView.isActivated = it
            if (it && !previousState)
                activatedListener(position)
        }
        imagePropArrayList[position].let {
            viewHolder.imageProp = it
            val setImageView = { bitmap: Bitmap ->
                viewHolder.imageView.setImageBitmap(bitmap)
            }
            if (it.thumbnailBitmap == null) {
                coroutineIOScope.launch {
                    val bitmap = async {
                        BitmapHandler.decodeSampledBitmapFromFileDescriptor(
                                with(viewHolder.imageView, {
                                    Rect(left, top, right, bottom)
                                }),
                                context.contentResolver,
                                it.originalUri,
                                dpToPixels(context.resources.getInteger(R.integer.list_item_width)),
                                dpToPixels(context.resources.getInteger(R.integer.list_item_width))
                        )
                    }
                    it.thumbnailBitmap = bitmap.await().also {
                        withContext(Dispatchers.Main) {
                            setImageView(it)
                        }
                    }
                }
            } else {
                setImageView(it.thumbnailBitmap!!)
            }
        }
    }

    // Return the size of your dataset (invoked by the layout manager)
    override fun getItemCount() = imagePropArrayList.size

    private fun dpToPixels(distance: Int): Int {
        return (context.resources.displayMetrics.density * distance).roundToInt()
    }
}