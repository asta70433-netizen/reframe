package com.example

import com.example.data.model.ZoomLevel
import com.example.data.model.ZoomOption
import com.example.ui.viewmodel.CameraUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraZoomTest {

    @Test
    fun `verify all required zoom options are defined with correct labels and progressive ratios`() {
        val expectedOptions = listOf("0.5x", "1x", "1.5x", "2x", "2.5x", "3x")
        val actualOptions = ZoomOption.entries.map { it.label }
        assertEquals(expectedOptions, actualOptions)

        val ratios = ZoomOption.entries.map { it.ratio }
        assertEquals(listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f), ratios)

        // 1x represents normal view and default
        assertEquals(ZoomOption.ZOOM_1X, ZoomOption.DEFAULT)
        assertEquals(1.0f, ZoomOption.ZOOM_1X.ratio, 0.001f)

        // Progressive zoom check: each step is greater than previous
        assertTrue(ZoomOption.ZOOM_0_5X.ratio < ZoomOption.ZOOM_1X.ratio)
        assertTrue(ZoomOption.ZOOM_1X.ratio < ZoomOption.ZOOM_1_5X.ratio)
        assertTrue(ZoomOption.ZOOM_1_5X.ratio < ZoomOption.ZOOM_2X.ratio)
        assertTrue(ZoomOption.ZOOM_2X.ratio < ZoomOption.ZOOM_2_5X.ratio)
        assertTrue(ZoomOption.ZOOM_2_5X.ratio < ZoomOption.ZOOM_3X.ratio)
    }

    @Test
    fun `verify default zoom in CameraUiState is 1x and standard`() {
        val defaultState = CameraUiState()
        assertEquals(ZoomOption.ZOOM_1X, defaultState.selectedZoomOption)
        assertEquals(ZoomLevel.STANDARD, defaultState.zoomLevel)
        assertEquals("1x", defaultState.selectedZoomOption.label)
        assertEquals(1.0f, defaultState.selectedZoomOption.ratio, 0.001f)
    }

    @Test
    fun `verify ZoomOption toZoomLevel mapping`() {
        assertEquals(ZoomLevel.WIDE, ZoomOption.ZOOM_0_5X.toZoomLevel())
        assertEquals(ZoomLevel.STANDARD, ZoomOption.ZOOM_1X.toZoomLevel())
        assertEquals(ZoomLevel.STANDARD, ZoomOption.ZOOM_1_5X.toZoomLevel())
        assertEquals(ZoomLevel.STANDARD, ZoomOption.ZOOM_2X.toZoomLevel())
        assertEquals(ZoomLevel.STANDARD, ZoomOption.ZOOM_2_5X.toZoomLevel())
        assertEquals(ZoomLevel.STANDARD, ZoomOption.ZOOM_3X.toZoomLevel())
    }

    @Test
    fun `verify ZoomOption fromRatio mapping`() {
        assertEquals(ZoomOption.ZOOM_0_5X, ZoomOption.fromRatio(0.5f))
        assertEquals(ZoomOption.ZOOM_1X, ZoomOption.fromRatio(1.0f))
        assertEquals(ZoomOption.ZOOM_1_5X, ZoomOption.fromRatio(1.5f))
        assertEquals(ZoomOption.ZOOM_2X, ZoomOption.fromRatio(2.0f))
        assertEquals(ZoomOption.ZOOM_2_5X, ZoomOption.fromRatio(2.5f))
        assertEquals(ZoomOption.ZOOM_3X, ZoomOption.fromRatio(3.0f))
    }

    @Test
    fun `verify CameraUiState updates when selecting each zoom level`() {
        var state = CameraUiState()
        for (option in ZoomOption.entries) {
            state = state.copy(
                selectedZoomOption = option,
                zoomLevel = option.toZoomLevel()
            )
            assertEquals(option, state.selectedZoomOption)
            if (option == ZoomOption.ZOOM_0_5X) {
                assertEquals(ZoomLevel.WIDE, state.zoomLevel)
            } else {
                assertEquals(ZoomLevel.STANDARD, state.zoomLevel)
            }
        }
    }

    @Test
    fun `verify legacy ZoomLevel toggle still works for backwards compatibility`() {
        val standard = ZoomLevel.STANDARD
        val wide = standard.toggle()
        assertEquals(ZoomLevel.WIDE, wide)
        assertEquals("0.5", wide.label)
        assertEquals("1", standard.label)
        assertEquals(standard, wide.toggle())
    }
}
