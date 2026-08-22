package com.oojoo.farm.slave.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ROI(관심 영역) 저장소.
 *
 * 하나의 카메라 화면 안에 여러 식물이 있을 수 있다.
 * 각 식물은 화면 좌표계(0.0~1.0 정규화)의 사각형 ROI에 매핑되고,
 * 모니터링 루프는 프레임에서 각 ROI만 잘라 해당 식물의 모델로 진단한다.
 *
 * 저장: files/rois.json (앱 재시작에도 유지)
 */
data class PlantRoi(
    val plantId: String,
    val plantName: String,
    val species: String?,
    /** 정규화 좌표 (0.0~1.0) — 좌상단 기준 */
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
) {
    fun isValid(): Boolean = w > 0.02f && h > 0.02f && x >= 0f && y >= 0f && x + w <= 1.001f && y + h <= 1.001f
}

object RoiStore {
    private const val FILE = "rois.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun list(ctx: Context): List<PlantRoi> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                PlantRoi(
                    plantId = o.getString("plantId"),
                    plantName = o.optString("plantName"),
                    species = o.optString("species").takeIf { it.isNotBlank() },
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat(),
                    w = o.getDouble("w").toFloat(),
                    h = o.getDouble("h").toFloat()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 식물당 하나의 ROI만 유지 (덮어쓰기). */
    fun save(ctx: Context, roi: PlantRoi) {
        val all = list(ctx).filter { it.plantId != roi.plantId } + roi
        writeAll(ctx, all)
    }

    fun remove(ctx: Context, plantId: String) {
        writeAll(ctx, list(ctx).filter { it.plantId != plantId })
    }

    fun clear(ctx: Context) = writeAll(ctx, emptyList())

    private fun writeAll(ctx: Context, rois: List<PlantRoi>) {
        val arr = JSONArray()
        for (r in rois) {
            arr.put(JSONObject().apply {
                put("plantId", r.plantId)
                put("plantName", r.plantName)
                put("species", r.species ?: JSONObject.NULL)
                put("x", r.x.toDouble())
                put("y", r.y.toDouble())
                put("w", r.w.toDouble())
                put("h", r.h.toDouble())
            })
        }
        file(ctx).writeText(arr.toString())
    }
}
