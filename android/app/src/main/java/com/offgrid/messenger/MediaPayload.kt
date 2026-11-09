package com.offgrid.messenger

/** Wire-compatible with web/protocol.js in the approved 1.6.1 web release.
 * Media is always enclosed in the existing authenticated AES-256-GCM OGM4 envelope.
 * Nothing about media changes the ESP-NOW or BLE link formats. */
object MediaPayload {
    const val IMAGE_WEBP = 0
    const val IMAGE_PNG = 1
    const val IMAGE_JPEG = 2
    const val VOICE_WEBM = 0
    const val VOICE_OGG = 1
    const val VOICE_MP4 = 2
    const val MAX_PHOTO_BYTES = 96 * 1024
    const val ANDROID_PHOTO_TARGET = 48 * 1024
    const val FAST_PHOTO_TARGET = 28 * 1024
    const val MAX_VOICE_BYTES = 384 * 1024
    const val ANDROID_VOICE_TARGET = 96 * 1024
    const val MAX_VOICE_DURATION_MS = 120_000

    data class Photo(val bytes:ByteArray, val mime:String, val width:Int, val height:Int)
    data class Voice(val bytes:ByteArray, val mime:String, val durationMs:Int)

    fun photoBody(mime:String, data:ByteArray, width:Int, height:Int):ByteArray {
        require(width in 1..65535 && height in 1..65535 && data.isNotEmpty() && data.size<=MAX_PHOTO_BYTES)
        val tag = when(mime.lowercase()) {
            "image/webp" -> IMAGE_WEBP
            "image/png" -> IMAGE_PNG
            "image/jpeg" -> IMAGE_JPEG
            else -> error("Unsupported image format")
        }
        return byteArrayOf(tag.toByte()) + data
    }
    fun unpackPhoto(body:ByteArray, width:Int, height:Int):Photo {
        require(body.size in 2..MAX_PHOTO_BYTES+1 && width in 1..4096 && height in 1..4096 &&
                width.toLong()*height <= 8_000_000L)
        val mime = when(body[0].toInt() and 255) {
            IMAGE_WEBP -> "image/webp"
            IMAGE_PNG -> "image/png"
            IMAGE_JPEG -> "image/jpeg"
            else -> error("Invalid image codec")
        }
        return Photo(body.copyOfRange(1,body.size),mime,width,height)
    }
    fun voiceBody(mime:String,data:ByteArray,durationMs:Int):ByteArray {
        require(data.size in 256..MAX_VOICE_BYTES && durationMs in 100..MAX_VOICE_DURATION_MS)
        val tag = when(mime.lowercase().substringBefore(';')) {
            "audio/webm" -> VOICE_WEBM
            "audio/ogg" -> VOICE_OGG
            "audio/mp4" -> VOICE_MP4
            else -> error("Unsupported voice codec")
        }
        return byteArrayOf(tag.toByte()) + data
    }
    fun unpackVoice(body:ByteArray, durationMs:Int):Voice {
        require(body.size in 257..MAX_VOICE_BYTES+1 && durationMs in 100..MAX_VOICE_DURATION_MS)
        val mime = when(body[0].toInt() and 255) {
            VOICE_WEBM -> "audio/webm"
            VOICE_OGG -> "audio/ogg"
            VOICE_MP4 -> "audio/mp4"
            else -> error("Invalid voice codec")
        }
        return Voice(body.copyOfRange(1,body.size),mime,durationMs)
    }
    fun durationParts(durationMs:Int):Pair<Int,Int> {
        require(durationMs in 100..MAX_VOICE_DURATION_MS)
        return Pair(durationMs and 65535, durationMs ushr 16)
    }
    fun durationFromParts(width:Int,height:Int):Int {
        require(width in 0..65535 && height in 0..65535)
        val duration = width + (height shl 16)
        require(duration in 100..MAX_VOICE_DURATION_MS)
        return duration
    }
}
