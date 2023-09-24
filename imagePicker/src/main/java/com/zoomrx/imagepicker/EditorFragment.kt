package com.zoomrx.imagepicker

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.VectorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toFile
import androidx.core.view.children
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.selection.*
import androidx.recyclerview.widget.RecyclerView
import com.yalantis.ucrop.UCrop
import com.zoomrx.imagepicker.ThumbnailList.CustomItemDetailsLookup
import com.zoomrx.imagepicker.ThumbnailList.CustomItemKeyProvider
import com.zoomrx.imagepicker.ThumbnailList.ThumbnailListAdapter
import com.zoomrx.imagepicker.modal.EditorParams
import com.zoomrx.imagepicker.modal.ImageFileParams
import com.zoomrx.imagepicker.modal.ImageProp
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

/**
 * A simple [Fragment] subclass.
 * Use the [EditorFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class EditorFragment : Fragment() {

    val imagePropArray = ArrayList<ImageProp>()
    private val fullBitmapJobArray = ArrayList<Job>()
    lateinit var selectedImageProp: ImageProp
    private var selectedItemPosition: Int = 0
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ThumbnailListAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var imageView: ImageView
    private lateinit var captionEditText: EditText
    private lateinit var undoButton: ImageButton
    private lateinit var deleteButton: ImageButton
    lateinit var userInputLayout: LinearLayout
    lateinit var editorParams: EditorParams
    lateinit var imageFileParams: ImageFileParams
    lateinit var backPressedListener: (Int) -> Unit
    lateinit var onEditCompleteListener: (ArrayList<Uri>, ArrayList<String>?) -> Unit
    private val coroutineIOScope = CoroutineScope(Dispatchers.IO)

    fun handleBackPressedEvent() {
        if (isVisible) {
            imagePropArray.forEach {
                it.editedUri?.toFile()?.deleteOnExit()
            }
            backPressedListener(editorParams.editorFlow)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val activity = context as FragmentActivity
        activity.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressedEvent()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.ThumbnailList)
        progressBar = view.findViewById(R.id.indeterminateBar)
        imageView = view.findViewById(R.id.image_full_preview)
        userInputLayout = view.findViewById(R.id.relativeLayout)
        captionEditText = view.findViewById(R.id.CaptionEditText)
        undoButton = view.findViewById<ImageButton>(R.id.undo_button)
        if (editorParams.allowCaption) {
            captionEditText.addTextChangedListener {
                imagePropArray[selectedItemPosition].captionText = it.toString()
            }
        }
        if (imagePropArray.size > editorParams.maxSelection) {
            var index = imagePropArray.size - 1
            var itemsRemoved = 0
            while (index >= editorParams.maxSelection) {
                imagePropArray.removeAt(index--)
                itemsRemoved++
            }
            Toast.makeText(requireContext(), "Maximum ${editorParams.maxSelection} images can be selected. Discarded last $itemsRemoved images.", Toast.LENGTH_LONG).show()
        }

        adapter = ThumbnailListAdapter(imagePropArray, requireContext(), coroutineIOScope) {
            selectedImageChanged(it)
        }.also {
            recyclerView.adapter = it
            it.selectionTracker = SelectionTracker.Builder(
                    "file-path-selection",
                    recyclerView,
                    CustomItemKeyProvider(it),
                    CustomItemDetailsLookup(recyclerView),
                    StorageStrategy.createStringStorage()
            ).withSelectionPredicate(
                    object : SelectionTracker.SelectionPredicate<String>() {
                        override fun canSetStateForKey(key: String, nextState: Boolean): Boolean {
                            return nextState
                        }

                        override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean {
                            return nextState
                        }

                        override fun canSelectMultiple(): Boolean {
                            return false
                        }
                    }
            ).build()
        }
        onImagesReceived(imagePropArray, true)
        forceSelectImage(selectedItemPosition)
        deleteButton = view.findViewById(R.id.delete_button)
        if (imagePropArray.size > 1) {
            if (editorParams.allowDeletion) {
                deleteButton.setOnClickListener {
                    onImageDeleted(selectedItemPosition)
                }
            } else {
                deleteButton.visibility = View.GONE
            }
        } else {
            recyclerView.visibility = View.GONE
            deleteButton.visibility = View.GONE
        }
        applyCustomTheme(view)
        view.findViewById<ImageButton>(R.id.crop_rotate_button).setOnClickListener {
            handleCropRotateButtonClick()
        }
        view.findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            handleBackPressedEvent()
        }
        view.findViewById<ImageButton>(R.id.send_button).setOnClickListener {
            handleSendButtonClick()
        }

        undoButton.visibility = View.GONE
        undoButton.setOnClickListener {
            handleUndoButtonClick()
        }
    }

    private fun handleUndoButtonClick() {
        imagePropArray[selectedItemPosition].editedUri?.toFile()?.deleteOnExit()
        imagePropArray[selectedItemPosition].editedUri = null
        imagePropArray[selectedItemPosition].fullSizeBitmap = null
        constructBitmapForPreview(selectedItemPosition)
        undoButton.visibility = View.GONE
    }

    private fun applyCustomTheme(view: View) {
        val navBar = view.findViewById<ConstraintLayout>(R.id.navBarLayout)
        navBar.background.setTint(editorParams.navBarTint)
        navBar.children.iterator().forEach {
            ((it as ImageButton).drawable as VectorDrawable).setTint(editorParams.navButtonTint)
        }
        val sendButton = view.findViewById<ImageButton>(R.id.send_button)
        (sendButton.drawable as VectorDrawable).setTint(editorParams.navButtonTint)
        sendButton.background.setTint(editorParams.navBarTint)
        imageView.setBackgroundColor(editorParams.backgroundColor)
        userInputLayout.setBackgroundColor(editorParams.backgroundColor)
    }

    private fun handleSendButtonClick() {
        val imageUriArray = arrayListOf<Uri>()
        var imageCaptionArray: ArrayList<String>? = null
        if (editorParams.allowCaption)
            imageCaptionArray = arrayListOf()
        imagePropArray.withIndex().forEach {
            imageUriArray.add(it.index, it.value.editedUri ?: it.value.originalUri)
            if (editorParams.allowCaption)
                imageCaptionArray?.add(it.index, it.value.captionText)
        }
        onEditCompleteListener(imageUriArray, imageCaptionArray)
    }

    private fun handleCropRotateButtonClick() {
        val uCropOptions = UCrop.Options().also {
            it.setFreeStyleCropEnabled(true)
            it.setCompressionFormat(Bitmap.CompressFormat.JPEG)
        }
        val destinationFile = File.createTempFile("${imageFileParams.fileNamePrefix}-${System.currentTimeMillis()}", ".JPEG", requireContext().filesDir)
        UCrop.of(selectedImageProp.originalUri, Uri.fromFile(destinationFile))
                .withOptions(uCropOptions)
                .start(requireContext(), this)
    }

    private fun onImagesReceived(newImagesProp: ArrayList<ImageProp>, alreadyAdded: Boolean) {
        if (!alreadyAdded) {
            val size = adapter.itemCount
            imagePropArray += newImagesProp
            adapter.notifyItemRangeInserted(size, newImagesProp.size)
        }
        newImagesProp.withIndex().forEach {
            constructBitmapForPreview(it.index)
        }
    }

    fun onImageChanged(imageProp: ImageProp, index: Int) {
        adapter.notifyItemChanged(index)
        constructBitmapForPreview(index)
    }

    private fun onImageDeleted(index: Int) {
        imagePropArray.removeAt(index)
        adapter.notifyItemRemoved(index)
        if (imagePropArray.size > 0) {
            if (imagePropArray.size == 1)
                deleteButton.visibility = View.GONE
            if (index == imagePropArray.size)
                forceSelectImage(index - 1)
            else
                forceSelectImage(index)
        } else {
            handleBackPressedEvent()
        }
    }

    private fun forceSelectImage(index: Int) {
        adapter.selectionTracker?.select(adapter.imagePropArrayList[index].filePath)
        selectedImageChanged(index)
    }

    private fun selectedImageChanged(index: Int) {
        selectedItemPosition = index
        selectedImageProp = adapter.imagePropArrayList[index].also {
            if (it.editedUri != null) {
                undoButton.visibility = View.VISIBLE
            } else {
                undoButton.visibility = View.GONE
            }
            if (it.fullSizeBitmap == null) {
                imageView.setImageDrawable(null)
                progressBar.visibility = View.VISIBLE
            } else {
                updateFullPreview(it.fullSizeBitmap!!)
            }
        }
        validateOptionsShown()
    }

    private fun validateOptionsShown() {
        captionEditText.setText(selectedImageProp.captionText, TextView.BufferType.EDITABLE)
    }

    private fun constructBitmapForPreview(index: Int) {
        val imageProp = imagePropArray[index]
        if (imageProp.fullSizeBitmap == null) {
            fullBitmapJobArray.add(index, coroutineIOScope.launch {
                val bitmap = async {
                    BitmapHandler.decodeSampledBitmapFromFileDescriptor(
                            Rect(imageView.left, imageView.top, imageView.right, imageView.bottom),
                            requireContext().contentResolver,
                            imageProp.editedUri ?: imageProp.originalUri,
                            dpToPixels(imageView.maxWidth),
                            dpToPixels(imageView.maxHeight)
                    )
                }
                imageProp.fullSizeBitmap = bitmap.await().also {
                    if (selectedItemPosition == index) {
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = View.GONE
                            updateFullPreview(it)
                        }
                    }
                }
            })
        }
    }

    private fun updateFullPreview(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
    }

    private fun dpToPixels(distance: Int): Int {
        return (requireContext().resources.displayMetrics.density * distance).roundToInt()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == UCrop.REQUEST_CROP) {
            undoButton.visibility = View.VISIBLE
            imagePropArray[selectedItemPosition].editedUri = data?.let { UCrop.getOutput(it) }
            imagePropArray[selectedItemPosition].fullSizeBitmap = null
            constructBitmapForPreview(selectedItemPosition)
        } else if (resultCode == UCrop.RESULT_ERROR) {
            val cropError = data?.let { UCrop.getError(it) }
            Toast.makeText(requireContext(), cropError?.localizedMessage ?: "error in cropping", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 - The initial array list of properties of images chosen.
         * @return A new instance of fragment EditorFragment.
         */
        @JvmStatic
        fun newInstance(param1: ArrayList<ImageProp>, editorParams: EditorParams) =
            EditorFragment().apply {
                imagePropArray += param1
                selectedImageProp = imagePropArray[0]
                this.editorParams = editorParams
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineIOScope.cancel()
    }
}