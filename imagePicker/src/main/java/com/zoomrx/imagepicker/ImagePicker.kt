package com.zoomrx.imagepicker

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import com.zoomrx.imagepicker.modal.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ImagePicker(
        private val context: Context,
        private val imageFileParams: ImageFileParams,
        private val editorParams: EditorParams?,
        private val nativeCallback: NativeCallbackInterface
) {
    object PhotoSource {
        const val CAMERA = 0
        const val GALLERY = 1
    }

    object ErrorCodes {
        const val INSUFFICIENT_DATA = 1
        const val PERMISSION_NOT_GRANTED = 2
        const val CANCELLED = 3
        const val FILE_CREATION = 101
        const val URI_CREATION = 102
        const val GALLERY_FILE_URI = 103
    }

    private val appContext = context.applicationContext as CustomApplication
    private lateinit var imagePickerActivity: ImagePickerActivity
    private lateinit var galleryParams: GalleryParams
    private lateinit var cameraParams: CameraParams
    private lateinit var galleryActivityResultLauncher: ActivityResultLauncher<String>

    private fun isPermissionGranted(requiredPermission: String): Boolean =
            ContextCompat.checkSelfPermission(imagePickerActivity, requiredPermission) == PackageManager.PERMISSION_GRANTED

    private fun arePermissionsGranted(requiredPermissions: Array<String>): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(imagePickerActivity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun startCameraWorkFlow(cameraParams: CameraParams) {
        this.cameraParams = cameraParams
        val fileName = imageFileParams.fileNamePrefix + "-${System.currentTimeMillis()}.JPEG"
        val outputFileOptions = try {
            createOutputFileOptions(imageFileParams.relativeDirectory, fileName, cameraParams.saveToGallery)
        } catch (ex: IOException) {
            // Error occurred while creating the File
            nativeCallback.reject("Error in creating image file", ErrorCodes.FILE_CREATION)
            null
        }

        if (outputFileOptions == null) {
            nativeCallback.reject("Error getting URI for new image file", ErrorCodes.URI_CREATION)
            return
        }

        context.startActivity(Intent(context, ImagePickerActivity::class.java).apply {
            putExtra(ImagePickerActivity.Constants.ACTIVITY_CREATED_CALLBACK_KEY, appContext.registerActivityCreatedCallback { context ->
                imagePickerActivity = context as ImagePickerActivity
                val permissionGrantedCallback = {
                    imagePickerActivity.supportFragmentManager.addOnBackStackChangedListener {
                        if (imagePickerActivity.supportFragmentManager.backStackEntryCount == 0) {
                            imagePickerActivity.finish()
                        }
                    }
                    val cameraFragment = CameraFragment()
                    cameraFragment.cameraParams = cameraParams
                    cameraFragment.callBack = { savedUri, code, message ->
                        if (savedUri != null) {
                            editorParams?.let {
                                showEditScreen(
                                        arrayListOf(
                                                ImageProp(
                                                        savedUri.path!!,
                                                        savedUri,
                                                        null,
                                                        null,
                                                        null)
                                        ),
                                        it
                                )
                            } ?: run {
                                imagePickerActivity.finish()
                                nativeCallback.resolve(copyImageFiles(imageFileParams.directoryToCopy, arrayOf(savedUri)), null)
                            }
                        } else {
                            cameraParams.targetFile?.deleteOnExit()
                            if (code == CameraFragment.ErrorCodes.BACK_PRESSED) {
                                imagePickerActivity.finish()
                                nativeCallback.reject("User cancelled", ErrorCodes.CANCELLED)
                            } else {
                                nativeCallback.reject(message!!, code!!)
                            }
                        }
                    }
                    cameraFragment.outputFileOptions = outputFileOptions
                    val ft = imagePickerActivity.supportFragmentManager.beginTransaction()
                    ft.add(android.R.id.content, cameraFragment, "CAMERA")
                    ft.addToBackStack("CAMERA")
                    ft.commit()
                }
                val permissionDeniedCallback = {
                    nativeCallback.reject("Permission(s) not granted", ErrorCodes.PERMISSION_NOT_GRANTED)
                    imagePickerActivity.finish()
                }
                val handlePermissionResult = { granted: Boolean ->
                    if (granted)
                        permissionGrantedCallback()
                    else
                        permissionDeniedCallback()
                }
                val requiredPermissions = arrayListOf(Manifest.permission.CAMERA)
                if (cameraParams.saveToGallery && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    if (arePermissionsGranted(requiredPermissions.toTypedArray())) {
                        permissionGrantedCallback()
                    } else {
                        imagePickerActivity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                            handlePermissionResult(!it.containsValue(false))
                        }.launch(requiredPermissions.toTypedArray())
                    }
                } else {
                    if (isPermissionGranted(requiredPermissions[0])) {
                        permissionGrantedCallback()
                    } else {
                        imagePickerActivity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                            handlePermissionResult(it)
                        }.launch(requiredPermissions[0])
                    }
                }
            })
        })
    }

    fun startGalleryWorkFlow(galleryParams: GalleryParams) {
        this.galleryParams = galleryParams
        context.startActivity(Intent(context, ImagePickerActivity::class.java).apply {
            putExtra(ImagePickerActivity.Constants.ACTIVITY_CREATED_CALLBACK_KEY, appContext.registerActivityCreatedCallback {
                imagePickerActivity = it as ImagePickerActivity
                val requiredPermission = Manifest.permission.READ_EXTERNAL_STORAGE
                if (isPermissionGranted(requiredPermission)) {
                    startGalleryActivity()
                } else {
                    imagePickerActivity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                        if (isGranted) {
                            startGalleryActivity()
                        } else {
                            nativeCallback.reject("Permission(s) not granted", ErrorCodes.PERMISSION_NOT_GRANTED)
                            imagePickerActivity.finish()
                        }
                    }.launch(requiredPermission)
                }
            })
        })
    }

    private fun startGalleryActivity() {
        galleryActivityResultLauncher = if (galleryParams.allowMultiple) {
            imagePickerActivity.registerForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
            ) { mutableList: List<Uri> ->
                if (mutableList.isNotEmpty()) {
                    if (editorParams != null) {
                        val imagePropArray = arrayListOf<ImageProp>()
                        mutableList.forEach { uri ->
                            if (uri.path != null) {
                                imagePropArray.add(ImageProp(uri.path!!, uri, null, null, null))
                            } else {
                                nativeCallback.reject("Error in fetching URIs of some files",
                                    ErrorCodes.GALLERY_FILE_URI
                                )
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
                                nativeCallback.reject("Error in fetching URIs of some files",
                                    ErrorCodes.GALLERY_FILE_URI
                                )
                                return@registerForActivityResult
                            }
                        }
                        nativeCallback.resolve(copyImageFiles(imageFileParams.directoryToCopy, mutableList.toTypedArray()), null)
                    }
                } else {
                    imagePickerActivity.finish()
                    nativeCallback.reject("User cancelled", ErrorCodes.CANCELLED)
                }
            }
        } else {
            imagePickerActivity.registerForActivityResult(
                    ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    if (uri.path != null) {
                        if (editorParams != null) {
                            val imagePropArray = arrayListOf(
                                    ImageProp(uri.path!!, uri, null, null, null)
                            )
                            showEditScreen(imagePropArray, editorParams)
                        } else {
                            nativeCallback.resolve(copyImageFiles(imageFileParams.directoryToCopy, arrayOf(uri)), null)
                        }
                    } else {
                        nativeCallback.reject("Error in fetching URI of the file",
                            ErrorCodes.GALLERY_FILE_URI
                        )
                        return@registerForActivityResult
                    }
                } else {
                    imagePickerActivity.finish()
                    nativeCallback.reject("User cancelled", ErrorCodes.CANCELLED)
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
            copiedFilesPath.add(toDirectory + fileName)
            if (!directoryFile.resolve(fileName).exists())
                context.contentResolver.openInputStream(uri)?.copyTo(FileOutputStream("$toDirectory/$fileName"))
        }
        return copiedFilesPath
    }

    private fun createOutputFileOptions(directoryPath: String?, fileName: String, saveToGallery: Boolean): ImageCapture.OutputFileOptions {
        return if (saveToGallery) {
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
            /*if (saveToGallery) {
                MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.path),
                        arrayOf("image/${fileName.substring(fileName.lastIndexOf('.') + 1)}")
                ) { _, _ ->
                    Log.d("ImagePicker", "scanned file to Media")
                }
            }*/
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

    private fun showEditScreen(fileArray: ArrayList<ImageProp>, editorParams: EditorParams) {
        val editorFragment = EditorFragment.newInstance(fileArray, editorParams)
        editorFragment.backPressedListener = { editFlow ->
            imagePickerActivity.supportFragmentManager.popBackStack()
            if (editFlow == EditorParams.EditFlow.FROM_GALLERY) {
                galleryActivityResultLauncher.launch("image/*")
            }
        }
        editorFragment.onEditCompleteListener = { uriArray, captionArray ->
            imagePickerActivity.finish()
            val copiedFilesPathArray = copyImageFiles(imageFileParams.directoryToCopy, uriArray.toTypedArray())
            nativeCallback.resolve(copiedFilesPathArray, captionArray)
        }
        editorFragment.imageFileParams = imageFileParams
        val ft = imagePickerActivity.supportFragmentManager.beginTransaction()
        ft.add(android.R.id.content, editorFragment, "EDIT_PHOTOS")
        ft.addToBackStack("EDIT_PHOTOS")
        ft.commitAllowingStateLoss()
    }
}