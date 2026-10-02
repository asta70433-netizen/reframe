package com.example.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.example.data.model.SmartCropKeyframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VideoCropProcessor {

    private const val TAG = "VideoCropProcessor"
    private const val TIMEOUT_USEC = 10000L

    suspend fun cropAndSave16x9(
        context: Context,
        sourceUri: Uri,
        outputFileName: String,
        preferredFps: Int? = null,
        autoSave: Boolean = true,
        keyframes: List<SmartCropKeyframe>? = null,
        onProgress: (step: String) -> Unit = {}
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val tempOutputFile = File(context.cacheDir, "temp_crop_${System.currentTimeMillis()}.mp4")
        try {
            onProgress("Analyzing source stream…")
            val success = processVideoCrop(context, sourceUri, tempOutputFile, preferredFps, keyframes, onProgress)
            if (!success || !tempOutputFile.exists() || tempOutputFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Transcoded video file is empty or corrupted"))
            }

            if (autoSave) {
                onProgress("Saving landscape video to Gallery…")
                val savedUri = saveToGallery(context, tempOutputFile, outputFileName)
                if (savedUri != null) {
                    Result.success(savedUri)
                } else {
                    Result.failure(java.io.IOException("Failed to save 16:9 video to Android Gallery (MediaStore)"))
                }
            } else {
                onProgress("Saving landscape video to App Storage…")
                val savedUri = saveToInternal(context, tempOutputFile, outputFileName)
                if (savedUri != null) {
                    Result.success(savedUri)
                } else {
                    Result.failure(java.io.IOException("Failed to save 16:9 video to app storage"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during video crop", e)
            Result.failure(e)
        } finally {
            if (tempOutputFile.exists()) {
                tempOutputFile.delete()
            }
        }
    }

    private fun processVideoCrop(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        preferredFps: Int? = null,
        keyframes: List<SmartCropKeyframe>? = null,
        onProgress: (step: String) -> Unit
    ): Boolean {
        var extractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        var eglDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext = EGL14.EGL_NO_CONTEXT
        var eglSurface = EGL14.EGL_NO_SURFACE
        var decoderSurface: Surface? = null
        var decoderSurfaceTexture: SurfaceTexture? = null
        var glProgram = 0

        try {
            // Open video extractor safely
            extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, sourceUri, null)
            } catch (e: Exception) {
                val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                    ?: throw java.io.IOException("Cannot open source video recording file descriptor")
                extractor.setDataSource(pfd.fileDescriptor)
                pfd.close()
            }

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                val err = IllegalStateException("Source video file has no playable video stream")
                Log.e(TAG, "No video track found in $sourceUri", err)
                throw err
            }

            // Open audio extractor if audio track is present
            if (audioTrackIndex >= 0) {
                try {
                    audioExtractor = MediaExtractor()
                    try {
                        audioExtractor.setDataSource(context, sourceUri, null)
                    } catch (e: Exception) {
                        val audioPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                        if (audioPfd != null) {
                            audioExtractor.setDataSource(audioPfd.fileDescriptor)
                            audioPfd.close()
                        }
                    }
                    audioExtractor.selectTrack(audioTrackIndex)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize audio extractor", e)
                    audioExtractor?.release()
                    audioExtractor = null
                }
            }

            val rawWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val rawHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"

            // Extract video rotation metadata and capture framerate from metadata retriever
            var rotation = 0
            var extractedMetadataFps: Int? = null
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, sourceUri)
                val rotStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                if (!rotStr.isNullOrEmpty()) {
                    rotation = rotStr.toIntOrNull() ?: 0
                }
                val capFpsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                if (!capFpsStr.isNullOrEmpty()) {
                    extractedMetadataFps = capFpsStr.toFloatOrNull()?.toInt()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Retriever metadata read failed", e)
            } finally {
                try { retriever.release() } catch (ignored: Exception) {}
            }

            var detectedFps = preferredFps ?: 30
            if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    val formatFps = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                    if (formatFps > 0) detectedFps = formatFps
                } catch (e: Exception) {
                    try {
                        val formatFps = videoFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toInt()
                        if (formatFps > 0) detectedFps = formatFps
                    } catch (ignored: Exception) {}
                }
            } else if (extractedMetadataFps != null && extractedMetadataFps > 0) {
                detectedFps = extractedMetadataFps
            }
            val frameRate = detectedFps.coerceIn(15, 60)
            Log.d(TAG, "Using target frameRate=$frameRate for 16:9 transcode (preferredFps=$preferredFps)")

            if (rotation == 0 && videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
            }

            val displayWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
            val displayHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight

            // Determine output resolution (16:9 landscape) based on source
            val (targetOutWidth, targetOutHeight, targetBitrate) = selectOutputResolutionAndBitrate(displayWidth, displayHeight)

            onProgress("Configuring hardware encoder ($targetOutWidth×$targetOutHeight)...")

            // 1. Setup Video Encoder with fallback if needed
            val encoderSetup = createConfiguredEncoder(targetOutWidth, targetOutHeight, frameRate, targetBitrate)
            encoder = encoderSetup.first
            val outWidth = encoderSetup.second
            val outHeight = encoderSetup.third
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            // 2. Setup EGL on Encoder Input Surface
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val eglConfig = configs[0]

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderInputSurface, surfaceAttribs, 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            // 3. Setup OpenGL Shaders & Texture
            val vertexShaderSource = """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uTexMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * aTexCoord).xy;
                }
            """.trimIndent()

            val fragmentShaderSource = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """.trimIndent()

            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
            glProgram = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vShader)
                GLES20.glAttachShader(it, fShader)
                GLES20.glLinkProgram(it)
            }

            val aPositionLoc = GLES20.glGetAttribLocation(glProgram, "aPosition")
            val aTexCoordLoc = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
            val uTexMatrixLoc = GLES20.glGetUniformLocation(glProgram, "uTexMatrix")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            decoderSurfaceTexture = SurfaceTexture(texId)
            decoderSurface = Surface(decoderSurfaceTexture)

            // 4. Setup Decoder
            decoder = MediaCodec.createDecoderByType(videoMime)
            decoder.configure(videoFormat, decoderSurface, null, 0)
            decoder.start()

            // 5. Setup Quad Buffers with Exact 16:9 Full Frame Center Crop
            val quadCoords = floatArrayOf(
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
                 1.0f,  1.0f
            )
            val vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(quadCoords)
                    position(0)
                }

            val texCoords = calculateCropTexCoords(rawWidth, rawHeight, rotation, targetAspect = 16f / 9f)
            val texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(texCoords)
                    position(0)
                }

            val transformMatrix = FloatArray(16)

            // 6. Setup MediaMuxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(0)
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            extractor.selectTrack(videoTrackIndex)

            onProgress("Transcoding synchronized video stream…")

            // 7. Video Transcoding Loop
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            val bufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            val audioBuffer = ByteBuffer.allocateDirect(128 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()

            while (!encoderDone) {
                // Feed decoder
                if (!extractorDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain decoder to OpenGL surface
                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                    if (outIndex >= 0) {
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val render = bufferInfo.size > 0

                        decoder.releaseOutputBuffer(outIndex, render)

                        if (render) {
                            try {
                                decoderSurfaceTexture.updateTexImage()
                                decoderSurfaceTexture.getTransformMatrix(transformMatrix)

                                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                                GLES20.glViewport(0, 0, outWidth, outHeight)
                                GLES20.glClearColor(0f, 0f, 0f, 1f)
                                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                                GLES20.glUseProgram(glProgram)
                                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                                GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, transformMatrix, 0)

                                GLES20.glEnableVertexAttribArray(aPositionLoc)
                                GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

                                if (!keyframes.isNullOrEmpty()) {
                                    val (fx, fy) = interpolateFocus(keyframes, bufferInfo.presentationTimeUs)
                                    val dynamicCoords = calculateCropTexCoords(rawWidth, rawHeight, rotation, 16f / 9f, fx, fy)
                                    texCoordBuffer.position(0)
                                    texCoordBuffer.put(dynamicCoords)
                                    texCoordBuffer.position(0)
                                }

                                GLES20.glEnableVertexAttribArray(aTexCoordLoc)
                                GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

                                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                                GLES20.glDisableVertexAttribArray(aPositionLoc)
                                GLES20.glDisableVertexAttribArray(aTexCoordLoc)

                                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, bufferInfo.presentationTimeUs * 1000L)
                                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                            } catch (e: Exception) {
                                Log.w(TAG, "Frame render exception", e)
                            }
                        }

                        if (isEos) {
                            encoder.signalEndOfInputStream()
                            decoderDone = true
                        }
                    }
                }

                // Drain encoder to Muxer
                var encIndex = encoder.dequeueOutputBuffer(encBufferInfo, TIMEOUT_USEC)
                while (encIndex >= 0 || encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw RuntimeException("Encoder format changed twice")
                        }
                        val newFormat = encoder.outputFormat
                        muxerVideoTrack = muxer.addTrack(newFormat)

                        if (audioTrackIndex >= 0 && audioFormat != null) {
                            try {
                                muxerAudioTrack = muxer.addTrack(audioFormat)
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not add audio track to muxer", e)
                                muxerAudioTrack = -1
                            }
                        }

                        muxer.start()
                        muxerStarted = true
                    } else if (encIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(encIndex)
                        if (encodedData != null) {
                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encBufferInfo.size = 0
                            }

                            if (encBufferInfo.size > 0 && muxerStarted) {
                                encodedData.position(encBufferInfo.offset)
                                encodedData.limit(encBufferInfo.offset + encBufferInfo.size)
                                muxer.writeSampleData(muxerVideoTrack, encodedData, encBufferInfo)
                            }

                            encoder.releaseOutputBuffer(encIndex, false)

                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                    encIndex = encoder.dequeueOutputBuffer(encBufferInfo, 0)
                }

                // Interleave audio samples up to current video progress
                if (muxerStarted && muxerAudioTrack >= 0 && audioExtractor != null) {
                    val currentVideoPts = encBufferInfo.presentationTimeUs
                    while (true) {
                        val audioPts = audioExtractor.sampleTime
                        if (audioPts < 0) {
                            break
                        }
                        // Write audio if it is lagging behind video or if encoder is almost done
                        if (audioPts > currentVideoPts + 500_000L && !encoderDone) {
                            break
                        }
                        val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                        if (sampleSize < 0) {
                            break
                        }
                        audioBufferInfo.offset = 0
                        audioBufferInfo.size = sampleSize
                        audioBufferInfo.presentationTimeUs = audioPts
                        audioBufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
                        audioExtractor.advance()
                    }
                }
            }

            // Drain any remaining audio up to the end of stream
            if (muxerStarted && muxerAudioTrack >= 0 && audioExtractor != null) {
                while (true) {
                    val audioPts = audioExtractor.sampleTime
                    if (audioPts < 0) break
                    val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) break
                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = sampleSize
                    audioBufferInfo.presentationTimeUs = audioPts
                    audioBufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
                    audioExtractor.advance()
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in processVideoCrop", e)
            return false
        } finally {
            try { decoder?.stop() } catch (ignored: Exception) {}
            try { decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop() } catch (ignored: Exception) {}
            try { encoder?.release() } catch (ignored: Exception) {}
            try { decoderSurface?.release() } catch (ignored: Exception) {}
            try { decoderSurfaceTexture?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
            try { audioExtractor?.release() } catch (ignored: Exception) {}
            try {
                if (muxer != null) {
                    muxer.stop()
                    muxer.release()
                }
            } catch (ignored: Exception) {}

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
        }
    }

    fun calculateCropTexCoords(
        rawWidth: Int,
        rawHeight: Int,
        rotation: Int,
        targetAspect: Float = 16f / 9f,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f
    ): FloatArray {
        // Display dimensions after container rotation is applied
        val displayWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val displayHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight

        val srcAspect = if (displayHeight > 0) displayWidth.toFloat() / displayHeight.toFloat() else (9f / 16f)

        val (uMin, uMax, vMin, vMax) = if (srcAspect < targetAspect) {
            // Source is taller than target (e.g. 9:16 portrait source into 16:9 landscape):
            // Fill full horizontal width [0.0..1.0] and crop height centered on focusY
            val cropHeightFraction = (srcAspect / targetAspect).coerceIn(0.05f, 1.0f)
            val centerY = focusY.coerceIn(cropHeightFraction / 2.0f, 1.0f - cropHeightFraction / 2.0f)
            val minV = centerY - cropHeightFraction / 2.0f
            val maxV = centerY + cropHeightFraction / 2.0f
            listOf(0.0f, 1.0f, minV, maxV)
        } else {
            // Source is wider than target (e.g. 16:9 landscape source into 16:9 landscape):
            // Fill full vertical height [0.0..1.0] and crop width centered on focusX
            val cropWidthFraction = (targetAspect / srcAspect).coerceIn(0.05f, 1.0f)
            val centerX = focusX.coerceIn(cropWidthFraction / 2.0f, 1.0f - cropWidthFraction / 2.0f)
            val minU = centerX - cropWidthFraction / 2.0f
            val maxU = centerX + cropWidthFraction / 2.0f
            listOf(minU, maxU, 0.0f, 1.0f)
        }

        // Quad vertices (TRIANGLE_STRIP):
        // Vertex 0: Bottom-Left  (-1, -1)
        // Vertex 1: Bottom-Right ( 1, -1)
        // Vertex 2: Top-Left     (-1,  1)
        // Vertex 3: Top-Right    ( 1,  1)
        return when (rotation) {
            90 -> floatArrayOf(
                vMin, uMin,
                vMin, uMax,
                vMax, uMin,
                vMax, uMax
            )
            180 -> floatArrayOf(
                1.0f - uMin, 1.0f - vMin,
                1.0f - uMax, 1.0f - vMin,
                1.0f - uMin, 1.0f - vMax,
                1.0f - uMax, 1.0f - vMax
            )
            270 -> floatArrayOf(
                1.0f - vMin, 1.0f - uMin,
                1.0f - vMin, 1.0f - uMax,
                1.0f - vMax, 1.0f - uMin,
                1.0f - vMax, 1.0f - uMax
            )
            else -> floatArrayOf(
                uMin, vMin,
                uMax, vMin,
                uMin, vMax,
                uMax, vMax
            )
        }
    }

    fun interpolateFocus(keyframes: List<SmartCropKeyframe>?, timestampUs: Long): Pair<Float, Float> {
        if (keyframes.isNullOrEmpty()) return Pair(0.5f, 0.5f)
        if (keyframes.size == 1) return Pair(keyframes[0].focusX, keyframes[0].focusY)

        if (timestampUs <= keyframes.first().timestampUs) {
            return Pair(keyframes.first().focusX, keyframes.first().focusY)
        }
        if (timestampUs >= keyframes.last().timestampUs) {
            return Pair(keyframes.last().focusX, keyframes.last().focusY)
        }

        for (i in 0 until keyframes.size - 1) {
            val k0 = keyframes[i]
            val k1 = keyframes[i + 1]
            if (timestampUs in k0.timestampUs..k1.timestampUs) {
                val duration = (k1.timestampUs - k0.timestampUs).toFloat()
                val fraction = if (duration > 0f) ((timestampUs - k0.timestampUs) / duration).coerceIn(0f, 1f) else 0f
                val fx = k0.focusX + (k1.focusX - k0.focusX) * fraction
                val fy = k0.focusY + (k1.focusY - k0.focusY) * fraction
                return Pair(fx, fy)
            }
        }

        return Pair(keyframes.last().focusX, keyframes.last().focusY)
    }

    private fun selectOutputResolutionAndBitrate(inputWidth: Int, inputHeight: Int): Triple<Int, Int, Int> {
        val maxDim = maxOf(inputWidth, inputHeight)
        return when {
            maxDim >= 3840 -> Triple(3840, 2160, 20_000_000)
            maxDim >= 2560 -> Triple(2560, 1440, 14_000_000)
            maxDim >= 1920 -> Triple(1920, 1080, 8_000_000)
            else -> Triple(1280, 720, 4_000_000)
        }
    }

    private fun createConfiguredEncoder(
        width: Int,
        height: Int,
        frameRate: Int,
        bitRate: Int
    ): Triple<MediaCodec, Int, Int> {
        // Ensure standard 16:9 dimensions divisible by 16
        val safeW = (width / 16) * 16
        val safeH = (height / 16) * 16

        val standardResolutions = listOf(
            Triple(3840, 2160, 24_000_000),
            Triple(2560, 1440, 14_000_000),
            Triple(1920, 1080, 8_000_000),
            Triple(1280, 720, 4_000_000),
            Triple(960, 540, 2_000_000),
            Triple(640, 360, 1_500_000)
        )

        val targetCand = Triple(if (safeW > 0) safeW else 1920, if (safeH > 0) safeH else 1080, bitRate)
        val candidateResolutions = (listOf(targetCand) + standardResolutions.filter { it.first <= targetCand.first } + standardResolutions)
            .filter { it.first > 0 && it.second > 0 }
            .distinctBy { "${it.first}x${it.second}" }

        for ((w, h, br) in candidateResolutions) {
            try {
                val encoderFormat = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, br)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val encoder = MediaCodec.createEncoderByType("video/avc")
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return Triple(encoder, w, h)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure encoder at ${w}x${h}, trying fallback", e)
            }
        }

        // Ultimate fallback
        val defaultFormat = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(defaultFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return Triple(encoder, 1280, 720)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun saveToGallery(
        context: Context,
        sourceFile: File,
        displayName: String
    ): Uri? {
        var mediaStoreUri: Uri? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReFrame")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { outStream ->
                            sourceFile.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }

                        contentValues.clear()
                        contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, contentValues, null, null)
                        mediaStoreUri = uri
                    } catch (e: Exception) {
                        context.contentResolver.delete(uri, null, null)
                        Log.w(TAG, "Failed writing to MediaStore uri", e)
                    }
                }
            }

            // Also ensure copy exists in app Movies/ReFrame folder and trigger scanner
            val moviesDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "ReFrame")
            moviesDir.mkdirs()
            val destFile = File(moviesDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf("video/mp4")
            ) { _, scannedUri ->
                Log.d(TAG, "MediaScanner scanned 16:9 file: $scannedUri")
            }

            return mediaStoreUri ?: Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to gallery", e)
            return mediaStoreUri
        }
    }

    private fun saveToInternal(
        context: Context,
        sourceFile: File,
        displayName: String
    ): Uri? {
        return try {
            val moviesDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "ReFrame")
            moviesDir.mkdirs()
            val destFile = File(moviesDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to internal storage", e)
            null
        }
    }
}
