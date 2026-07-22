package com.oojoo.farm.master.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 앱 다국어 지원 — 시스템 언어 자동 감지 + 사용자 설정 override.
 * Prefs.language가 "system"이면 시스템 locale을 따르고, "ko" 또는 "en"이면 해당 언어.
 */
object AppLocale {
    const val SYSTEM = "system"
    const val KOREAN = "ko"
    const val ENGLISH = "en"

    fun resolve(ctx: Context): String {
        val pref = Prefs.language(ctx)
        if (pref != SYSTEM) return pref
        val sysLang = ctx.resources.configuration.locales[0].language
        return if (sysLang.startsWith("ko")) KOREAN else ENGLISH
    }
}

/** 다국어 문자열. */
data class AppStrings(
    val home: String,
    val plants: String,
    val farmers: String,
    val market: String,
    val community: String,
    val myFarm: String,
    val plantHealth: String,
    val noPlants: String,
    val noFarmerAssigned: String,
    val noInfo: String,
    val refresh: String,
    val settings: String,
    val uiCustomize: String,
    val language: String,
    val languageSystem: String,
    val languageKorean: String,
    val languageEnglish: String,
    val reset: String,
    val register: String,
    val delete: String,
    val cancel: String,
    val assignFarmer: String,
    val plantName: String,
    val species: String,
    val plantedDate: String,
    val plantedDateOptional: String,
    val growthStage: String,
    val selectFarmer: String,
    val noSlaves: String,
    val unassign: String,
    val neighbor: String,
    val writePost: String,
    val searchCrop: String,
    val all: String,
    val share: String,
    val sell: String,
    val buy: String,
    val noPosts: String,
    val locationAuto: String,
    val locationDetecting: String,
    val locationRetry: String,
    val serverAddress: String,
    val nickname: String,
    val growingRegion: String,
    val startApp: String,
    val hello: String,
    val welcomeSubtitle: String,
    val quickWater: String
)

val koreanStrings = AppStrings(
    home = "홈",
    plants = "식물",
    farmers = "Farmer",
    market = "마켓",
    community = "이웃",
    myFarm = "나의 농장",
    plantHealth = "식물 건강 상태",
    noPlants = "등록된 식물이 없습니다.",
    noFarmerAssigned = "담당 Farmer 없음",
    noInfo = "정보없음",
    refresh = "새로고침",
    settings = "설정",
    uiCustomize = "UI 커스터마이징",
    language = "언어",
    languageSystem = "시스템 설정",
    languageKorean = "한국어",
    languageEnglish = "English",
    reset = "기본값으로 초기화",
    register = "등록",
    delete = "삭제",
    cancel = "취소",
    assignFarmer = "Farmer 배정",
    plantName = "식물 이름",
    species = "작물 종류",
    plantedDate = "식재일",
    plantedDateOptional = "식재일 (선택)",
    growthStage = "생장 단계",
    selectFarmer = "Farmer 선택",
    noSlaves = "연결된 Farmer가 없습니다.",
    unassign = "배정 해제",
    neighbor = "이웃",
    writePost = "글쓰기",
    searchCrop = "작물/제목 검색",
    all = "전체",
    share = "나눔",
    sell = "판매",
    buy = "구입",
    noPosts = "지역에 아직 글이 없어요.\n첫 글을 올려보세요!",
    locationAuto = "재배 지역 (자동)",
    locationDetecting = "위치·날씨 자동 설정 중…",
    locationRetry = "다시 감지",
    serverAddress = "서버 주소",
    nickname = "닉네임",
    growingRegion = "재배 지역",
    startApp = "시작하기!",
    hello = "안녕하세요!",
    welcomeSubtitle = "누구나 집에서 키우는\n재미있는 스마트 농장",
    quickWater = "빠른 관수!"
)

val englishStrings = AppStrings(
    home = "Home",
    plants = "Plants",
    farmers = "Farmer",
    market = "Market",
    community = "Neighbors",
    myFarm = "My Farm",
    plantHealth = "Plant Health Status",
    noPlants = "No plants registered.",
    noFarmerAssigned = "No Farmer assigned",
    noInfo = "No info",
    refresh = "Refresh",
    settings = "Settings",
    uiCustomize = "UI Customize",
    language = "Language",
    languageSystem = "System",
    languageKorean = "한국어",
    languageEnglish = "English",
    reset = "Reset to defaults",
    register = "Register",
    delete = "Delete",
    cancel = "Cancel",
    assignFarmer = "Assign Farmer",
    plantName = "Plant name",
    species = "Crop type",
    plantedDate = "Planted date",
    plantedDateOptional = "Planted date (optional)",
    growthStage = "Growth stage",
    selectFarmer = "Select Farmer",
    noSlaves = "No paired Farmers.",
    unassign = "Unassign",
    neighbor = "Neighbors",
    writePost = "Write",
    searchCrop = "Search crop/title",
    all = "All",
    share = "Share",
    sell = "Sell",
    buy = "Buy",
    noPosts = "No posts yet.\nBe the first to post!",
    locationAuto = "Growing region (auto)",
    locationDetecting = "Detecting location & weather…",
    locationRetry = "Retry",
    serverAddress = "Server address",
    nickname = "Nickname",
    growingRegion = "Growing region",
    startApp = "Get Started!",
    hello = "Hello!",
    welcomeSubtitle = "Grow your own smart farm\nat home",
    quickWater = "Quick Water!"
)

val LocalAppStrings = compositionLocalOf { koreanStrings }

@Composable
fun AppStringsProvider(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val lang = remember(ctx) { AppLocale.resolve(ctx) }
    val strings = if (lang == AppLocale.KOREAN) koreanStrings else englishStrings
    CompositionLocalProvider(LocalAppStrings provides strings, content = content)
}
