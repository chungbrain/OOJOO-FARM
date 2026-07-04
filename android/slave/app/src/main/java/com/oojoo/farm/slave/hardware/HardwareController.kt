package com.oojoo.farm.slave.hardware

import kotlinx.coroutines.flow.StateFlow

/**
 * 외부 하드웨어(급수 밸브/펌프, Fan, Laser) 제어 추상화. (PRD 4.5)
 *
 * 슬레이브가 로컬에서 직접 제어한다. 실제 구현은 BLE(ESP32) 로 하드웨어에 명령을 보내며,
 * 하드웨어가 없거나 미연결일 때는 [MockHardwareController] 가 동작을 시뮬레이션한다.
 *
 * 모든 액추에이터 동작은 **Fail-safe 타이머**를 내장해, 통신/전원 이상 시에도
 * 최대 동작시간([FAILSAFE_MAX_MS]) 후 자동으로 꺼지도록 한다.
 */
interface HardwareController {
    val connected: StateFlow<Boolean>

    fun connect()
    fun disconnect()

    /** 급수 밸브 개방 후 durationMs 뒤 자동 폐쇄(Fail-safe). */
    fun openValve(durationMs: Long)
    fun closeValve()

    /** 해충 퇴치 Fan 가동(자동 정지). */
    fun fan(durationMs: Long)

    /** 저출력 Laser 펄스(안전 규격, 짧은 시간). */
    fun laserPulse(durationMs: Long)

    companion object {
        /** 과수/과열 방지 최대 동작시간 (60초). */
        const val FAILSAFE_MAX_MS = 60_000L
    }
}
