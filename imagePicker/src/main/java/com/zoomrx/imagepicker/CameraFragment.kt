package com.zoomrx.camera

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.AnimatedVectorDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.yalantis.ucrop.util.ImageHeaderParser.UNKNOWN_ORIENTATION
import com.zoomrx.camera.camera.R
import com.zoomrx.camera.modal.CameraParams
import java.util.concurrent.ExecutorService

class CameraFragment: Fragment(R.layout.camera_layout) {
    private var imageCapture: ImageCapture = ImageCapture.Builder().build()
    var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    lateinit var preview: Preview
    lateinit var cameraProvider: ProcessCameraProvider
    lateinit var callBack: (Uri?, Int?, String?) -> Unit
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    lateinit var cameraParams: CameraParams
    lateinit var outputFileOptions: ImageCapture.OutputFileOptions
    var viewsToBeRotated = arrayListOf<View>()
    var previousOrientation = 0

    object ErrorCodes {
        const val NOT_INITIALIZED = 101
        const val CONTEXT_NOT_FOUND = 102
        const val CAPTURE_FAILED = 103
        const val BACK_PRESSED = 104
        const val PERMISSION_NOT_GRANTED = 105
    }

    private val orientationEventListener by lazy {
        object : OrientationEventListener(activity) {
            var previousRotation = -1
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == UNKNOWN_ORIENTATION) {
                    return
                }

                val rotation = when (orientation) {
                    in 50 until 130 -> {
                        Surface.ROTATION_270
                    }
                    in 140 until 220 -> {
                        Surface.ROTATION_180
                    }
                    in 230 until 310 -> {
                        Surface.ROTATION_90
                    }
                    in 320 until 359 -> {
                        Surface.ROTATION_0
                    }
                    in 0 until 40 -> {
                        Surface.ROTATION_0
                    }
                    else -> {
                        return
                    }
                }

                if (rotation != previousRotation && rotation != -1) {
                    previousRotation = rotation
                    imageCapture.targetRotation = rotation
                    rotateViewsOnOrientationChange(rotation)
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (view?.rootView?.display?.displayId == displayId) {
                val rotation = view!!.rootView.display.rotation
                imageCapture.targetRotation = rotation
            }
        }

        override fun onDisplayAdded(displayId: Int) {
        }

        override fun onDisplayRemoved(displayId: Int) {
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val activity = context as FragmentActivity
        activity.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val supportFragmentManager = context.supportFragmentManager
                if (isVisible) {
                    callBack(null, ErrorCodes.BACK_PRESSED, null)
                }
            }
        })
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                callBack(null, ErrorCodes.PERMISSION_NOT_GRANTED, "Camera permission not granted")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        previousOrientation = requireActivity().requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        orientationEventListener.enable()
        val displayManager = activity?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Preview
        previewView = view.findViewById(R.id.viewFinder)
        preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
        imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
        if (isPermissionGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSION)
        }

        // Set up the listener for take photo button
        val cameraButton = view.findViewById<ImageButton>(R.id.captureButton)
        cameraButton.setOnClickListener {
            ((it as ImageButton).drawable as AnimatedVectorDrawable).start()
            takePhoto()
        }

        handleFlashButton(view.findViewById(R.id.flashButton))

        handleFlipButton(view.findViewById(R.id.changeCamera))

    }

    private fun rotateViewsOnOrientationChange(rotation: Int) {
        viewsToBeRotated.forEach {
            if (it.rotation != rotation * 90F) {
                var toDegree = rotation * 90F
                if (toDegree - it.rotation > 90) {
                    toDegree -= 360F
                }
                it.animate().rotation(toDegree).start()
            }
        }
    }

    private fun handleFlashButton(flashButton: ImageButton) {
        viewsToBeRotated.add(flashButton)
        flashButton.setOnClickListener {
            if (imageCapture.flashMode == ImageCapture.FLASH_MODE_OFF) {
                imageCapture.flashMode = ImageCapture.FLASH_MODE_AUTO
                flashButton.setImageResource(R.drawable.ic_baseline_flash_auto_36)
            } else if (imageCapture.flashMode == ImageCapture.FLASH_MODE_AUTO) {
                imageCapture.flashMode = ImageCapture.FLASH_MODE_ON
                flashButton.setImageResource(R.drawable.ic_baseline_flash_on_36)
            } else {
                imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
                flashButton.setImageResource(R.drawable.ic_baseline_flash_off_36)
            }
        }
    }

    private fun handleFlipButton(flipCameraButton: ImageButton) {
        viewsToBeRotated.add(flipCameraButton)
        flipCameraButton.setOnClickListener {
            val flipAnimation = ObjectAnimator.ofFloat(flipCameraButton, "rotationY", (flipCameraButton.rotationY + 180F) % 360F)
            flipAnimation.start()
            if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            }
            changeCamera(cameraSelector)
        }
    }

    private fun takePhoto() {


        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
                outputFileOptions, ContextCompat.getMainExecutor(activity), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                callBack(null, ErrorCodes.CAPTURE_FAILED, "Photo capture failed: ${exc.message}")
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                callBack(output.savedUri ?: cameraParams.targetFile?.toUri(), null, null)
            }
        })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireActivity())

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            try {
                // Select back camera as a default
                changeCamera(cameraSelector)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(activity))
    }

    private fun changeCamera(cameraSelector: CameraSelector) {
        // Unbind use cases before rebinding
        cameraProvider.unbindAll()

        // Bind use cases to camera
        cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture)
    }

    private fun isPermissionGranted() = ContextCompat.checkSelfPermission(requireContext(), REQUIRED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun onStop() {
        super.onStop()
        activity?.requestedOrientation = previousOrientation
        orientationEventListener.disable()
        val displayManager = activity?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val REQUIRED_PERMISSION = Manifest.permission.CAMERA
    }
}