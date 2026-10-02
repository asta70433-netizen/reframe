package com.example.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

class DualCameraPreviewRenderer {

    private val tag = "DualCameraRenderer"

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraTexId = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    private var portraitSurfaceTexture: SurfaceTexture? = null
    private var portraitEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var portraitWidth = 0
    private var portraitHeight = 0

    private var landscapeSurfaceTexture: SurfaceTexture? = null
    private var landscapeEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var landscapeWidth = 0
    private var landscapeHeight = 0

    private var glProgram = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0

    private val vertexBuffer: FloatBuffer
    private val portraitTexCoordBuffer: FloatBuffer
    private val landscapeTexCoordBuffer: FloatBuffer
    private val landscapeTexCoords = FloatArray(8)
    private val transformMatrix = FloatArray(16)

    var smartCropTracker: SmartCropTracker? = null

    val renderExecutor: Executor = Executor { command ->
        renderHandler?.post(command) ?: command.run()
    }

    init {
        val quadCoords = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadCoords)
                position(0)
            }

        // Full-frame coordinates for 9:16 Portrait Preview
        val portraitTexCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
        portraitTexCoordBuffer = ByteBuffer.allocateDirect(portraitTexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(portraitTexCoords)
                position(0)
            }

        // Center 16:9 crop coordinates for Landscape Preview
        // Exact fraction: (9/16) / (16/9) = 81/256 ≈ 0.31640625
        val fractionY = (9.0f / 16.0f) / (16.0f / 9.0f)
        val vMin = (1.0f - fractionY) / 2.0f
        val vMax = (1.0f + fractionY) / 2.0f
        landscapeTexCoords[0] = 0.0f
        landscapeTexCoords[1] = vMin
        landscapeTexCoords[2] = 1.0f
        landscapeTexCoords[3] = vMin
        landscapeTexCoords[4] = 0.0f
        landscapeTexCoords[5] = vMax
        landscapeTexCoords[6] = 1.0f
        landscapeTexCoords[7] = vMax

        landscapeTexCoordBuffer = ByteBuffer.allocateDirect(landscapeTexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(landscapeTexCoords)
                position(0)
            }

        startRenderThread()
    }

    private fun startRenderThread() {
        if (renderThread != null) return
        val thread = HandlerThread("DualCameraRenderThread").apply { start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler

        handler.post {
            initEGL()
            initGL()
        }
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(tag, "eglGetDisplay failed")
            return
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(tag, "eglInitialize failed")
            return
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(tag, "eglCreateContext failed")
            return
        }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        }
    }

    private fun initGL() {
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

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)

        glProgram = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)
        }

        aPositionLoc = GLES20.glGetAttribLocation(glProgram, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(glProgram, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTexId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        cameraSurfaceTexture = SurfaceTexture(cameraTexId).apply {
            setOnFrameAvailableListener({
                renderHandler?.post { drawFrame() }
            }, renderHandler)
        }
        cameraSurface = Surface(cameraSurfaceTexture)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    val surfaceProvider: Preview.SurfaceProvider = Preview.SurfaceProvider { request ->
        val resolution = request.resolution
        val handler = renderHandler ?: run {
            startRenderThread()
            renderHandler!!
        }

        handler.post {
            cameraSurfaceTexture?.setDefaultBufferSize(resolution.width, resolution.height)
            val surface = cameraSurface
            if (surface != null) {
                request.provideSurface(surface, renderExecutor) { result ->
                    Log.d(tag, "Camera surface provided result: ${result.resultCode}")
                }
            }
        }
    }

    fun setPortraitTarget(surfaceTexture: SurfaceTexture?, width: Int, height: Int) {
        renderHandler?.post {
            if (portraitEglSurface != EGL14.EGL_NO_SURFACE) {
                if (pbufferSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                }
                EGL14.eglDestroySurface(eglDisplay, portraitEglSurface)
                portraitEglSurface = EGL14.EGL_NO_SURFACE
            }

            portraitSurfaceTexture = surfaceTexture
            portraitWidth = width
            portraitHeight = height

            if (surfaceTexture != null && eglConfig != null && width > 0 && height > 0) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                portraitEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    eglConfig,
                    surfaceTexture,
                    surfaceAttribs,
                    0
                )
            }
        }
    }

    fun setLandscapeTarget(surfaceTexture: SurfaceTexture?, width: Int, height: Int) {
        renderHandler?.post {
            if (landscapeEglSurface != EGL14.EGL_NO_SURFACE) {
                if (pbufferSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                }
                EGL14.eglDestroySurface(eglDisplay, landscapeEglSurface)
                landscapeEglSurface = EGL14.EGL_NO_SURFACE
            }

            landscapeSurfaceTexture = surfaceTexture
            landscapeWidth = width
            landscapeHeight = height

            if (surfaceTexture != null && eglConfig != null && width > 0 && height > 0) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                landscapeEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    eglConfig,
                    surfaceTexture,
                    surfaceAttribs,
                    0
                )
            }
        }
    }

    private fun drawFrame() {
        val st = cameraSurfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(transformMatrix)
        } catch (e: Exception) {
            return
        }

        // 1. Render to 9:16 Portrait Target (Full upright portrait field of view)
        if (portraitEglSurface != EGL14.EGL_NO_SURFACE && portraitWidth > 0 && portraitHeight > 0) {
            if (EGL14.eglMakeCurrent(eglDisplay, portraitEglSurface, portraitEglSurface, eglContext)) {
                GLES20.glViewport(0, 0, portraitWidth, portraitHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawTextureQuad(portraitTexCoordBuffer)
                EGL14.eglSwapBuffers(eglDisplay, portraitEglSurface)
            }
        }

        // 2. Render to 16:9 Landscape Target (Center-cropped 16:9 full-bleed field of view or Smart Crop)
        if (landscapeEglSurface != EGL14.EGL_NO_SURFACE && landscapeWidth > 0 && landscapeHeight > 0) {
            val (focusX, focusY) = smartCropTracker?.tickSmoothMovement() ?: Pair(0.5f, 0.5f)
            val fractionY = (9.0f / 16.0f) / (16.0f / 9.0f)
            val centerY = focusY.coerceIn(fractionY / 2f, 1.0f - fractionY / 2f)
            val vMin = centerY - fractionY / 2f
            val vMax = centerY + fractionY / 2f

            landscapeTexCoords[0] = 0.0f
            landscapeTexCoords[1] = vMin
            landscapeTexCoords[2] = 1.0f
            landscapeTexCoords[3] = vMin
            landscapeTexCoords[4] = 0.0f
            landscapeTexCoords[5] = vMax
            landscapeTexCoords[6] = 1.0f
            landscapeTexCoords[7] = vMax

            landscapeTexCoordBuffer.position(0)
            landscapeTexCoordBuffer.put(landscapeTexCoords)
            landscapeTexCoordBuffer.position(0)

            if (EGL14.eglMakeCurrent(eglDisplay, landscapeEglSurface, landscapeEglSurface, eglContext)) {
                GLES20.glViewport(0, 0, landscapeWidth, landscapeHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawTextureQuad(landscapeTexCoordBuffer)
                EGL14.eglSwapBuffers(eglDisplay, landscapeEglSurface)
            }
        }

        if (pbufferSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
        }
    }

    private fun drawTextureQuad(texCoords: FloatBuffer) {
        GLES20.glUseProgram(glProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, transformMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        renderHandler?.post {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (portraitEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, portraitEglSurface)
                    portraitEglSurface = EGL14.EGL_NO_SURFACE
                }
                if (landscapeEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, landscapeEglSurface)
                    landscapeEglSurface = EGL14.EGL_NO_SURFACE
                }
                if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                    pbufferSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            if (cameraSurface != null) {
                cameraSurface?.release()
                cameraSurface = null
            }
            if (cameraSurfaceTexture != null) {
                cameraSurfaceTexture?.release()
                cameraSurfaceTexture = null
            }
            renderThread?.quitSafely()
            renderThread = null
            renderHandler = null
        }
    }
}
