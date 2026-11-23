package com.oojoo.farm.master.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.model.Plant
import kotlin.math.sqrt

/**
 * 농장 배치 구역 — 녹색 지면 안의 3행 × 4열 격자.
 * 홈 농장 씬(FarmSceneView), 식물 등록/상세의 위치 선택기가 공유하는 단일 정의.
 */
const val FARM_ROWS = 3
const val FARM_COLS = 4

/** 격자 한 칸 (row 0=맨 위(뒤), row FARM_ROWS-1=맨 아래(앞)). */
data class FarmCell(val row: Int, val col: Int)

/** 저장된 위치를 현재 그리드 크기 안으로 클램프 — 과거 4x4 시절 저장값(row/col=3) 호환. */
fun clampFarmCell(row: Int, col: Int): FarmCell =
    FarmCell(row.coerceIn(0, FARM_ROWS - 1), col.coerceIn(0, FARM_COLS - 1))

/**
 * 빈 cell 중 가장 좋은 위치 — 격자 중심에서 가까운 순, 같은 행에 1칸 간격 유지.
 * (새 식물 등록/이동 시 충돌 없이 배치하는 공용 규칙)
 */
fun pickEmptyFarmCell(used: Set<FarmCell>): FarmCell {
    val centerRow = (FARM_ROWS - 1) / 2f
    val centerCol = (FARM_COLS - 1) / 2f
    val order = (0 until FARM_ROWS).flatMap { r ->
        (0 until FARM_COLS).map { c -> FarmCell(r, c) to cellDistance(r, c, centerRow, centerCol) }
    }.sortedBy { it.second }.map { it.first }
    val spaced = order.firstOrNull { c ->
        c !in used && used.none { o -> o.row == c.row && kotlin.math.abs(o.col - c.col) <= 1 }
    }
    if (spaced != null) return spaced
    return order.firstOrNull { it !in used } ?: FarmCell(FARM_ROWS - 1, FARM_COLS - 1)
}

private fun cellDistance(row: Int, col: Int, centerRow: Float, centerCol: Float): Float {
    val dr = row - centerRow
    val dc = col - centerCol
    return sqrt(dr * dr + dc * dc)
}

/**
 * 현재 식물들의 농장 점유 현황 — Prefs 저장 위치 우선, 저장 없는 식물은
 * FarmSceneView와 같은 자동 배치 규칙으로 계산 (선택기 표시를 씬과 일치시킴).
 * excludePlantId: 편집 중인 식물 자신은 점유에서 제외.
 */
fun computeFarmOccupancy(ctx: Context, plants: List<Plant>, excludePlantId: String? = null): Map<String, FarmCell> {
    val stored = Prefs.farmPlantCells(ctx)
    val m = mutableMapOf<String, FarmCell>()
    for (p in plants) {
        if (p.id == excludePlantId) continue
        val rc = stored[p.id]
        m[p.id] = if (rc != null) clampFarmCell(rc.first, rc.second) else pickEmptyFarmCell(m.values.toSet())
    }
    return m
}

/**
 * 농장 위치 선택기 — 녹색 구역을 3×4 칸으로 보여주고 한 칸을 고른다.
 * 점유 칸은 해당 식물 이모지, 선택 칸은 노란 배경 + 배치할 식물 이모지.
 */
@Composable
fun FarmCellPicker(
    occupied: Map<String, FarmCell>,
    emojis: Map<String, String>,
    selected: FarmCell?,
    onSelect: (FarmCell) -> Unit,
    modifier: Modifier = Modifier,
    movingEmoji: String? = null
) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.farm_position_label), style = MaterialTheme.typography.labelMedium, color = OojooTheme.Muted)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.farm_position_hint), fontSize = 11.sp, color = OojooTheme.Muted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF66BB6A), Color(0xFF2E7D32))))
                .border(1.5.dp, OojooTheme.Ink.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (0 until FARM_ROWS).forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until FARM_COLS).forEach { c ->
                        val cell = FarmCell(r, c)
                        val occupant = occupied.entries.firstOrNull { it.value == cell }?.key
                        val isSelected = selected == cell
                        Box(
                            Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSelected -> OojooTheme.Yellow
                                        occupant != null -> Color(0xFFB5905E).copy(alpha = 0.92f)
                                        else -> Color.White.copy(alpha = 0.18f)
                                    }
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) OojooTheme.Ink else OojooTheme.Ink.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelect(cell) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when {
                                    isSelected -> movingEmoji ?: "✓"
                                    occupant != null -> emojis[occupant] ?: "🌱"
                                    else -> "·"
                                },
                                fontSize = if (isSelected || occupant != null) 18.sp else 13.sp,
                                color = if (occupant == null && !isSelected) Color.White.copy(alpha = 0.6f) else Color.Unspecified
                            )
                        }
                    }
                }
            }
        }
    }
}
