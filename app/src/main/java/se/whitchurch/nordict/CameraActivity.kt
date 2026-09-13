package se.whitchurch.nordict

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Heavily based on https://developer.android.com/codelabs/camerax-getting-started
class CameraActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cropImage = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val croppedImageFilePath = result.getUriFilePath(this.applicationContext)
                val base64 = croppedImageFilePath?.let {
                    Base64.encodeToString(
                        File(it).readBytes(),
                        Base64.DEFAULT
                    )
                } ?: return@registerForActivityResult
                Ordboken.getInstance(this).images = arrayListOf("data:image/jpeg;base64,$base64")
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                val exception = result.error
                Log.e("CameraActivity", "Crop failed", exception)
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CameraScreen(
                        onPreviewReady = { pv ->
                            imageCapture?.let { return@let }
                            if (!hasPermission()) return@CameraScreen
                            startCameraOn(pv)
                        },
                        onTakePhoto = { pv, ic ->
                            val file = File(cacheDir, "photo.jpg")
                            ic.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(this),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onError(exc: ImageCaptureException) {
                                        Log.e("Foo", "Photo capture failed: ${exc.message}", exc)
                                    }

                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        cropImage.launch(
                                            CropImageContractOptions(
                                                uri = Uri.parse(file.toURI().toString()),
                                                cropImageOptions = CropImageOptions(
                                                    guidelines = CropImageView.Guidelines.ON,
                                                    outputCompressFormat = Bitmap.CompressFormat.PNG
                                                )
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }

        if (!hasPermission()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    @Composable
    fun CameraScreen(
        onPreviewReady: (PreviewView) -> Unit,
        onTakePhoto: (PreviewView, ImageCapture) -> Unit
    ) {
        val previewView = remember { mutableStateOf<PreviewView?>(null) }

        LaunchedEffect(previewView.value) {
            previewView.value?.let { onPreviewReady(it) }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        cameraPreviewView = this
                        post { previewView.value = this }
                    }
                }
            )

            FloatingActionButton(
                onClick = {
                    val pv = previewView.value ?: return@FloatingActionButton
                    val ic = imageCapture ?: return@FloatingActionButton
                    onTakePhoto(pv, ic)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 50.dp)
                    .clip(CircleShape)
                    .size(100.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Take Photo")
            }
        }
    }

    private fun hasPermission(): Boolean = allPermissionsGranted()

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCameraOn(pv: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetResolution(Size(480, 640))
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                cameraPreviewView?.let { startCameraOn(it) }
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private var cameraPreviewView: PreviewView? = null

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}