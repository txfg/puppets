package com.groove.GlamUp // Ensure this matches your package

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext // Keep this for AndroidView factory
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker // For FaceOverlay constants
import com.groove.GlamUp.ui.theme.GlamUPTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.activity.compose.rememberLauncherForActivityResult


class MainActivity : ComponentActivity(), FaceLandmarkerListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var actualPreviewUseCase: Preview? = null // Renamed to avoid confusion with StateFlow
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // For Compose to observe the Preview use case
    private val previewUseCaseFlow = MutableStateFlow<Preview?>(null)
    private var isFrontCameraSelected = true // Default or set based on your initial camera choice

    private val faceLandmarksState = mutableStateOf<List<List<NormalizedLandmark>>>(emptyList())
    private val imageWidthState = mutableStateOf(0)
    private val imageHeightState = mutableStateOf(0)

    private var faceLandmarkerAnalyzer: FaceLandmarkerAnalyzer? = null

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value) permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(this, "Permission request denied", Toast.LENGTH_LONG).show()
            } else {
                // Permissions granted, now set content which will trigger camera setup if needed
                setContentWithCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            setContentWithCamera() // This will also call startCamera if previewUseCaseFlow.value is null
        } else {
            requestPermissions()
        }
    }

    private fun setContentWithCamera() {
        setContent {
            GlamUPTheme {
                val currentPreviewUseCase by previewUseCaseFlow.collectAsState()

                if (currentPreviewUseCase != null) {
                    CameraViewWithOverlay(
                        previewUseCase = currentPreviewUseCase!!,
                        onTakePhotoClick = { takePhoto() },
                        faceLandmarks = faceLandmarksState.value,
                        imageWidth = imageWidthState.value,
                        imageHeight = imageHeightState.value,
                        isFrontCamera = isFrontCameraSelected // Pass the boolean flag
                    )
                } else {
                    // Show a loading indicator or placeholder
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Initializing Camera...")
                    }
                    // Attempt to start camera if not already trying and permissions are granted
                    // This ensures startCamera is called if it hasn't been, e.g. on first launch after permission grant
                    if (cameraProvider == null && allPermissionsGranted()) {
                        startCamera()
                    }
                }
            }
        }
        // If not already setting up and preview is null, try to start camera.
        // This handles the case where setContent is called but startCamera hasn't completed.
        if (previewUseCaseFlow.value == null && cameraProvider == null && allPermissionsGranted()) {
            startCamera()
        }
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Preview Use Case
            val newPreview = Preview.Builder().build()
            this.actualPreviewUseCase = newPreview // Store for CameraX binding
            previewUseCaseFlow.value = newPreview // Update the StateFlow for Compose

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            faceLandmarkerAnalyzer = FaceLandmarkerAnalyzer(this, this@MainActivity)

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, faceLandmarkerAnalyzer!!)
                }

            // --- Camera Selector Logic ---
            // You can make 'initialCameraIsFront' a class member or a parameter
            val initialCameraIsFront = true // Example: default to front camera
            isFrontCameraSelected = initialCameraIsFront // Update the state
            val cameraSelector = if (isFrontCameraSelected) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            // --- End Camera Selector Logic ---

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    actualPreviewUseCase, // Use the stored instance for binding
                    imageCapture,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Could not start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread {
            Toast.makeText(this, "Analyzer Error: $error", Toast.LENGTH_LONG).show()
            Log.e(TAG, "FaceLandmarkerAnalyzer Error: $error (Code: $errorCode)")
        }
    }

    override fun onResults(resultBundle: FaceLandmarkerListener.ResultBundle) {
        runOnUiThread {
            faceLandmarksState.value = resultBundle.results
            imageWidthState.value = resultBundle.imageWidth
            imageHeightState.value = resultBundle.imageHeight
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GlamUp-Photos")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(baseContext, "Photo capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceLandmarkerAnalyzer?.clear()
        cameraProvider?.unbindAll() // Good practice to unbind here too
    }
}

// Ensure CameraViewWithOverlay and FaceOverlay are defined as discussed previously,
// accepting 'isFrontCamera' and using it.

@Composable
fun CameraViewWithOverlay(
    previewUseCase: Preview,
    onTakePhotoClick: () -> Unit,
    faceLandmarks: List<List<NormalizedLandmark>>,
    imageWidth: Int,
    imageHeight: Int,
    isFrontCamera: Boolean // Added parameter
) {
    var pViewWidth by remember { mutableStateOf(0) }
    var pViewHeight by remember { mutableStateOf(0) }
    // val context = LocalContext.current // Not strictly needed if previewUseCase is passed

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewUseCase.setSurfaceProvider(this.surfaceProvider)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    pViewWidth = size.width
                    pViewHeight = size.height
                }
        )

        if (imageWidth > 0 && imageHeight > 0 && pViewWidth > 0 && pViewHeight > 0) {
            FaceOverlay(
                landmarks = faceLandmarks,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                previewViewWidth = pViewWidth,
                previewViewHeight = pViewHeight,
                isFrontCamera = isFrontCamera // Pass it down
            )
        }

        Button(
            onClick = onTakePhotoClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
        ) {
            Text("Take Photo")
        }
    }
}

@Composable
fun FaceOverlay(
    landmarks: List<List<NormalizedLandmark>>,
    imageWidth: Int,
    imageHeight: Int,
    previewViewWidth: Int,
    previewViewHeight: Int,
    isFrontCamera: Boolean // Added parameter
) {
    if (landmarks.isEmpty() || imageWidth <= 0 || imageHeight <= 0 || previewViewWidth <= 0 || previewViewHeight <= 0) {
        return
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val imageAspectRatio = imageWidth.toFloat() / imageHeight
        val viewAspectRatio = previewViewWidth.toFloat() / previewViewHeight
        val scaleFactor: Float
        var offsetX = 0f
        var offsetY = 0f

        if (imageAspectRatio > viewAspectRatio) {
            scaleFactor = previewViewHeight.toFloat() / imageHeight
            val scaledImageWidth = imageWidth * scaleFactor
            offsetX = (previewViewWidth - scaledImageWidth) / 2f
        } else {
            scaleFactor = previewViewWidth.toFloat() / imageWidth
            val scaledImageHeight = imageHeight * scaleFactor
            offsetY = (previewViewHeight - scaledImageHeight) / 2f
        }

        landmarks.forEach { singleFaceLandmarks ->
            if (singleFaceLandmarks.isEmpty()) return@forEach

            singleFaceLandmarks.forEach { landmark ->
                var lx = landmark.x() * imageWidth
                var ly = landmark.y() * imageHeight
                lx *= scaleFactor
                ly *= scaleFactor
                lx += offsetX
                ly += offsetY
                if (isFrontCamera) {
                    lx = previewViewWidth - lx
                }
                if (lx >= 0 && lx <= previewViewWidth && ly >= 0 && ly <= previewViewHeight) {
                    drawCircle(color = Color.Red, radius = 5.0f, center = Offset(lx, ly))
                }
            }

            val regionsToDraw = listOf(
                FaceLandmarker.FACE_LANDMARKS_LIPS to Color.Magenta,
                FaceLandmarker.FACE_LANDMARKS_LEFT_EYE to Color.Cyan,
                FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE to Color.Cyan,
                FaceLandmarker.FACE_LANDMARKS_FACE_OVAL to Color.Yellow
            )

            regionsToDraw.forEach { (connections, color) ->
                connections.forEach { connection ->
                    if (connection.start() < singleFaceLandmarks.size && connection.end() < singleFaceLandmarks.size) {
                        val startLandmark = singleFaceLandmarks[connection.start()]
                        val endLandmark = singleFaceLandmarks[connection.end()]
                        var startX = startLandmark.x() * imageWidth
                        var startY = startLandmark.y() * imageHeight
                        var endX = endLandmark.x() * imageWidth
                        var endY = endLandmark.y() * imageHeight

                        startX *= scaleFactor; startY *= scaleFactor
                        endX *= scaleFactor; endY *= scaleFactor
                        startX += offsetX; startY += offsetY
                        endX += offsetX; endY += offsetY

                        if (isFrontCamera) {
                            startX = previewViewWidth - startX
                            endX = previewViewWidth - endX
                        }
                        drawLine(color = color, start = Offset(startX, startY), end = Offset(endX, endY), strokeWidth = 2.0f)
                    }
                }
            }
        }
    }
}
