package com.oojoo.farm.slave.album

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 앱 앨범 사진을 폰 갤러리의 "Pictures/oojoo" 폴더에 발행한다.
 *
 * - API 29+: MediaStore insert (RELATIVE_PATH) — 권한 불필요, 갤러리에 즉시 노출.
 * - API 24~28: 공용 Pictures/oojoo 에 파일 복사 + MediaScanner 스캔 —
 *   WRITE_EXTERNAL_STORAGE 가 위임되어야 하며, 아니면 조용히 스킵.
 *
 * 실패해도 앱 내부 앨범(PlantAlbum)과 업로드는 이미 완료된 상태이므로
 * 발행 실패는 로그만 남긴다 (null 반환).
 */
object GalleryPublisher {
    private const val TAG = "GalleryPublisher"
    const val FOLDER = "oojoo"

    fun publish(ctx: Context, jpeg: File, displayName: String): Uri? {
        if (!jpeg.exists() || jpeg.length() < 32) return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) publishScoped(ctx, jpeg, displayName)
            else publishLegacy(ctx, jpeg, displayName)
        } catch (e: Exception) {
            Log.w(TAG, "publish failed: $displayName — ${e.message}")
            null
        }
    }

    /** API 29+ — 스큒oped storage, MediaStore 으로 발행 (권한 불필요). */
    private fun publishScoped(ctx: Context, jpeg: File, displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            jpeg.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("output stream null")
        // PENDING 해제 — 이제 갤러리 인덱스에 노출
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, done, null, null)
        Log.i(TAG, "published to Pictures/$FOLDER: $displayName (${jpeg.length()}B)")
        return uri
    }

    /** API 24~28 — 공용 외부 저장소 직접 쓰기 (WRITE_EXTERNAL_STORAGE 필요). */
    private fun publishLegacy(ctx: Context, jpeg: File, displayName: String): Uri? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "WRITE_EXTERNAL_STORAGE not granted — legacy publish skipped: $displayName")
            return null
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER
        ).apply { mkdirs() }
        val dest = File(dir, displayName).apply { jpeg.copyTo(this, overwrite = true) }
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf("image/jpeg")) { _, _ -> }
        Log.i(TAG, "published (legacy) to Pictures/$FOLDER: $displayName (${jpeg.length()}B)")
        return Uri.fromFile(dest)
    }
}
