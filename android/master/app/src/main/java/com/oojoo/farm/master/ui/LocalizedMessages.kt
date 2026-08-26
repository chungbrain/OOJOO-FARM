package com.oojoo.farm.master.ui

import com.oojoo.farm.master.data.AppStrings

internal fun localizeMasterMessage(message: String, strings: AppStrings): String {
    if (!strings.isEnglish) return message
    val replacements = listOf(
        "신고 접수됨" to "Report submitted",
        "차단했습니다 (피드에서 숨김)" to "Blocked (hidden from the feed)",
        "제목을 입력하세요" to "Enter a title",
        "등록 실패" to "Registration failed",
        "오류" to "Error",
        "일시정지 지시" to "Pause command sent",
        "재개 지시" to "Resume command sent",
        "연결 해제됨" to "Disconnected",
        "퇴치 지시" to "Control command sent",
        "퇴치 승인" to "Control approved",
        "담당 Farmer 없음" to "No Farmer assigned",
        "관수 지시 전송 중" to "Sending watering command",
        "관수 지시 전송 완료" to "Watering command sent",
        "Farmer 배정 해제" to "Farmer unassigned",
        "Farmer 배정 완료" to "Farmer assigned",
        "배정 실패" to "Assignment failed",
        "삭제 실패" to "Deletion failed",
        "삭제됨" to "deleted",
        "식물 이름을 입력하세요" to "Enter a plant name",
        "수정되었습니다" to "Updated",
        "플랜으로 변경되었습니다" to "plan selected",
        "분석 불가" to "Analysis unavailable",
        "너무 어두움 (카메라 위치 확인 필요)" to "Too dark (check camera position)",
        "건강 (녹색 충분)" to "Healthy (sufficient greenness)",
        "보통 (녹색 약간 부족)" to "Fair (slightly low greenness)",
        "주의 (황변 의심)" to "Caution (possible yellowing)",
        "이상 (색상 심각)" to "Abnormal (severe color issue)"
    )
    return replacements.fold(message) { result, (source, target) -> result.replace(source, target) }
}
