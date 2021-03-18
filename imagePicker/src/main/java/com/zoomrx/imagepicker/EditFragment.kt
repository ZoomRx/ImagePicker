package com.zoomrx.camera

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
import com.zoomrx.camera.ThumbnailList.CustomItemDetailsLookup
import com.zoomrx.camera.ThumbnailList.CustomItemKeyProvider
import com.zoomrx.camera.ThumbnailList.ThumbnailListAdapter
import com.zoomrx.camera.camera.R
import com.zoomrx.camera.modal.EditorParams
import com.zoomrx.camera.modal.ImageFileParams
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

/**
 * A simple [Fragment] subclass.
 * Use the [EditFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class EditFragment : Fragment() {

    data class ImageProp(val filePath: String, val originalUri: Uri, var fullSizeBitmap: Bitmap?, var thumbnailBitmap: Bitmap?, var editedUri: Uri?, var captionText: String = "")

    // TODO: Rename and change types of parameters
    val imagePropArray = ArrayList<ImageProp>()
    private val fullBitmapJobArray = ArrayList<Job>()
    lateinit var selectedImageProp: ImageProp
    private var selectedItemPosition: Int = 0
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ThumbnailListAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var imageView: ImageView
    private lateinit var captionEditText: EditText
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
        applyCustomTheme(view)
        recyclerView = view.findViewById(R.id.ThumbnailList)
        progressBar = view.findViewById(R.id.indeterminateBar)
        imageView = view.findViewById(R.id.image_full_preview)
        captionEditText = view.findViewById(R.id.CaptionEditText)
        if (editorParams.allowCaption) {
            captionEditText.addTextChangedListener {
                imagePropArray[selectedItemPosition].captionText = it.toString()
            }
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
        view.findViewById<ImageButton>(R.id.crop_rotate_button).setOnClickListener {
            handleCropRotateButtonClick()
        }
        view.findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            handleBackPressedEvent()
        }
        val deleteButton = view.findViewById<ImageButton>(R.id.delete_button)
        if (editorParams.allowDeletion) {
            deleteButton.setOnClickListener {
                onImageDeleted(selectedItemPosition)
            }
        } else {
            deleteButton.visibility = View.GONE
        }
        view.findViewById<ImageButton>(R.id.send_button).setOnClickListener {
            handleSendButtonClick()
        }
    }

    private fun applyCustomTheme(view: View) {
        val navBar = view.findViewById<ConstraintLayout>(R.id.navBarLayout)
        navBar.setBackgroundColor(editorParams.navBarTint)
        navBar.children.iterator().forEach {
            ((it as ImageButton).drawable as VectorDrawable).setTint(editorParams.navButtonTint)
        }
        val sendButton = view.findViewById<ImageButton>(R.id.send_button)
        (sendButton.drawable as VectorDrawable).setTint(editorParams.navButtonTint)
        sendButton.background.setTint(editorParams.navBarTint)
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
            if (index == imagePropArray.size)
                forceSelectImage(index - 1)
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
         * @return A new instance of fragment EditFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: ArrayList<ImageProp>, editorParams: EditorParams) =
            EditFragment().apply {
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