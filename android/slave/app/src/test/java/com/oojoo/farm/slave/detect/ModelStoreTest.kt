package com.oojoo.farm.slave.detect

import com.oojoo.farm.slave.model.ModelRef
import com.oojoo.farm.slave.vision.Ojp1
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // 앱에 내장된 실제 OJP1 모델 (서버 backend/models 와 같은 파일)
    private fun asset(name: String) = File("src/main/assets/models/$name").readBytes()

    private fun refOf(bytes: ByteArray, file: String = "pest.bin") =
        ModelRef(file, "/api/detectors/models/$file", ModelStore.sha256Hex(bytes), bytes.size.toLong())

    @Test
    fun downloadsVerifiesAndCachesBySha() = runBlocking {
        val bytes = asset("pest.bin")
        var calls = 0
        val store = ModelStore(tmp.root) { calls++; bytes }
        val ref = refOf(bytes)
        assertFalse(store.has(ref))
        assertArrayEquals(bytes, store.ensure(ref).getOrThrow())
        assertTrue(File(tmp.root, "${ref.sha256}.bin").isFile)
        assertTrue(store.has(ref))
        // 두 번째는 디스크에서 (재검증만)
        assertArrayEquals(bytes, store.ensure(ref).getOrThrow())
        assertEquals(1, calls)
    }

    @Test
    fun rejectsTamperedOrTruncatedDownload() = runBlocking {
        val bytes = asset("pest.bin")
        val ref = refOf(bytes)
        val tampered = bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val r1 = ModelStore(tmp.root) { tampered }.ensure(ref)
        assertTrue(r1.isFailure)
        assertTrue(r1.exceptionOrNull()!!.message!!.contains("sha256"))
        val r2 = ModelStore(tmp.root) { bytes.copyOf(bytes.size - 4) }.ensure(ref)
        assertTrue(r2.exceptionOrNull()!!.message!!.contains("size"))
        assertEquals("검증 실패한 모델은 저장하지 않는다", 0, tmp.root.listFiles()!!.count { it.name.endsWith(".bin") })
    }

    @Test
    fun corruptedFileOnDiskIsRedownloaded() = runBlocking {
        val bytes = asset("health_grade.bin")
        val ref = refOf(bytes, "health_grade.bin")
        File(tmp.root, "${ref.sha256}.bin").writeBytes(ByteArray(bytes.size))
        var calls = 0
        val r = ModelStore(tmp.root) { calls++; bytes }.ensure(ref)
        assertArrayEquals(bytes, r.getOrThrow())
        assertEquals(1, calls)
    }

    @Test
    fun networkFailureIsReportedNotThrown() = runBlocking {
        val bytes = asset("pest.bin")
        val r = ModelStore(tmp.root) { throw java.io.IOException("offline") }.ensure(refOf(bytes))
        assertTrue(r.isFailure)
        assertEquals("offline", r.exceptionOrNull()!!.message)
    }

    @Test
    fun retainOnlyDeletesUnusedModels() = runBlocking {
        val a = asset("pest.bin")
        val b = asset("health_grade.bin")
        val store = ModelStore(tmp.root) { url -> if (url.contains("pest")) a else b }
        val ra = refOf(a, "pest.bin")
        val rb = refOf(b, "health_grade.bin")
        store.ensure(ra).getOrThrow()
        store.ensure(rb).getOrThrow()
        assertEquals(1, store.retainOnly(setOf(ra.sha256)))
        assertTrue(store.has(ra))
        assertFalse(store.has(rb))
    }

    /** DetectorManager 가 모델 출력 수로 카탈로그 라벨과의 일치를 확인한다 — 실제 파일의 출력 수 고정. */
    @Test
    fun bundledModelsHaveExpectedOutputs() {
        assertEquals(5, Ojp1.parse(asset("health_grade.bin")).dense.out)
        assertEquals(4, Ojp1.parse(asset("pest.bin")).dense.out)
        assertEquals(6, Ojp1.parse(asset("basil.bin")).dense.out)
        assertEquals(48, Ojp1.parse(asset("basil.bin")).size)
    }
}
