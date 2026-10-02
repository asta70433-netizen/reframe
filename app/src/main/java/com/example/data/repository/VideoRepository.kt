package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.example.data.model.RecordedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoRepository(private val context: Context) {

    private val sessionRegex = Regex("""(\d{8}_\d{6})""")

    suspend fun getRecordedVideos(): List<RecordedVideo> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<RecordedVideo>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATA
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            val queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                queryUri,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                var count = 0
                while (cursor.moveToNext() && count < 60) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Video_$id.mp4"
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val dateAdded = cursor.getLong(dateColumn)
                    val dataPath = cursor.getString(dataColumn) ?: ""
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    // Load thumbnail & resolution
                    var resolutionStr = ""
                    val thumbnail = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.contentResolver.loadThumbnail(contentUri, Size(240, 240), null)
                        } else {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(context, contentUri)
                            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: ""
                            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: ""
                            if (w.isNotEmpty() && h.isNotEmpty()) {
                                resolutionStr = "${w}×${h}"
                            }
                            val frame = retriever.getFrameAtTime(1000000)
                            retriever.release()
                            frame
                        }
                    } catch (e: Exception) {
                        null
                    }

                    val isLandscape = name.contains("16x9") || name.contains("16_9")
                    val sessionId = sessionRegex.find(name)?.value ?: ""

                    videoList.add(
                        RecordedVideo(
                            id = id,
                            uri = contentUri,
                            filePath = dataPath,
                            name = name,
                            durationMillis = duration,
                            sizeBytes = size,
                            dateAdded = dateAdded,
                            isLandscape = isLandscape,
                            sessionId = sessionId,
                            aspectRatioLabel = if (isLandscape) "16:9" else "9:16",
                            resolution = resolutionStr,
                            thumbnailBitmap = thumbnail
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Also check app private movies directory for newly recorded items that might not have indexed yet
        try {
            val moviesDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir, "ReFrame")
            val fallbackDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            val dirsToCheck = listOfNotNull(moviesDir, fallbackDir).filter { it.exists() }

            dirsToCheck.forEach { dir ->
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
                    if (file.extension.equals("mp4", ignoreCase = true) && videoList.none { it.filePath == file.absolutePath || it.name == file.name }) {
                        val uri = Uri.fromFile(file)
                        val retriever = MediaMetadataRetriever()
                        var dur = 0L
                        var res = ""
                        var thumb = try {
                            retriever.setDataSource(file.absolutePath)
                            dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: ""
                            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: ""
                            if (w.isNotEmpty() && h.isNotEmpty()) {
                                res = "${w}×${h}"
                            }
                            retriever.getFrameAtTime(500000)
                        } catch (e: Exception) {
                            null
                        } finally {
                            try { retriever.release() } catch (ignored: Exception) {}
                        }

                        val isLandscape = file.name.contains("16x9") || file.name.contains("16_9")
                        val sessionId = sessionRegex.find(file.name)?.value ?: ""

                        videoList.add(
                            RecordedVideo(
                                id = file.name.hashCode().toLong(),
                                uri = uri,
                                filePath = file.absolutePath,
                                name = file.name,
                                durationMillis = dur,
                                sizeBytes = file.length(),
                                dateAdded = file.lastModified() / 1000,
                                isLandscape = isLandscape,
                                sessionId = sessionId,
                                aspectRatioLabel = if (isLandscape) "16:9" else "9:16",
                                resolution = res,
                                thumbnailBitmap = thumb
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        videoList.sortedByDescending { it.dateAdded }
    }

    suspend fun deleteVideo(video: RecordedVideo): Boolean = withContext(Dispatchers.IO) {
        try {
            if (video.uri.scheme == "file") {
                val file = File(video.filePath)
                if (file.exists()) file.delete() else false
            } else {
                val rows = context.contentResolver.delete(video.uri, null, null)
                rows > 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteSessionPair(sessionId: String, videos: List<RecordedVideo>): Boolean = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext false
        val pair = videos.filter { it.sessionId == sessionId }
        var allDeleted = true
        for (v in pair) {
            val success = deleteVideo(v)
            if (!success) allDeleted = false
        }
        allDeleted
    }
}
