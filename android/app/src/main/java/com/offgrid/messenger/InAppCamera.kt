package com.offgrid.messenger

import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.os.SystemClock
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX capture lives inside OffGrid; no other camera app, gallery write or cloud needed.
 * Captures are temporary until compressed/stripped by PhotoPreparer and AES-GCM encrypted.
 */
@Composable
fun InAppCameraScreen(onCancel:()->Unit,onSend:(File)->Unit) {
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    val mainExecutor=remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor=remember { Executors.newSingleThreadExecutor() }
    val previewView=remember(context) {
        PreviewView(context).apply {
            implementationMode=PreviewView.ImplementationMode.COMPATIBLE
            scaleType=PreviewView.ScaleType.FIT_CENTER
        }
    }
    var lens by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var captured by remember { mutableStateOf<File?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastCapture by remember { mutableLongStateOf(0L) }
    val submitted=remember { AtomicBoolean(false) }
    val screenActive=remember { AtomicBoolean(true) }
    val currentFile by rememberUpdatedState(captured)
    val radius=RoundedCornerShape(24.dp)
    fun discard() {
        captured?.delete()
        captured=null;previewImage=null;busy=false;error=null
    }
    fun close() {discard();onCancel()}
    BackHandler { close() }

    // The provider is bound only while the live preview is visible. Capturing then
    // reviewing releases the camera; leaving the screen closes all use cases.
    DisposableEffect(lifecycleOwner,lens,captured) {
        var active=true
        var boundProvider:ProcessCameraProvider?=null
        var boundPreview:Preview?=null
        var boundCapture:ImageCapture?=null
        if(captured==null) {
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({
                if(!active)return@addListener
                runCatching {
                    val provider=future.get()
                    val selector=CameraSelector.Builder().requireLensFacing(lens).build()
                    require(provider.hasCamera(selector)) { "Selected camera is not available" }
                    val preview=Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                    val capture=ImageCapture.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                        .build()
                    capture.flashMode=flash
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner,selector,preview,capture)
                    boundProvider=provider;boundPreview=preview;boundCapture=capture
                    imageCapture=capture
                }.onFailure {error="Camera unavailable: ${it.message}";imageCapture=null}
            },mainExecutor)
        }
        onDispose {
            active=false
            boundProvider?.let { provider ->
                listOfNotNull(boundPreview,boundCapture).takeIf{it.isNotEmpty()}?.let { cases ->
                    runCatching {provider.unbind(*cases.toTypedArray())}
                }
            }
            imageCapture=null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            screenActive.set(false)
            if(!submitted.get())currentFile?.delete()
            cameraExecutor.shutdown()
        }
    }
    LaunchedEffect(captured) {
        previewImage= if(captured==null)null else withContext(Dispatchers.IO) {
            runCatching {
                val file=captured ?: return@runCatching null
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.absolutePath,bounds)
                val options=BitmapFactory.Options().apply {
                    var sample=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600)sample*=2
                    inSampleSize=sample
                }
                var bitmap=BitmapFactory.decodeFile(file.absolutePath,options)
                    ?: return@runCatching null
                val orientation=ExifInterface(file.absolutePath)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
                val matrix=Matrix()
                when(orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                }
                if(!matrix.isIdentity) {
                    val rotated=android.graphics.Bitmap.createBitmap(bitmap,0,0,
                        bitmap.width,bitmap.height,matrix,true)
                    if(rotated!==bitmap)bitmap.recycle()
                    bitmap=rotated
                }
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }

    LaunchedEffect(flash,imageCapture) {imageCapture?.flashMode=flash}

    Box(Modifier.fillMaxSize().background(Color(0xFF070B15)).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Crossfade(targetState=captured!=null,animationSpec=tween(220),label="camera review") { reviewing ->
            if(reviewing) {
                val preview=previewImage
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                    if(preview!=null) Image(preview,"Captured photo preview",
                        Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                    else CircularProgressIndicator(color=Color.White)
                }
            } else {
                // Fit the *entire* 4:3 sensor preview between the camera controls.
                // CameraX's FILL_CENTER would otherwise crop front and rear cameras.
                BoxWithConstraints(Modifier.fillMaxSize()
                    .padding(top=68.dp,bottom=164.dp),contentAlignment=Alignment.Center) {
                    val ratio=if(maxWidth>maxHeight) 4f/3f else 3f/4f
                    val width=minOf(maxWidth,maxHeight*ratio)
                    val frame=Modifier.width(width).height(width/ratio)
                    AndroidView(factory={previewView},modifier=frame)
                    Box(frame.border(1.dp,Color.White.copy(alpha=.20f),radius))
                }
            }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter)
            .background(Color.Black.copy(alpha=.48f)).padding(horizontal=12.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=::close) {Icon(Icons.Default.ArrowBack,"Close camera",tint=Color.White)}
            Text(if(captured==null)"OffGrid Camera" else "Review photo",
                color=Color.White,style=MaterialTheme.typography.titleMedium,
                modifier=Modifier.weight(1f))
            if(captured==null) {
                IconButton(onClick={
                    flash=when(flash) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }) {
                    Icon(when(flash){
                        ImageCapture.FLASH_MODE_OFF -> Icons.Default.FlashOff
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        else -> Icons.Default.FlashOn
                    },"Change flash mode",tint=Color.White)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color.Black.copy(alpha=.60f))
            .padding(horizontal=22.dp,vertical=20.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            error?.let{ Text(it,color=Color(0xFFFFC8C5),style=MaterialTheme.typography.bodySmall) }
            if(captured==null) {
                Text("Private capture · compressed before encrypted sending",
                    color=Color.White.copy(alpha=.83f),style=MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.SpaceEvenly) {
                    Spacer(Modifier.size(48.dp))
                    Box(Modifier.size(76.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha=.22f)).padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(enabled=!busy && imageCapture!=null) {
                            val capture=imageCapture ?: return@clickable
                            capture.targetRotation=previewView.display?.rotation ?: Surface.ROTATION_0
                            if(SystemClock.elapsedRealtime()-lastCapture<700L)return@clickable
                            lastCapture=SystemClock.elapsedRealtime()
                            busy=true;error=null
                            val output=File.createTempFile("offgrid-camera-",".jpg",context.cacheDir)
                            val options=ImageCapture.OutputFileOptions.Builder(output).build()
                            capture.takePicture(options,cameraExecutor,object:ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                                    mainExecutor.execute {
                                        if(!screenActive.get())output.delete()
                                        else if(output.length()>0L)captured=output
                                        else {output.delete();error="Camera returned an empty photo"}
                                        busy=false
                                    }
                                }
                                override fun onError(exc:ImageCaptureException) {
                                    output.delete()
                                    mainExecutor.execute {if(screenActive.get()) {
                                        busy=false;error="Capture failed: ${exc.message}"
                                    }}
                                }
                            })
                        })
                    if(busy)CircularProgressIndicator(color=Color(0xFF0E397D),modifier=Modifier.size(22.dp))
                    IconButton(onClick={lens=if(lens==CameraSelector.LENS_FACING_BACK)
                        CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK},
                        enabled=!busy,modifier=Modifier.size(48.dp)) {
                        Icon(Icons.Default.Cameraswitch,"Switch front or rear camera",tint=Color.White)
                    }
                }
            } else {
                Text("Only sent when you confirm. Gallery stays unchanged.",
                    color=Color.White.copy(alpha=.86f),style=MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick=::discard,modifier=Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(7.dp));Text("Retake")
                    }
                    Button(onClick={
                        val file=captured ?: return@Button
                        submitted.set(true)
                        onSend(file)
                    },modifier=Modifier.weight(1f),enabled=!busy && previewImage!=null) {
                        Icon(Icons.Default.Check,null);Spacer(Modifier.width(7.dp));Text("Send photo")
                    }
                }
            }
        }
    }
}
