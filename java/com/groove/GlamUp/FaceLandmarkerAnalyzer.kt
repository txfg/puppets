package com.groove.GlamUp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.text.format

interface FaceLandmarkerListener {
    fun onError(error: String, errorCode: Int = 0)

    fun onResults(resultBundle: ResultBundle) // CORRECTED: No "FaceLandmarkerHelper."

    data class ResultBundle(
        val results: List<List<NormalizedLandmark>>,
        val imageWidth: Int,
        val imageHeight: Int,
        val imageRotation: Int
    )
}


class FaceLandmarkerAnalyzer(
    private val context: Context,
    private var listener: FaceLandmarkerListener? // Listener for results
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceLandmarkerAnalyzer"
    }

    private var faceLandmarker: FaceLandmarker? = null
    // You might not need a separate imageFaceLandmarker if this class only handles live stream
    // For simplicity, I'll keep the logic focused on the live stream (faceLandmarker)

    init {
        setupFaceLandmarker()
    }

    private fun setupFaceLandmarker() {
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")

            val baseOptions = baseOptionsBuilder.build()

            val optionsBuilder = FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM) // Essential for live feed
                .setResultListener { result: FaceLandmarkerResult, inputImage: MPImage ->
                    if (result.faceLandmarks().isNotEmpty()) {
                        val resultBundle = FaceLandmarkerListener.ResultBundle( // Or just ResultBundle(...) if it's a top-level import
                            results = result.faceLandmarks(),
                            imageWidth = inputImage.width,
                            imageHeight = inputImage.height,
                            imageRotation = 0 // Or actual rotation if available and needed
                        )
                        listener?.onResults(resultBundle)
                    } else {
                        val resultBundle = FaceLandmarkerListener.ResultBundle( // Or just ResultBundle(...)
                            results = emptyList(),
                            imageWidth = inputImage.width,
                            imageHeight = inputImage.height,
                            imageRotation = 0
                        )
                        listener?.onResults(resultBundle)
                    }
                }
// ...
                .setErrorListener { error: RuntimeException ->
                    Log.e(TAG, "MediaPipe FaceLandmarker Error: ${error.localizedMessage}")
                    listener?.onError(error.localizedMessage ?: "Unknown MediaPipe error")
                }

            val options = optionsBuilder.build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)

        } catch (e: Exception) {
            val errorMessage = "Failed to initialize FaceLandmarker: ${e.message}"
            Log.e(TAG, errorMessage, e)
            listener?.onError(errorMessage)
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (faceLandmarker == null) {
            Log.w(TAG, "FaceLandmarker not initialized yet.")
            imageProxy.close() // Close the imageProxy to prevent CameraX from stalling
            return
        }

        // Convert ImageProxy to Bitmap. This is crucial.
        // The bitmap passed to MediaPipe should be upright.
        // ImageProxy's rotationDegrees tells us how much to rotate the buffer to make it upright.
        val bitmap = imageProxyToBitmap(imageProxy) // This should handle YUV to RGB

        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap.")
            imageProxy.close()
            return
        }

        // The imageProxy's rotationDegrees indicates the rotation needed to make the image upright.
        // We need to rotate the bitmap BEFORE sending it to MediaPipe if it's not already upright.
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())


        // Convert Bitmap to MPImage
        val mpImage: MPImage = BitmapImageBuilder(rotatedBitmap).build()
        val timestamp = imageProxy.imageInfo.timestamp // Or System.currentTimeMillis()

        // Perform face landmark detection
        try {
            faceLandmarker?.detectAsync(mpImage, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error during face detection: ${e.message}", e)
            listener?.onError("Error during face detection: ${e.message}")
        } finally {
            // IMPORTANT: Close the ImageProxy to release the buffer for the next frame
            imageProxy.close()
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap // No rotation needed
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Converts an ImageProxy (typically in YUV_420_888 format from CameraX) to an RGB Bitmap.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unsupported image format: ${imageProxy.format}. Expected YUV_420_888.")
            // Optionally, try to handle other formats if needed, but YUV_420_888 is standard for ImageAnalysis
            return null
        }

        val yBuffer: ByteBuffer = imageProxy.planes[0].buffer
        val uBuffer: ByteBuffer = imageProxy.planes[1].buffer
        val vBuffer: ByteBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // NV21 order is Y plane, then V plane, then U plane (interleaved VU)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize) // V plane
        uBuffer.get(nv21, ySize + vSize, uSize) // U plane

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21, // YuvImage expects NV21 by default for this constructor
            imageProxy.width, imageProxy.height,
            null // strides can be null if planes are tightly packed
        )

        val out = ByteArrayOutputStream()
        // The Rect should cover the entire image
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height), // Use android.graphics.Rect
            100, // Quality: 0-100
            out
        )

        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    // Call this method to release resources, e.g., from MainActivity's onDestroy
    fun clear() {
        faceLandmarker?.close()
        faceLandmarker = null
        listener = null // Clear the listener to prevent memory leaks
        Log.d(TAG, "FaceLandmarker resources cleared.")
    }
}
