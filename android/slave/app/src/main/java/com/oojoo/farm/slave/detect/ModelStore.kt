package com.oojoo.farm.slave.detect

import com.oojoo.farm.slave.model.ModelRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * 검출 모듈 모델 파일 저장소.
 *
 * 서버(GET /api/detectors/models/:file)에서 내려받은 OJP1 모델을 sha256 으로 검증한 뒤
 * `{sha256}.bin` 이름으로 보관한다 (내용 주소 — 서버 모델이 바뀌면 sha 가 바뀌어 새로 받는다).
 * 크기·해시가 하나라도 다르면 저장하지 않는다. 디스크의 파일도 읽을 때 다시 검증한다.
 *
 * Android 의존 없음 — fetch 를 주입받아 JVM 단위 테스트로 검증한다.
 */
class ModelStore(
    private val dir: File,
    private val fetch: suspend (url: String) -> ByteArray
) {
    companion object {
        const val MAX_MODEL_BYTES = 4 * 1024 * 1024
        private val SHA_HEX = Regex("^[0-9a-f]{64}$")

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val mutex = Mutex()

    private fun fileFor(sha: String) = File(dir, "$sha.bin")

    /** 이미 검증된 파일이 디스크에 있는지 (다운로드 필요 여부 판단용). */
    fun has(ref: ModelRef): Boolean {
        if (!SHA_HEX.matches(ref.sha256)) return false
        val f = fileFor(ref.sha256)
        return f.isFile && (ref.size <= 0 || f.length() == ref.size)
    }

    /** 검증된 모델 바이트 — 디스크에 있으면 재검증 후 반환, 없으면 내려받아 검증·저장. */
    suspend fun ensure(ref: ModelRef): Result<ByteArray> = mutex.withLock {
        try {
            if (!SHA_HEX.matches(ref.sha256)) return@withLock Result.failure(IllegalArgumentException("invalid sha256"))
            dir.mkdirs()
            val f = fileFor(ref.sha256)
            if (f.isFile) {
                val b = f.readBytes()
                if (sha256Hex(b) == ref.sha256) return@withLock Result.success(b)
                f.delete() // 손상 — 다시 받는다
            }
            if (ref.url.isBlank()) return@withLock Result.failure(IllegalStateException("no download url"))
            val bytes = fetch(ref.url)
            if (bytes.size > MAX_MODEL_BYTES) return@withLock Result.failure(IllegalStateException("model too large (${bytes.size} bytes)"))
            if (ref.size > 0 && bytes.size.toLong() != ref.size) {
                return@withLock Result.failure(IllegalStateException("size mismatch (${bytes.size} != ${ref.size})"))
            }
            val got = sha256Hex(bytes)
            if (got != ref.sha256) return@withLock Result.failure(IllegalStateException("sha256 mismatch"))
            val tmp = File(dir, "${ref.sha256}.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) { tmp.delete(); return@withLock Result.failure(IllegalStateException("cannot store model")) }
            }
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 쓰이지 않는 모델 파일 정리 (모듈 삭제 시) — 지운 파일 수. */
    suspend fun retainOnly(shas: Set<String>): Int = mutex.withLock {
        val files = dir.listFiles() ?: return@withLock 0
        var n = 0
        for (f in files) {
            val keep = f.name.endsWith(".bin") && f.name.removeSuffix(".bin") in shas
            if (!keep && f.delete()) n++
        }
        n
    }
}
