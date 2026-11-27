package com.oojoo.farm.master.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

enum class MediaKind { PHOTO, VIDEO }

/** 첨부할 수 없는 이유. */
enum class MediaProblem { UNSUPPORTED, TOO_LARGE, UNREADABLE, UPLOAD_FAILED }

/**
 * 이웃 글 첨부 규칙 — 서버(`backend/src/lib/communityMedia.js`)와 같은 상한.
 * 순수 함수만 두어 JVM 단위 테스트로 검증한다.
 */
object MediaRules {
    const val MAX_ITEMS = 10
    const val MAX_VIDEO_BYTES = 100L * 1024 * 1024
    const val PHOTO_MAX_EDGE = 1920
    const val THUMB_MAX_EDGE = 480
    const val PHOTO_QUALITY = 85
    const val THUMB_QUALITY = 80

    /** 서버가 받는 동영상 형식 → 업로드 파일 확장자. */
    val VIDEO_EXTENSIONS = mapOf(
        "video/mp4" to "mp4",
        "video/3gpp" to "3gp",
        "video/webm" to "webm",
        "video/quicktime" to "mov",
        "video/x-matroska" to "mkv"
    )

    /** 사진은 어떤 이미지 형식이든 JPEG 로 다시 인코딩해 올리므로 image 전체 허용. */
    fun kindOf(mime: String?): MediaKind? = when {
        mime == null -> null
        mime.startsWith("image/") -> MediaKind.PHOTO
        mime in VIDEO_EXTENSIONS -> MediaKind.VIDEO
        else -> null
    }

    /** 고르자마자 거를 이유 — 업로드 전에 알 수 있는 것만. 문제 없으면 null. */
    fun problemOf(kind: MediaKind?, sizeBytes: Long?): MediaProblem? = when {
        kind == null -> MediaProblem.UNSUPPORTED
        kind == MediaKind.VIDEO && sizeBytes != null && sizeBytes > MAX_VIDEO_BYTES -> MediaProblem.TOO_LARGE
        else -> null
    }

    /** 디코딩 샘플 크기 — 긴 변이 maxEdge 아래로 떨어지지 않는 가장 큰 2의 거듭제곱. */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        val longEdge = maxOf(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** 비율을 지키며 긴 변을 maxEdge 이하로 줄인 크기 (이미 작으면 그대로). */
    fun fitWithin(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxEdge || longEdge <= 0) return width to height
        val scale = maxEdge.toDouble() / longEdge
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /** 동영상 회전 메타데이터(90/270)면 화면에 보이는 가로·세로가 바뀐다. */
    fun displaySize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees % 180 != 0) height to width else width to height

    /** 0:07 · 1:05 · 1:02:03 */
    fun formatDuration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun uploadFileName(kind: MediaKind, mime: String, index: Int): String = when (kind) {
        MediaKind.PHOTO -> "photo_${index + 1}.jpg"
        MediaKind.VIDEO -> "video_${index + 1}.${VIDEO_EXTENSIONS[mime] ?: "mp4"}"
    }
}

/** 고른 파일을 미리 읽어 둔 정보 — 썸네일(최대 480px)과 동영상 길이·크기. */
class PreparedMedia(
    val kind: MediaKind?,
    val mime: String?,
    val sizeBytes: Long?,
    val thumb: Bitmap?,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null
)

class EncodedPhoto(val jpeg: ByteArray, val width: Int, val height: Int)

/** 갤러리에서 고른 사진·동영상 읽기 (IO 스레드에서 호출). */
object MediaPrep {

    fun prepare(context: Context, uri: Uri): PreparedMedia {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: guessMime(uri)
        val size = querySize(context, uri)
        return when (val kind = MediaRules.kindOf(mime)) {
            MediaKind.PHOTO -> PreparedMedia(kind, mime, size, decodePhoto(context, uri, MediaRules.THUMB_MAX_EDGE))
            MediaKind.VIDEO -> prepareVideo(context, uri, mime, size)
            null -> PreparedMedia(null, mime, size, null)
        }
    }

    /** 업로드용 사진 — 긴 변 1920px, EXIF 방향 반영, JPEG 85 (위치 등 EXIF 는 버려진다). */
    fun encodePhoto(context: Context, uri: Uri): EncodedPhoto? {
        val bitmap = decodePhoto(context, uri, MediaRules.PHOTO_MAX_EDGE) ?: return null
        return try {
            EncodedPhoto(jpeg(bitmap, MediaRules.PHOTO_QUALITY), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    private fun guessMime(uri: Uri): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase().orEmpty()
        return if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun querySize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else null
        }
    } catch (_: Exception) {
        null
    }

    /** 긴 변 maxEdge 이하로 디코딩 + EXIF 방향 회전. 못 읽으면 null. */
    private fun decodePhoto(context: Context, uri: Uri, maxEdge: Int): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = MediaRules.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            decoded?.let { orient(scaleDown(it, maxEdge), exifOrientation(context, uri)) }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val (w, h) = MediaRules.fitWithin(src.width, src.height, maxEdge)
        if (w == src.width && h == src.height) return src
        return Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }
    }

    private fun exifOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun orient(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(-90f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }

    private fun prepareVideo(context: Context, uri: Uri, mime: String?, size: Long?): PreparedMedia {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val shown = if (w != null && h != null) MediaRules.displaySize(w, h, rot) else null
            // 포스터 — 1초 지점(짧으면 중간) 근처 키프레임. 프레임은 회전이 이미 반영되어 나온다.
            val atUs = minOf(1_000_000L, (duration ?: 0L) * 500L)
            val edge = MediaRules.THUMB_MAX_EDGE
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge)
            } else {
                r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            PreparedMedia(
                MediaKind.VIDEO, mime, size, frame?.let { scaleDown(it, edge) },
                shown?.first, shown?.second, duration
            )
        } catch (_: Exception) {
            // 메타데이터를 못 읽어도 업로드는 시도할 수 있다 (포스터 없이)
            PreparedMedia(MediaKind.VIDEO, mime, size, null)
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
