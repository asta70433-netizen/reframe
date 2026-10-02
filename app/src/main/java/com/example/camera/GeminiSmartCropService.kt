package com.example.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.SmartCropAnalysisResult
import com.example.data.model.SmartCropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiSmartCropService {

    private val tag = "GeminiSmartCropService"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val isRequestInProgress = AtomicBoolean(false)
    private var lastAnalysisTimestamp = 0L
    private val minAnalysisIntervalMs = 1200L // Periodic sampling interval

    suspend fun analyzeFrameForSmartCrop(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        forced: Boolean = false
    ): SmartCropAnalysisResult? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forced && (now - lastAnalysisTimestamp < minAnalysisIntervalMs)) {
            return@withContext null
        }

        if (!isRequestInProgress.compareAndSet(false, true)) {
            return@withContext null
        }

        try {
            lastAnalysisTimestamp = now

            // 1. Prepare lightweight downsampled bitmap (e.g., max 480px width)
            val preparedBitmap = prepareOptimizedBitmap(bitmap, rotationDegrees)
            val base64Image = bitmapToBase64Jpeg(preparedBitmap, quality = 70)

            val apiKey = try {
                BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }

            if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
                val geminiResult = callGeminiVisionApi(apiKey, base64Image)
                if (geminiResult != null) {
                    return@withContext geminiResult
                }
            }

            // 2. Intelligent local fallback heuristic if API is unconfigured or network is unreachable
            return@withContext fallbackLocalSubjectAnalysis(preparedBitmap)
        } catch (e: Exception) {
            Log.w(tag, "Smart crop analysis encountered error: ${e.message}")
            return@withContext fallbackLocalSubjectAnalysis(bitmap)
        } finally {
            isRequestInProgress.set(false)
        }
    }

    private fun callGeminiVisionApi(apiKey: String, base64Jpeg: String): SmartCropAnalysisResult? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val prompt = """
            Analyze this camera frame for automated smart video reframing (cropping to 16:9 landscape and 9:16 portrait).
            Identify:
            1. Main people, faces, or primary moving subjects in the frame.
            2. If multiple people are present, find the center of interest that keeps everyone framed.
            3. Return the optimal focal center (focusX, focusY) as normalized coordinates between 0.0 and 1.0 (where 0,0 is top-left, 0.5,0.5 is center, 1,1 is bottom-right).
            4. Return the bounding box containing the main subject(s).
            
            Respond strictly in valid JSON format:
            {
              "focusX": 0.5,
              "focusY": 0.45,
              "subject": "Person in foreground",
              "subjectCount": 1,
              "confidence": 0.95,
              "box": {
                "left": 0.25,
                "top": 0.15,
                "right": 0.75,
                "bottom": 0.85
              }
            }
        """.trimIndent()

        val requestBodyJson = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Jpeg)
                            })
                        })
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)

            val generationConfig = JSONObject().apply {
                put("temperature", 0.1)
                put("topP", 0.8)
                put("responseMimeType", "application/json")
            }
            put("generationConfig", generationConfig)
        }

        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(tag, "Gemini API request failed with HTTP code: ${response.code}")
                return null
            }

            val responseBody = response.body?.string() ?: return null
            val rootJson = JSONObject(responseBody)
            val candidates = rootJson.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null

            val textResult = parts.getJSONObject(0).optString("text", "")
            return parseGeminiResponse(textResult)
        }
    }

    private fun parseGeminiResponse(jsonText: String): SmartCropAnalysisResult? {
        return try {
            val cleanedJson = jsonText
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val json = JSONObject(cleanedJson)
            val focusX = json.optDouble("focusX", 0.5).toFloat().coerceIn(0.05f, 0.95f)
            val focusY = json.optDouble("focusY", 0.5).toFloat().coerceIn(0.05f, 0.95f)
            val subject = json.optString("subject", "Main subject")
            val count = json.optInt("subjectCount", 1)
            val conf = json.optDouble("confidence", 0.9).toFloat()

            var box: SmartCropRect? = null
            if (json.has("box")) {
                val b = json.getJSONObject("box")
                box = SmartCropRect(
                    left = b.optDouble("left", 0.2).toFloat().coerceIn(0f, 1f),
                    top = b.optDouble("top", 0.2).toFloat().coerceIn(0f, 1f),
                    right = b.optDouble("right", 0.8).toFloat().coerceIn(0f, 1f),
                    bottom = b.optDouble("bottom", 0.8).toFloat().coerceIn(0f, 1f)
                )
            }

            SmartCropAnalysisResult(
                focusX = focusX,
                focusY = focusY,
                boundingBox = box,
                detectedSubject = subject,
                detectedCount = count,
                confidence = conf
            )
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse Gemini Smart Crop response JSON: ${e.message}")
            null
        }
    }

    /**
     * Highly responsive fallback: computes the visual center of weight/contrast
     * to keep framing centered on the subject even without network connectivity.
     */
    private fun fallbackLocalSubjectAnalysis(bitmap: Bitmap): SmartCropAnalysisResult {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) {
            return SmartCropAnalysisResult()
        }

        // Subsample grid to compute luminance saliency center
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 24).coerceAtLeast(1)

        var totalWeight = 0.0
        var sumX = 0.0
        var sumY = 0.0

        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Subject saliency weight based on skin-tone range and high local contrast
                val isSkinTone = (r > 95 && g > 40 && b > 20 && (r - g) > 15 && r > b)
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                val weight = if (isSkinTone) 3.5 else (lum / 255.0 * 1.2 + 0.5)

                sumX += x * weight
                sumY += y * weight
                totalWeight += weight
            }
        }

        val centerX = if (totalWeight > 0) ((sumX / totalWeight) / w).toFloat().coerceIn(0.2f, 0.8f) else 0.5f
        val centerY = if (totalWeight > 0) ((sumY / totalWeight) / h).toFloat().coerceIn(0.25f, 0.75f) else 0.45f

        val boxWidth = 0.45f
        val boxHeight = 0.55f
        val box = SmartCropRect(
            left = (centerX - boxWidth / 2f).coerceIn(0f, 1f),
            top = (centerY - boxHeight / 2f).coerceIn(0f, 1f),
            right = (centerX + boxWidth / 2f).coerceIn(0f, 1f),
            bottom = (centerY + boxHeight / 2f).coerceIn(0f, 1f)
        )

        return SmartCropAnalysisResult(
            focusX = centerX,
            focusY = centerY,
            boundingBox = box,
            detectedSubject = "Auto-Framed Subject",
            detectedCount = 1,
            confidence = 0.85f
        )
    }

    private fun prepareOptimizedBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        val targetWidth = 360
        val scale = targetWidth.toFloat() / src.width.toFloat()
        val targetHeight = (src.height * scale).toInt()

        val matrix = Matrix()
        matrix.postScale(scale, scale)
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }

        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 70): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
