package com.example.ui

import android.Manifest
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

fun fixSpelling(text: String): String {
    val corrections = mapOf(
        "suga" to "sugar",
        "sal" to "salt",
        "suar" to "sugar",
        "slat" to "salt",
        "colur" to "color",
        "flavour" to "flavor",
        "favour" to "flavor",
        "flvour" to "flavor",
        "acis" to "acid",
        "srup" to "syrup"
    )
    var result = text
    corrections.forEach { (wrong, right) ->
        val regex = "\\b$wrong\\b".toRegex(RegexOption.IGNORE_CASE)
        result = result.replace(regex, right)
    }
    return result
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScannerScreen(
    onTextExtracted: (String) -> Unit,
    onClose: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        CameraCaptureWithAnalysis(onTextExtracted, onClose)
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Camera permission is required to scan ingredients.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Grant Permission")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onClose) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun CameraCaptureWithAnalysis(
    onTextExtracted: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    var scannedText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val imageCapture = remember { ImageCapture.Builder().build() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraScanner", "Use case binding failed", exc)
                    }

                }, ContextCompat.getMainExecutor(context))

                previewView
            }
        )

        // Overlay UI
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            // Bottom panel with text
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp).navigationBarsPadding(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Point at Ingredients List", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (isProcessing) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Extracting text...", color = MaterialTheme.colorScheme.onSurface)
                    } else if (scannedText.isNotBlank()) {
                        Text(
                            text = scannedText,
                            maxLines = 6,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                onTextExtracted(scannedText)
                                onClose()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use Scanned Text")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { scannedText = "" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Retake")
                        }
                    } else {
                        Button(
                            onClick = {
                                isProcessing = true
                                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null) {
                                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                            recognizer.process(image)
                                                .addOnSuccessListener { visionText ->
                                                    scannedText = fixSpelling(visionText.text)
                                                    isProcessing = false
                                                    imageProxy.close()
                                                }
                                                .addOnFailureListener { e ->
                                                    Log.e("CameraScanner", "Text recognition failed", e)
                                                    scannedText = "Failed to extract text."
                                                    isProcessing = false
                                                    imageProxy.close()
                                                }
                                        } else {
                                            isProcessing = false
                                            imageProxy.close()
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        Log.e("CameraScanner", "Photo capture failed", exception)
                                        isProcessing = false
                                    }
                                })
                            },
                            modifier = Modifier.size(72.dp),
                            shape = androidx.compose.foundation.shape.CircleShape
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = "Capture Image", modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
}
