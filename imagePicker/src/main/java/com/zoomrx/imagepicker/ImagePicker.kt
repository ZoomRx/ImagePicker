package com.zoomrx.camera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import com.zoomrx.camera.modal.CameraParams
import com.zoomrx.camera.modal.EditorParams
import com.zoomrx.camera.modal.GalleryParams
import com.zoomrx.camera.modal.ImageFileParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ImagePicker(
        val context: Context,
        val imageFileParams: ImageFileParams,
        val editorParams: EditorParams?,
        val nativeCallback: NativeCallbackInterface
) {
    object PhotoSource {
        const val CAMERA = 0
        const val GALLERY = 1
    }

    object ErrorCodes {
        const val FILE_CREATION = 101
        const val URI_CREATION = 102
        const val GALLERY_FILE_URI = 103
    }

    private val appContext = context.applicationContext as CustomApplication
    private lateinit var pluginActivity: PluginActivity
    private lateinit var galleryParams: GalleryParams
    private lateinit var cameraParams: CameraParams
    private lateinit var galleryActivityResultLauncher: ActivityResultLauncher<String>

    fun startCameraWorkFlow(cameraParams: CameraParams) {
        this.cameraParams = cameraParams
        val fileName = imageFileParams.fileNamePrefix + "-${System.currentTimeMillis()}.JPEG"
        val outputFileOptions = try {
            createOutputFileOptions(imageFileParams.relativeDirectory, fileName, cameraParams.saveAsPublic, cameraParams.saveToGallery)
        } catch (ex: IOException) {
            // Error occurred while creating the File
            nativeCallback.reject("Error in creating image file", ErrorCodes.FILE_CREATION)
            null
        }

        if (outputFileOptions == null) {
            nativeCallback.reject("Error getting URI for new image file", ErrorCodes.URI_CREATION)
            return
        }

        context.startActivity(Intent(context, PluginActivity::class.java).apply {
            putExtra(PluginActivity.Constants.ACTIVITY_CREATED_CALLBACK_KEY, appContext.registerActivityCreatedCallback { context ->
                pluginActivity = context as PluginActivity
                pluginActivity.supportFragmentManager.addOnBackStackChangedListener {
                    if (pluginActivity.supportFragmentManager.backStackEntryCount == 0) {
                        pluginActivity.finish()
                    }
                }
                val cameraFragment = CameraFragment()
                cameraFragment.cameraParams = cameraParams
                cameraFragment.callBack = { savedUri, code, message ->
                    if (savedUri != null) {
                        editorParams?.let {
                            showEditScreen(
                                    arrayListOf(
                                            EditFragment.ImageProp(
                                                    savedUri.path!!,
                                                    savedUri,
                                                    null,
                                                    null,
                                                    null)
                                    ),
                                    it
                            )
                        } ?: run {
                            pluginActivity.supportFragmentManager.popBackStack()
                            nativeCallback.resolve(copyImageFiles(imageFileParams.directoryToCopy, arrayOf(savedUri)), null)
                        }
                    } else {
                        cameraParams.targetFile?.deleteOnExit()
                        if (code == CameraFragment.ErrorCodes.BACK_PRESSED) {
                            pluginActivity.supportFragmentManager.popBackStack()
                            nativeCallback.resolve(arrayListOf(), null)
                        } else {
                            nativeCallback.reject(message!!, code!!)
                        }
                    }
                }
                cameraFragment.outputFileOptions = outputFileOptions
                val ft = pluginActivity.supportFragmentManager.beginTransaction()
                ft.add(android.R.id.content, cameraFragment, "CAMERA")
                ft.addToBackStack("CAMERA")
                ft.commit()
            })
        })
    }

    fun startGalleryWorkFlow(galleryParams: GalleryParams) {
        this.galleryParams = galleryParams
        context.startActivity(Intent(context, PluginActivity::class.java).apply {
            putExtra(PluginActivity.Constants.ACTIVITY_CREATED_CALLBACK_KEY, appContext.registerActivityCreatedCallback {
                pluginActivity = it as PluginActivity
                startGalleryActivity()
            })
        })
    }

    private fun removePluginActivity() {
        pluginActivity.finish()
    }

    private fun startGalleryActivity() {
        galleryActivityResultLauncher = if (galleryParams.allowMultiple) {
            pluginActivity.registerForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
            ) { mutableList: List<Uri> ->
                if (mutableList.isNotEmpty()) {
                    if (editorParams != null) {
                        val imagePropArray = arrayListOf<EditFragment.ImageProp>()
                        mutableList.forEach { uri ->
                            if (uri.path != null) {
                                imagePropArray.add(EditFragment.ImageProp(uri.path!!, uri, null, null, null))
                            } else {
                                nativeCallback.reject("Error in fetching URIs of some files", ErrorCodes.GALLERY_FILE_URI)
                                return@registerForActivityResult
                            }
                        }
                        showEditScreen(imagePropArray, editorParams)
                    } else {
                        val filePathArray = arrayListOf<String>()
                        mutableList.forEach { uri ->
                            if (uri.path != null) {
                                filePathArray.add(uri.path!!)
                            } else {
                                nativeCallback.reject("Error in fetching URIs of some files", ErrorCodes.GALLERY_FILE_URI)
                                return@registerForActivityResult
                            }
                        }
                        nativeCallback.resolve(copyImageFiles(imageFileParams.directoryToCopy, mutableList.toTypedArray()), null)
                    }
                } else {
                    removePluginActivity()
                    nativeCallback.resolve(arrayListOf(), null)
                }
            }
        } else {
            pluginActivity.registerForActivityResult(
                    ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    if (uri.path != null) {
                        if (editorParams != null) {
                            val imagePropArray = arrayListOf(
                                    EditFragment.ImageProp(uri.path!!, uri, null, null, null)
                            )
                            showEditScreen(imagePropArray, editorParams)
                        } else {
                            nativeCallback.resolve(copyImageFiles(imageFileParams.directoryToCopy, arrayOf(uri)), null)
                        }
                    } else {
                        nativeCallback.reject("Error in fetching URI of the file", ErrorCodes.GALLERY_FILE_URI)
                        return@registerForActivityResult
                    }
                } else {
                    removePluginActivity()
                    nativeCallback.resolve(arrayListOf(), null)
                }
            }
        }
        galleryActivityResultLauncher.launch("image/*")
    }

    private fun copyImageFiles(toDirectory: String, fileUriArrayList: Array<Uri>, compressFile: Boolean = false): ArrayList<String> {
        val directoryFile = File(toDirectory)
        if (!directoryFile.exists())
            directoryFile.mkdirs()
        val copiedFilesPath = arrayListOf<String>()
        fileUriArrayList.forEach { uri ->
            val fileName = uri.path!!.substring(uri.path!!.lastIndexOf('/') + 1)
            copiedFilesPath.add("$toDirectory/$fileName")
            if (!directoryFile.resolve(fileName).exists())
                context.contentResolver.openInputStream(uri)?.copyTo(FileOutputStream("$toDirectory/$fileName"))
        }
        return copiedFilesPath
    }

    private fun createOutputFileOptions(directoryPath: String?, fileName: String, saveAsPublic: Boolean, saveToGallery: Boolean): ImageCapture.OutputFileOptions {
        return if (saveAsPublic) {
            ImageCapture.OutputFileOptions.Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        directoryPath?.let {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, it)
                        }
                    }
            ).build()
        } else {
            val targetFile = getFileInPicturesDirectory(directoryPath, fileName)
            cameraParams.targetFile = targetFile
            if (saveToGallery) {
                MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.path),
                        arrayOf("image/${fileName.substring(fileName.lastIndexOf('.') + 1)}")
                ) { _, _ ->
                    Log.d("ImagePicker", "scanned file to Media")
                }
            }
            ImageCapture.OutputFileOptions.Builder(targetFile).build()
        }
    }

    private fun getFileInPicturesDirectory(directoryPath: String?, fileName: String): File {
        val splitFileName = fileName.split('.')
        val picturesDirectory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val directory =
                if (directoryPath == null)
                    picturesDirectory
                else
                    File(picturesDirectory, directoryPath).also {
                        if (!it.isDirectory) it.mkdirs()
                    }
        return File.createTempFile(
                splitFileName[0],
                ".${splitFileName[1]}",
                directory,
        )
    }

    private fun showEditScreen(fileArray: ArrayList<EditFragment.ImageProp>, editorParams: EditorParams) {
        val editFragment = EditFragment.newInstance(fileArray, editorParams)
        editFragment.backPressedListener = { editFlow ->
            pluginActivity.supportFragmentManager.popBackStack()
            if (editFlow == EditorParams.EditFlow.FROM_GALLERY) {
                galleryActivityResultLauncher.launch("image/*")
            }
        }
        editFragment.onEditCompleteListener = { uriArray, captionArray ->
            removePluginActivity()
            val copiedFilesPathArray = copyImageFiles(imageFileParams.directoryToCopy, uriArray.toTypedArray())
            nativeCallback.resolve(copiedFilesPathArray, captionArray)
        }
        editFragment.imageFileParams = imageFileParams
        val ft = pluginActivity.supportFragmentManager.beginTransaction()
        ft.add(android.R.id.content, editFragment, "EDIT_PHOTOS")
        ft.addToBackStack("EDIT_PHOTOS")
        ft.commitAllowingStateLoss()
    }
}