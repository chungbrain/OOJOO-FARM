package com.oojoo.farm.slave.vision

/**
 * F4 — 카메라 위치 기반 자동 가로/세로 촬영 정책.
 *
 * 고정 설치(헤드리스/회전 잠금) Farmer 기기는 디스플레이 회전 감지가 신뢰 불가할
 * 수 있어, 카메라 물리 설치 위치(mount)를 원격 정책(camera_mount)으로 명시 지정한다.
 * 리졸버 수학은 PC 시뮬레이션(simulation/src/engine/orientation.js)과 동일 규격 —
 * docs/farmer-features.md §4.
 *
 * mount:
 *   auto(기본) — 디스플레이 회전 감지 기반(기존 DisplayListener 로직)
 *   landscape  — 가로 설치 고정 → 가로모드 촬영
 *   portrait   — 세로 설치 고정 → 세로모드 촬영
 */
object OrientationPolicy {
    const val MOUNT_AUTO = "auto"
    const val MOUNT_LANDSCAPE = "landscape"
    const val MOUNT_PORTRAIT = "portrait"

    private val ROTATION_DEG = intArrayOf(0, 90, 180, 270)

    /** 원격 정책(camera_mount) — FarmerEngine.syncPolicy() 가 갱신. */
    @Volatile
    var mount: String = MOUNT_AUTO
        private set

    fun setMount(value: String?) {
        mount = when (value) {
            MOUNT_LANDSCAPE -> MOUNT_LANDSCAPE
            MOUNT_PORTRAIT -> MOUNT_PORTRAIT
            else -> MOUNT_AUTO
        }
    }

    /** natural portrait 센서(90/270°) 여부 — 스마트폰 후면 카메라 전형. */
    fun isNaturalPortraitSensor(sensorOrientation: Int): Boolean =
        sensorOrientation == 90 || sensorOrientation == 270

    data class OrientationPlan(
        val targetRotation: Int,  // Surface.ROTATION_* (0..3) — use case 에 지정
        val exifRotation: Int,     // 사진 EXIF 회전(도) — upright 변환각
        val captureMode: String    // "landscape" | "portrait" — 최종 사진 방향
    )

    /**
     * F4 리졸버 — sensorOrientation + displayRotation + mount → 촬영 방향 결정.
     * natural portrait 센서(90°) 기준:
     *   mount=landscape → targetRotation=1 → EXIF 0°  → 640x480 가로 사진
     *   mount=portrait  → targetRotation=0 → EXIF 90° → 480x640 세로 사진
     *   auto           → 디스플레이 회전(0=세로 설치 / 1·3=가로 설치)을 따름
     */
    fun resolve(
        sensorOrientation: Int,
        displayRotation: Int,
        mount: String = this.mount
    ): OrientationPlan {
        val naturalPortrait = isNaturalPortraitSensor(sensorOrientation)
        val target = when (mount) {
            MOUNT_LANDSCAPE -> if (naturalPortrait) 1 else 0
            MOUNT_PORTRAIT -> if (naturalPortrait) 0 else 1
            else -> displayRotation.coerceIn(0, 3)
        }
        val targetDeg = ROTATION_DEG[target]
        val exif = (((sensorOrientation - targetDeg) % 360) + 360) % 360
        val rotatedQuarter = target % 2 == 1
        val outputPortrait = naturalPortrait xor rotatedQuarter
        return OrientationPlan(
            targetRotation = target,
            exifRotation = exif,
            captureMode = if (outputPortrait) "portrait" else "landscape"
        )
    }
}
