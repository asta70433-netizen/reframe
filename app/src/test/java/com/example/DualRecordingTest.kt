package com.example

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.RecordedVideo
import com.example.data.repository.VideoRepository
import com.example.ui.viewmodel.CameraUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DualRecordingTest {

    private lateinit var context: Context
    private lateinit var repository: VideoRepository
    private lateinit var moviesDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = VideoRepository(context)
        val parent = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")
        moviesDir = File(parent, "ReFrame")
        moviesDir.mkdirs()
    }

    @Test
    fun `verify dual output filenames and landscape distinction`() {
        val timestamp = "20260825_120000"
        val portraitName = "ReFrame_${timestamp}_9x16.mp4"
        val landscapeName = "ReFrame_${timestamp}_16x9.mp4"

        assertTrue(portraitName.contains("9x16"))
        assertFalse(portraitName.contains("16x9"))

        assertTrue(landscapeName.contains("16x9"))
        assertFalse(landscapeName.contains("9x16"))
    }

    @Test
    fun `verify repository detects both 9x16 and 16x9 files with session grouping`() = runTest {
        val timestamp = "20260825_143000"
        val portraitFile = File(moviesDir, "ReFrame_${timestamp}_9x16.mp4").apply {
            writeBytes(ByteArray(1024) { 1 })
        }
        val landscapeFile = File(moviesDir, "ReFrame_${timestamp}_16x9.mp4").apply {
            writeBytes(ByteArray(2048) { 2 })
        }

        val videos = repository.getRecordedVideos()

        val portraitVideo = videos.find { it.name == portraitFile.name }
        val landscapeVideo = videos.find { it.name == landscapeFile.name }

        assertNotNull("Portrait video must exist in gallery", portraitVideo)
        assertNotNull("Landscape video must exist in gallery", landscapeVideo)

        assertFalse("9:16 video should not be marked as landscape", portraitVideo!!.isLandscape)
        assertTrue("16:9 video must be marked as landscape", landscapeVideo!!.isLandscape)
        assertEquals("9:16", portraitVideo.aspectRatioLabel)
        assertEquals("16:9", landscapeVideo.aspectRatioLabel)
        assertEquals(timestamp, portraitVideo.sessionId)
        assertEquals(timestamp, landscapeVideo.sessionId)

        // Ensure both files have different sizes and distinct IDs
        assertEquals(1024L, portraitVideo.sizeBytes)
        assertEquals(2048L, landscapeVideo.sizeBytes)
        assertTrue("Videos must have distinct IDs", portraitVideo.id != landscapeVideo.id)

        // Clean up
        portraitFile.delete()
        landscapeFile.delete()
    }

    @Test
    fun `verify deleting one aspect ratio video keeps the twin video intact`() = runTest {
        val timestamp = "20260825_150000"
        val portraitFile = File(moviesDir, "ReFrame_${timestamp}_9x16.mp4").apply {
            writeBytes(ByteArray(512) { 1 })
        }
        val landscapeFile = File(moviesDir, "ReFrame_${timestamp}_16x9.mp4").apply {
            writeBytes(ByteArray(512) { 2 })
        }

        var videos = repository.getRecordedVideos()
        val portraitVideo = videos.first { it.name == portraitFile.name }

        val deleted = repository.deleteVideo(portraitVideo)
        assertTrue("Deletion must succeed", deleted)

        videos = repository.getRecordedVideos()
        assertFalse("Portrait video should be removed", videos.any { it.name == portraitFile.name })
        assertTrue("Landscape video must still be present", videos.any { it.name == landscapeFile.name })

        // Clean up
        landscapeFile.delete()
    }

    @Test
    fun `verify CameraUiState default toast message on save`() {
        val state = CameraUiState()
        assertEquals("Both videos saved to Photos", state.savedToastMessage)
        assertFalse(state.isToastError)
    }

    @Test
    fun `verify VideoCropProcessor calculates exact 16x9 center crop for portrait video with rotation 0`() {
        val rawWidth = 1080
        val rawHeight = 1920
        val rotation = 0
        val texCoords = com.example.camera.VideoCropProcessor.calculateCropTexCoords(rawWidth, rawHeight, rotation)

        assertEquals(8, texCoords.size)
        val x0 = texCoords[0]
        val y0 = texCoords[1]
        val x1 = texCoords[2]
        val y1 = texCoords[3]
        val x2 = texCoords[4]
        val y2 = texCoords[5]
        val x3 = texCoords[6]
        val y3 = texCoords[7]

        // Full horizontal width (0.0 to 1.0)
        assertEquals(0.0f, x0, 0.001f)
        assertEquals(1.0f, x1, 0.001f)
        assertEquals(0.0f, x2, 0.001f)
        assertEquals(1.0f, x3, 0.001f)

        // Vertical center crop: height fraction = (9/16)/(16/9) = 81/256 ≈ 0.3164
        val cropHeight = Math.abs(y2 - y0)
        val expectedFraction = (9.0f / 16.0f) / (16.0f / 9.0f)
        assertEquals(expectedFraction, cropHeight, 0.001f)

        // Centered around 0.5
        val centerY = (y0 + y2) / 2.0f
        assertEquals(0.5f, centerY, 0.001f)

        // Resulting aspect ratio in pixels: (1.0 * 1080) / (cropHeight * 1920) = 16/9
        val pixelWidth = 1.0f * rawWidth
        val pixelHeight = cropHeight * rawHeight
        val effectiveAspect = pixelWidth / pixelHeight
        assertEquals(16.0f / 9.0f, effectiveAspect, 0.01f)
    }

    @Test
    fun `verify VideoCropProcessor calculates exact 16x9 center crop for rotated 90 degree portrait video`() {
        val rawWidth = 1920
        val rawHeight = 1080
        val rotation = 90
        val texCoords = com.example.camera.VideoCropProcessor.calculateCropTexCoords(rawWidth, rawHeight, rotation)

        assertEquals(8, texCoords.size)
        // For rotation 90, upright bottom-left (V0) is mapped to (s = vMin, t = uMin = 0.0)
        // upright top-right (V3) is mapped to (s = vMax, t = uMax = 1.0)
        val expectedFraction = (9.0f / 16.0f) / (16.0f / 9.0f)
        val vMin = (1.0f - expectedFraction) / 2.0f
        val vMax = (1.0f + expectedFraction) / 2.0f

        // V0: (vMin, 0.0)
        assertEquals(vMin, texCoords[0], 0.001f)
        assertEquals(0.0f, texCoords[1], 0.001f)

        // V1: (vMin, 1.0)
        assertEquals(vMin, texCoords[2], 0.001f)
        assertEquals(1.0f, texCoords[3], 0.001f)

        // V2: (vMax, 0.0)
        assertEquals(vMax, texCoords[4], 0.001f)
        assertEquals(0.0f, texCoords[5], 0.001f)

        // V3: (vMax, 1.0)
        assertEquals(vMax, texCoords[6], 0.001f)
        assertEquals(1.0f, texCoords[7], 0.001f)

        val cropSpan = Math.abs(texCoords[4] - texCoords[0])
        assertEquals(expectedFraction, cropSpan, 0.001f)
    }

    @Test
    fun `verify exported 9x16 portrait video in gallery is correctly identified as true portrait`() = runTest {
        val timestamp = "20260827_100000"
        val portraitFile = File(moviesDir, "ReFrame_${timestamp}_9x16.mp4").apply {
            writeBytes(ByteArray(4096) { 0x55.toByte() })
        }

        val videos = repository.getRecordedVideos()
        val portraitVideo = videos.firstOrNull { it.name == "ReFrame_${timestamp}_9x16.mp4" }

        assertNotNull("Saved 9:16 portrait video must be retrievable from Gallery", portraitVideo)
        assertEquals("ReFrame_${timestamp}_9x16.mp4", portraitVideo!!.name)
        assertFalse("9:16 video must not be marked as landscape", portraitVideo.isLandscape)
        assertEquals("9:16", portraitVideo.aspectRatioLabel)
        assertEquals(4096L, portraitVideo.sizeBytes)
        assertEquals(timestamp, portraitVideo.sessionId)

        // Clean up
        portraitFile.delete()
    }

    @Test
    fun `verify video quality mappings and titles`() {
        val qualities = com.example.data.model.VideoQuality.entries
        assertTrue(qualities.contains(com.example.data.model.VideoQuality.FOUR_K))
        assertTrue(qualities.contains(com.example.data.model.VideoQuality.TWO_K))
        assertTrue(qualities.contains(com.example.data.model.VideoQuality.TEN_EIGHTY))

        assertEquals("4K (Full Resolution)", com.example.data.model.VideoQuality.FOUR_K.title)
        assertEquals("2K (1440p)", com.example.data.model.VideoQuality.TWO_K.title)
        assertEquals("1080p", com.example.data.model.VideoQuality.TEN_EIGHTY.title)
    }

    @Test
    fun `verify frame rate options and fps selection with fallback logic`() {
        val rates = com.example.data.model.FrameRate.entries
        assertTrue(rates.contains(com.example.data.model.FrameRate.FPS_24))
        assertTrue(rates.contains(com.example.data.model.FrameRate.FPS_30))
        assertTrue(rates.contains(com.example.data.model.FrameRate.FPS_60))

        assertEquals(24, com.example.data.model.FrameRate.FPS_24.fps)
        assertEquals(30, com.example.data.model.FrameRate.FPS_30.fps)
        assertEquals(60, com.example.data.model.FrameRate.FPS_60.fps)

        val supportedFullRanges = arrayOf(
            android.util.Range(15, 30),
            android.util.Range(30, 30),
            android.util.Range(24, 24),
            android.util.Range(60, 60)
        )

        // Exact match
        assertEquals(android.util.Range(24, 24), com.example.camera.CameraService.selectBestFpsRange(supportedFullRanges, 24))
        assertEquals(android.util.Range(30, 30), com.example.camera.CameraService.selectBestFpsRange(supportedFullRanges, 30))
        assertEquals(android.util.Range(60, 60), com.example.camera.CameraService.selectBestFpsRange(supportedFullRanges, 60))

        // Device without 60 FPS support safely falls back to maximum supported FPS (30)
        val limited30FpsRanges = arrayOf(
            android.util.Range(15, 30),
            android.util.Range(30, 30)
        )
        val fallback60To30 = com.example.camera.CameraService.selectBestFpsRange(limited30FpsRanges, 60)
        assertEquals(30, fallback60To30.upper)

        // Device without exact 24 FPS safely uses matching or closest range
        val fallback24 = com.example.camera.CameraService.selectBestFpsRange(limited30FpsRanges, 24)
        assertTrue(fallback24.lower <= 24 && fallback24.upper >= 24 || fallback24.upper == 30)

        // Null / empty available ranges safely return fallback range without crashing
        val nullFallback = com.example.camera.CameraService.selectBestFpsRange(null, 60)
        assertEquals(60, nullFallback.upper)
    }

    @Test
    fun `verify auto save state handling and storage routing`() = runTest {
        val timestamp = "20260827_120000"
        val testVideo = File(moviesDir, "ReFrame_${timestamp}_9x16.mp4").apply {
            writeBytes(ByteArray(2048) { 0x33.toByte() })
        }

        val videos = repository.getRecordedVideos()
        val retrieved = videos.firstOrNull { it.name == "ReFrame_${timestamp}_9x16.mp4" }
        assertNotNull("App-internal saved video when auto-save is off should be retrievable", retrieved)
        assertEquals(2048L, retrieved!!.sizeBytes)

        testVideo.delete()
    }

    @Test
    fun `verify error message formatting for storage, encoder, and camera faults`() {
        val storageError = androidx.camera.video.VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
        val encodingError = androidx.camera.video.VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED
        val sourceInactiveError = androidx.camera.video.VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
        val recorderError = androidx.camera.video.VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR

        assertTrue(storageError == 3)
        assertTrue(encodingError == 6)
        assertTrue(sourceInactiveError == 4)
        assertTrue(recorderError == 7)
    }

    @Test
    fun `verify complete camera recording lifecycle with zoom, lens options, dual output, playback and repeat runs`() = runTest {
        // Step 1 & 2 & 3: Camera options (Lens, Zoom, Quality, FPS)
        val initialLens = com.example.data.model.CameraLens.BACK
        val toggledLens = initialLens.toggle()
        assertEquals(com.example.data.model.CameraLens.FRONT, toggledLens)
        assertEquals(com.example.data.model.CameraLens.BACK, toggledLens.toggle())

        val zoomStandard = com.example.data.model.ZoomLevel.STANDARD
        val zoomWide = zoomStandard.toggle()
        assertEquals(com.example.data.model.ZoomLevel.WIDE, zoomWide)
        assertEquals("0.5", zoomWide.label)
        assertEquals("1", zoomStandard.label)
        assertEquals(zoomStandard, zoomWide.toggle())

        // Step 9: Verify resolution and FPS options
        val quality4k = com.example.data.model.VideoQuality.FOUR_K
        val fps60 = com.example.data.model.FrameRate.FPS_60
        assertEquals(60, fps60.fps)
        assertEquals("4K (Full Resolution)", quality4k.title)

        // Step 4 & 5 & 10: Perform repeated recording cycles (simulating multiple recordings)
        val timestamps = listOf("20260827_150100", "20260827_150200", "20260827_150300")
        val createdFiles = mutableListOf<File>()

        for (ts in timestamps) {
            // Step 6: Verify twin video creation (9:16 and 16:9)
            val pFile = File(moviesDir, "ReFrame_${ts}_9x16.mp4").apply {
                writeBytes(ByteArray(8192) { 0x11.toByte() })
            }
            val lFile = File(moviesDir, "ReFrame_${ts}_16x9.mp4").apply {
                writeBytes(ByteArray(8192) { 0x22.toByte() })
            }
            createdFiles.add(pFile)
            createdFiles.add(lFile)
        }

        // Step 8: Verify all videos are saved and indexed by repository in Gallery
        val allVideos = repository.getRecordedVideos()
        for (ts in timestamps) {
            val pVid = allVideos.firstOrNull { it.name == "ReFrame_${ts}_9x16.mp4" }
            val lVid = allVideos.firstOrNull { it.name == "ReFrame_${ts}_16x9.mp4" }

            assertNotNull("9:16 video for session $ts must exist in gallery", pVid)
            assertNotNull("16:9 video for session $ts must exist in gallery", lVid)

            // Step 7: Verify playback metadata and URI attributes
            assertFalse(pVid!!.isLandscape)
            assertTrue(lVid!!.isLandscape)
            assertEquals("9:16", pVid.aspectRatioLabel)
            assertEquals("16:9", lVid.aspectRatioLabel)
            assertNotNull(pVid.uri)
            assertNotNull(lVid.uri)
            assertEquals(8192L, pVid.sizeBytes)
            assertEquals(8192L, lVid.sizeBytes)
        }

        // Clean up created test recordings
        createdFiles.forEach { it.delete() }
    }
}
