package com.oojoo.farm.master.network

import android.content.ContentResolver
import android.net.Uri
import com.oojoo.farm.master.model.CommunityMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 업로드 실패 — 서버 상태코드(없으면 0, 네트워크 오류)와 서버 메시지. */
class UploadException(val code: Int, message: String) : IOException(message) {
    val tooLarge: Boolean get() = code == 413
    val unsupported: Boolean get() = code == 415
}

/** 올릴 파일 — 사진은 축소·재인코딩한 바이트, 동영상은 원본을 스트리밍. */
sealed class UploadSource {
    abstract val mime: String
    abstract val fileName: String

    class Bytes(val bytes: ByteArray, override val mime: String, override val fileName: String) : UploadSource()

    class Content(
        val resolver: ContentResolver,
        val uri: Uri,
        override val mime: String,
        override val fileName: String,
        val length: Long?
    ) : UploadSource()
}

/**
 * 이웃 글 첨부 업로드 — `POST api/community/media` (multipart: userId, file, thumb?, width/height/durationMs).
 *
 * ApiClient 의 OkHttp 는 BODY 로깅 인터셉터가 요청 본문을 통째로 메모리에 담아 찍기 때문에,
 * 동영상(최대 100MB)은 로깅 없는 별도 클라이언트로 스트리밍한다.
 */
object MediaUploader {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS) // 쓰기 한 번 사이의 최대 정지 시간 (전체 시간 아님)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun uploadCommunityMedia(
        userId: String,
        source: UploadSource,
        thumbJpeg: ByteArray?,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        onProgress: (Float) -> Unit
    ): CommunityMedia {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("userId", userId)
        width?.let { form.addFormDataPart("width", it.toString()) }
        height?.let { form.addFormDataPart("height", it.toString()) }
        durationMs?.let { form.addFormDataPart("durationMs", it.toString()) }
        form.addFormDataPart("file", source.fileName, source.toRequestBody())
        thumbJpeg?.let { form.addFormDataPart("thumb", "thumb.jpg", it.toRequestBody(JPEG)) }

        val request = Request.Builder()
            .url(ApiClient.baseUrl.trimEnd('/') + "/api/community/media")
            .post(ProgressBody(form.build(), onProgress))
            .build()

        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw UploadException(res.code, serverError(text) ?: "HTTP ${res.code}")
                json.decodeFromString(CommunityMedia.serializer(), text)
            }
        }
    }

    private fun serverError(body: String): String? = try {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    private val JPEG = "image/jpeg".toMediaType()

    private fun UploadSource.toRequestBody(): RequestBody = when (this) {
        is UploadSource.Bytes -> bytes.toRequestBody(mime.toMediaType())
        is UploadSource.Content -> ContentBody(resolver, uri, mime.toMediaType(), length)
    }

    /** content:// 를 그대로 흘려보내는 본문 — 메모리에 올리지 않는다. */
    private class ContentBody(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val type: MediaType,
        private val length: Long?
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length ?: -1L
        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            input.source().use { sink.writeAll(it) }
        }
    }

    /** 보낸 바이트를 세어 0..1 진행률로 알린다 (1% 단위로만). */
    private class ProgressBody(
        private val delegate: RequestBody,
        private val onProgress: (Float) -> Unit
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            var lastPercent = -1
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    if (total > 0) {
                        val percent = (sent * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }.buffer()
            delegate.writeTo(counting)
            counting.flush()
        }
    }

    /** 코루틴 취소(화면 이탈) 시 요청도 끊는다. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }
}
