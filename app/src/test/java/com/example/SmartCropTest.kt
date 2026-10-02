package com.example

import com.example.camera.SmartCropTracker
import com.example.camera.VideoCropProcessor
import com.example.data.model.SmartCropAnalysisResult
import com.example.data.model.SmartCropKeyframe
import com.example.data.model.SmartCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCropTest {

    @Test
    fun testSmartCropTrackerTargetUpdate() {
        val tracker = SmartCropTracker()
        tracker.setEnabled(true)

        val rect = SmartCropRect(0.2f, 0.3f, 0.4f, 0.4f)
        val analysisResult = SmartCropAnalysisResult(
            detectedSubject = "Person smiling",
            focusX = 0.4f,
            focusY = 0.5f,
            boundingBox = rect,
            confidence = 0.95f
        )

        tracker.onNewAnalysisResult(analysisResult)

        val status = tracker.status.value
        assertEquals("Person smiling", status.detectedSubject)
        val (curX, curY) = tracker.tickSmoothMovement(1_000_000L)
        assertTrue(curX in 0.0f..1.0f)
        assertTrue(curY in 0.0f..1.0f)
    }

    @Test
    fun testSmartCropInterpolation() {
        val keyframes = listOf(
            SmartCropKeyframe(timestampUs = 0L, focusX = 0.5f, focusY = 0.5f),
            SmartCropKeyframe(timestampUs = 1_000_000L, focusX = 0.7f, focusY = 0.3f)
        )

        val (fxMid, fyMid) = VideoCropProcessor.interpolateFocus(keyframes, 500_000L)
        assertEquals(0.6f, fxMid, 0.01f)
        assertEquals(0.4f, fyMid, 0.01f)

        val (fxBefore, fyBefore) = VideoCropProcessor.interpolateFocus(keyframes, -100L)
        assertEquals(0.5f, fxBefore, 0.001f)
        assertEquals(0.5f, fyBefore, 0.001f)

        val (fxAfter, fyAfter) = VideoCropProcessor.interpolateFocus(keyframes, 2_000_000L)
        assertEquals(0.7f, fxAfter, 0.001f)
        assertEquals(0.3f, fyAfter, 0.001f)
    }

    @Test
    fun testCalculateCropTexCoordsPortraitRotation90() {
        // Source 1920x1080 raw, rotated 90 (upright 1080x1920 portrait)
        val coords = VideoCropProcessor.calculateCropTexCoords(
            rawWidth = 1920,
            rawHeight = 1080,
            rotation = 90,
            targetAspect = 16f / 9f,
            focusX = 0.5f,
            focusY = 0.5f
        )

        assertEquals(8, coords.size)
        for (v in coords) {
            assertTrue("Tex coord $v is out of bounds [0, 1]", v in 0.0f..1.0f)
        }

        // Vertex 0 (Bottom-Left), Vertex 1 (Bottom-Right), Vertex 2 (Top-Left), Vertex 3 (Top-Right)
        val u0 = coords[0]
        val v0 = coords[1]
        val u1 = coords[2]
        val v1 = coords[3]
        val u2 = coords[4]
        val v2 = coords[5]
        val u3 = coords[6]
        val v3 = coords[7]
        // Horizontal left-to-right on screen must move along v (from 0 to 1)
        assertTrue("v0 must be left (0.0)", Math.abs(v0 - 0.0f) < 0.01f)
        assertTrue("v1 must be right (1.0)", Math.abs(v1 - 1.0f) < 0.01f)
        // Vertical bottom-to-top on screen must move along u
        assertTrue("u2 must be greater than u0 for upright orientation with 90 deg rotation", u2 > u0)
    }

    @Test
    fun testCalculateCropTexCoordsLandscapeRotation0() {
        // Source 1920x1080 raw, rotation 0 (upright landscape)
        val coords = VideoCropProcessor.calculateCropTexCoords(
            rawWidth = 1920,
            rawHeight = 1080,
            rotation = 0,
            targetAspect = 16f / 9f,
            focusX = 0.5f,
            focusY = 0.5f
        )

        assertEquals(8, coords.size)
        // For rotation 0 with 16:9 into 16:9:
        // Vertex 0 (Bottom-Left) should be (0.0, 0.0)
        // Vertex 1 (Bottom-Right) should be (1.0, 0.0)
        // Vertex 2 (Top-Left) should be (0.0, 1.0)
        // Vertex 3 (Top-Right) should be (1.0, 1.0)
        assertEquals(0.0f, coords[0], 0.01f) // u0
        assertEquals(0.0f, coords[1], 0.01f) // v0
        assertEquals(1.0f, coords[2], 0.01f) // u1
        assertEquals(0.0f, coords[3], 0.01f) // v1
        assertEquals(0.0f, coords[4], 0.01f) // u2
        assertEquals(1.0f, coords[5], 0.01f) // v2
        assertEquals(1.0f, coords[6], 0.01f) // u3
        assertEquals(1.0f, coords[7], 0.01f) // v3
    }
}
