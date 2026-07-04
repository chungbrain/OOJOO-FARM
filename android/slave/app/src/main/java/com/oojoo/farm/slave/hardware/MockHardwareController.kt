package com.oojoo.farm.slave.hardware

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 하드웨어가 없거나 BLE 미연결 시 사용하는 시뮬레이션 컨트롤러(기본값).
 * 동작을 로그로 남기고 Fail-safe 자동 정지 타이머만 실제로 돌린다.
 */
object MockHardwareController : HardwareController {
    private const val TAG = "MockHardware"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var valveJob: Job? = null
    private var fanJob: Job? = null

    private val _connected = MutableStateFlow(true) // 시뮬레이션은 항상 연결됨
    override val connected: StateFlow<Boolean> = _connected

    override fun connect() { _connected.value = true }
    override fun disconnect() { _connected.value = false }

    override fun openValve(durationMs: Long) {
        val d = durationMs.coerceIn(0, HardwareController.FAILSAFE_MAX_MS)
        Log.i(TAG, "밸브 개방 (${d}ms, fail-safe 자동 폐쇄)")
        valveJob?.cancel()
        valveJob = scope.launch {
            delay(d)
            closeValve()
        }
    }

    override fun closeValve() {
        valveJob?.cancel()
        Log.i(TAG, "밸브 폐쇄")
    }

    override fun fan(durationMs: Long) {
        val d = durationMs.coerceIn(0, HardwareController.FAILSAFE_MAX_MS)
        Log.i(TAG, "Fan 가동 (${d}ms)")
        fanJob?.cancel()
        fanJob = scope.launch {
            delay(d)
            Log.i(TAG, "Fan 정지")
        }
    }

    override fun laserPulse(durationMs: Long) {
        val d = durationMs.coerceIn(0, 2_000L) // 레이저는 짧게 제한
        Log.i(TAG, "Laser 펄스 (${d}ms)")
    }
}
