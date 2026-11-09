package com.offgrid.messenger

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Photo selection is offline: decode bounded, re-encode to JPEG (strips EXIF/GPS). */
object PhotoPreparer {
    fun prepare(resolver:ContentResolver,uri:Uri,targetBytes:Int=MediaPayload.ANDROID_PHOTO_TARGET):MediaPayload.Photo {
        require(targetBytes in 12*1024..MediaPayload.ANDROID_PHOTO_TARGET)
        val info=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        val source=resolver.openInputStream(uri) ?: error("Cannot open selected photo")
        source.use { BitmapFactory.decodeStream(it,null,info) } // Bounds-only decoding intentionally returns null.
        require(info.outWidth in 1..10000 && info.outHeight in 1..10000 &&
                info.outWidth.toLong()*info.outHeight<=50_000_000L) { "Photo dimensions not supported" }
        val sample=generateSequence(1){ it*2 }.first { info.outWidth/it<=1400 && info.outHeight/it<=1400 }
        val options=BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888 }
        var working=resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
            ?: error("Cannot decode selected photo")
        // If a detailed photo cannot reach the fast 28-KiB target, return a
        // valid <=48-KiB quality encode rather than failing a working feature.
        var qualityFallback:MediaPayload.Photo?=null
        try {
            for (maxEdge in intArrayOf(1280,1100,950,800,700,600,480,360)) {
                val scale=minOf(1.0,maxEdge.toDouble()/max(working.width,working.height))
                val w=max(1,(working.width*scale).toInt());val h=max(1,(working.height*scale).toInt())
                val scaled=if(w==working.width && h==working.height) working else Bitmap.createScaledBitmap(working,w,h,true)
                try {
                    for (quality in intArrayOf(83,75,67,58,48)) {
                        val stream=ByteArrayOutputStream()
                        if(!scaled.compress(Bitmap.CompressFormat.JPEG,quality,stream))continue
                        val bytes=stream.toByteArray()
                        if(bytes.size<=targetBytes)
                            return MediaPayload.Photo(bytes,"image/jpeg",scaled.width,scaled.height)
                        if(targetBytes<MediaPayload.ANDROID_PHOTO_TARGET &&
                            bytes.size<=MediaPayload.ANDROID_PHOTO_TARGET && qualityFallback==null)
                            qualityFallback=MediaPayload.Photo(bytes,"image/jpeg",scaled.width,scaled.height)
                    }
                } finally { if(scaled!==working)scaled.recycle() }
            }
            qualityFallback?.let { return it }
            error("Photo exceeds 48 KiB after compression; choose a simpler picture")
        } finally { working.recycle() }
    }
}

/** Explicit tap-to-record; AAC in MPEG-4 is accepted by the existing web voice protocol. */
class VoiceNoteRecorder(private val context:Context) {
    private var recorder:MediaRecorder?=null
    private var output:File?=null
    private var mime="audio/mp4"
    private var started=0L
    val isRecording get()=recorder!=null

    private fun createRecorder():MediaRecorder = if(Build.VERSION.SDK_INT>=31) MediaRecorder(context) else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    fun start() {
        check(recorder==null) { "Already recording" }
        // Ogg/Opus is supported by the desktop web receiver and is efficient
        // for speech; Android 8-9 fall back to interoperable AAC-in-MP4.
        val options=if(Build.VERSION.SDK_INT>=29) listOf("audio/ogg","audio/mp4") else listOf("audio/mp4")
        var lastError:Exception?=null
        for(format in options) {
            val file=File.createTempFile("offgrid-record-",if(format=="audio/ogg")".ogg" else ".m4a",context.cacheDir)
            val r=createRecorder()
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                if(format=="audio/ogg") {
                    r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    r.setAudioSamplingRate(48000)
                } else {
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setAudioSamplingRate(16000)
                }
                r.setAudioChannels(1)
                r.setAudioEncodingBitRate(16000)
                r.setOutputFile(file.absolutePath)
                r.prepare();r.start()
                recorder=r;output=file;mime=format;started=SystemClock.elapsedRealtime()
                return
            } catch(ex:Exception) {
                lastError=ex
                r.release();file.delete()
            }
        }
        throw lastError ?: IllegalStateException("No supported microphone encoder")
    }
    fun finish(cancel:Boolean=false):MediaPayload.Voice? {
        val r=recorder ?: return null
        val file=output
        val elapsed=(SystemClock.elapsedRealtime()-started).toInt().coerceAtMost(30_000)
        recorder=null;output=null;started=0
        try {
            if(cancel){ try{r.stop()}catch(_:RuntimeException){};return null }
            r.stop()
            val bytes=file?.readBytes() ?: error("Recording unavailable")
            require(bytes.size in 256..MediaPayload.ANDROID_VOICE_TARGET) { "Voice note is too large; record a shorter note" }
            require(elapsed>=500) { "Record for at least half a second" }
            return MediaPayload.Voice(bytes,mime,elapsed)
        } finally { r.release();file?.delete() }
    }
}
