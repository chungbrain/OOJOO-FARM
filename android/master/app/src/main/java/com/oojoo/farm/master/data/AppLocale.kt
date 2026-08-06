package com.oojoo.farm.master.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/**
 * 앱 다국어 지원 — 시스템 언어 자동 감지 + 사용자 설정 override.
 * Prefs.language가 "system"이면 시스템 locale을 따르고, "ko" 또는 "en"이면 해당 언어.
 *
 * 반응형: appLanguageState 가 Compose 에서 관찰하는 단일 소스.
 * - null  => 아직 사용자 override 없음 → AppLocale.resolve(ctx) 로 시스템/Prefs 기반 결정
 * - "ko"/"en"/"system" => 사용자가 설정 화면에서 선택한 값 (즉시 UI 갱신)
 */
object AppLocale {
    const val SYSTEM = "system"
    const val KOREAN = "ko"
    const val ENGLISH = "en"

    /** Compose 가 관찰하는 현재 언어 상태. null = resolve() fallback. */
    val appLanguageState = mutableStateOf<String?>(null)

    fun resolve(ctx: Context): String {
        val pref = Prefs.language(ctx)
        if (pref != SYSTEM) return pref
        val sysLang = ctx.resources.configuration.locales[0].language
        return if (sysLang.startsWith("ko")) KOREAN else ENGLISH
    }

    /** 언어 변경 — Prefs 영구 저장 + Compose 상태 갱신 (UI 즉시 갱신). */
    fun setLanguage(ctx: Context, lang: String) {
        Prefs.setLanguage(ctx, lang)
        appLanguageState.value = lang
    }
}

/**
 * 다국어 문자열. UI에서 정적으로 노출되는 모든 텍스트를 포함.
 * ViewModel 등 Composable 외부에서는 사용할 수 없으며, 동적 메시지는 항상 한국어로
 * 폴백하거나 UI 계층에서 [LocalAppStrings] 기반으로 변환한다.
 */
data class AppStrings(
    // 공통
    val appName: String,           // "OOJOO FARM"
    val back: String,              // "뒤로" / "Back"
    val close: String,             // "닫기" / "Close"
    val online: String,            // "🟢 온라인" / "🟢 Online"
    val offline: String,           // "⚪ 오프라인" / "⚪ Offline"
    val done: String,              // "완료" / "Done"
    val applyComplete: String,     // "적용 완료" / "Apply"
    val submit: String,            // "등록"/"게시" 등 다용도 submit
    val save: String,              // "저장" / "Save"

    // Bottom nav (MainActivity)
    val home: String,
    val plants: String,
    val farmers: String,
    val market: String,
    val community: String,

    // Home
    val myFarm: String,            // "🚜 나의 농장" / "🚜 My Farm"
    val plantHealth: String,       // "📋 식물 건강 상태"
    val noPlants: String,           // "등록된 식물이 없습니다."
    val noPlantsEmpty: String,     // "등록된 식물이 없어요!"
    val noFarmerAssigned: String,
    val noInfo: String,
    val refresh: String,           // "🔄 새로고침"
    val locationChecking: String,  // "위치 확인 중"
    val locationAutoSet: String,   // "위치 자동 설정"
    val weatherNightRain: String,
    val weatherNight: String,
    val weatherDayRain: String,
    val weatherDay: String,

    // Settings / Theme editor
    val settings: String,
    val uiCustomize: String,
    val language: String,
    val languageSystem: String,
    val languageKorean: String,
    val languageEnglish: String,
    val reset: String,
    val preview: String,                     // "미리보기"
    val previewDesc: String,                 // 슬라이더 안내 문구
    val uiDetailSettings: String,           // "UI 상세 설정"
    val cornerRadiusLabel: String,           // "모서리 둥글기 (Radius)"
    val shadowLabel: String,                // "그림자 크기 (Shadow)"
    val borderWidthLabel: String,           // "테두리 두께 (Border)"
    val serverAddress: String,
    val serverSection: String,               // "서버 설정"
    val serverApply: String,                 // "서버 주소 적용"
    val serverApplied: String,               // "✅ 적용됨: ..." prefix
    val serverAppliedSuffix: String,         // "(앱 재시작 시에도 유지됩니다)" 등 부가 설명
    val restartNotice: String,              // "앱 재시작 후 적용됩니다"

    // Plant list / detail / registration
    val myPlants: String,                    // "🌱 내 식물"
    val register: String,                    // "등록" / "＋ 등록"
    val delete: String,
    val cancel: String,
    val plantRegister: String,               // "식물 등록" 버튼 / 화면 타이틀
    val registerSuccess: String,             // "🌱 식물이 등록되었습니다!"
    val selectedCrop: String,                 // "선택된 작물"
    val cropInputPrompt: String,             // "작물 종류를 선택/입력하세요"
    val plantName: String,
    val plantNameRequired: String,           // "식물 이름 *"
    val plantNamePh: String,                 // "예: 방울토마토"
    val species: String,
    val speciesPh: String,                   // "예: 토마토, 바질"
    val speciesUnspecified: String,          // "작물 미지정"
    val plantedDate: String,
    val plantedDateOptional: String,         // "식재일 (선택)"
    val plantedDatePh: String,               // "YYYY-MM-DD (비워도 됨)"
    val growthStage: String,                  // "생장 단계"
    val growthStageSeedling: String,         // "묘목"
    val growthStageVegetative: String,       // "영양생장"
    val growthStageFlowering: String,        // "개화"
    val growthStageFruiting: String,         // "결실"
    val selectFarmer: String,                // "담당 Farmer" 필드 / 선택
    val selectLater: String,                 // "선택 안함 (나중에 연결)"
    val selectNone: String,                  // "선택 안함"
    val farmerOnlineBadge: String,           // "(온라인)"
    val farmerOfflineBadge: String,          // "(오프라인)"
    val assignFarmer: String,                // "＋ Farmer 배정"
    val assignFarmerTitle: String,           // "🤖 Farmer 배정" 다이얼로그 타이틀
    val selectFarmerPrompt: String,          // "에 배정할 Farmer를 선택하세요" (prefix)
    val noSlaves: String,                     // "연결된 Farmer가 없습니다.\nFarmer 페이지에서 먼저 페어링하세요."
    val unassign: String,                     // "배정 해제"
    val deletePlantTitle: String,             // "🗑️ 식물 삭제"
    val deletePlantConfirm: String,           // "을(를) 삭제합니다.\n관련 이벤트/관수 기록도 함께 삭제됩니다."
    val plantDetail: String,                  // "식물 상세"
    val plantNotFound: String,                // "식물을 찾을 수 없습니다"
    val typeLabel: String,                    // "종류"
    val datePlantedLabel: String,             // "식재일"
    val stageLabel: String,                   // "단계"
    val farmerLabel: String,                  // "Farmer"
    val unknown: String,                       // "미상"
    val unconnected: String,                  // "미연결"
    val quickWater: String,                   // "빠른 관수 (원격 지시)"
    val healthInfo: String,                   // "건강 정보 요약"
    val overallStatus: String,                // "종합 상태"
    val waterNeed: String,                    // "수분 필요"
    val waterNeedYes: String,                 // "예 (관수 필요)"
    val waterNeedNo: String,                  // "아니오 (적정)"
    val pestSuspect: String,                  // "해충 의심"
    val found: String,                        // "발견됨!"
    val safe: String,                         // "안전"
    val healthScore: String,                  // "건강 점수"
    val waterHistory: String,                 // "관수 이력"
    val noWaterHistory: String,               // "관수 기록 없음"
    val autoMode: String,                     // "자율"
    val manualMode: String,                   // "수동"
    val recentEvents: String,                 // "최근 이벤트"
    val noEvents: String,                     // "이벤트 없음"
    // notification / event type labels (PlantDetailScreen.notiLabel)
    val notiHarvestReady: String,             // "🍅 수확 적기"
    val notiPestDetected: String,             // "🐛 해충 감지"
    val notiAutoWater: String,                // "💧 자율 관수"
    val notiManualWater: String,              // "💧 수동 관수"
    val notiAnomaly: String,                  // "⚠️ 이상 징후"
    val notiCapture: String,                  // "📷 캡처"

    // Farmer list / pairing
    val farmerManage: String,                 // "🤖 Farmer 관리"
    val gallery: String,                       // "📷 사진첩"
    val subscription: String,                  // "⭐ 구독"
    val connectFarmer: String,                // "🤝 Farmer 연결"
    val pairingTitle: String,                  // "Farmer 페어링"
    val pairingDesc: String,                  // "마스터에서 만든 코드를\nFarmer 앱에 입력하세요!"
    val accountLabel: String,                 // "계정"
    val generatePairCode: String,             // "🔑 페어링 코드 생성"
    val pairingCode: String,                   // "페어링 코드"
    val validLabel: String,                   // "유효"
    val pairingInstructions: String,          // "유효시간 10분 · 1회용 — 이 코드를 Farmer 앱에 입력!"
    val noFarmersEmpty: String,               // "연결된 Farmer가 없어요!"
    val pairingTip: String,                    // "＋ 버튼으로 페어링해요!"
    val lastComm: String,                     // "마지막 통신"
    val pause: String,                        // "⏸ 정지"
    val resumeAction: String,                 // "▶ 재개"
    val fanControl: String,                   // "🌀 Fan"
    val laserControl: String,                 // "🔦 Laser"
    val viewCamera: String,                   // "📹 카메라 보기 (3초)"
    val reportBtn: String,                    // "📊 리포트"
    val deleteFarmerTitle: String,            // "🗑️ Farmer 삭제"
    val deleteFarmerConfirm: String,          // "을(를) 삭제합니다.\n페어링이 해제되고 목록에서 사라집니다."

    // Notification
    val notificationCenter: String,           // "🔔 알림 센터"
    val noNotifications: String,             // "알림이 없어요!"

    // Market
    val marketTitle: String,                  // "마켓"
    val searchProducts: String,              // "상품 검색 ..."
    val recommendTitle: String,              // "내 식물 맞춤 추천"
    val bundleKits: String,                  // "추천 번들 키트"
    val contains: String,                     // "구성:"
    val addBundle: String,                   // "번들 담기"
    val products: String,                     // "상품"
    val searchResultsPrefix: String,         // "\""
    val searchResultsSuffix: String,         // "\" 검색결과"
    val affiliate: String,                    // "제휴"
    val selfHost: String,                     // "자체"
    val viewOrders: String,                  // "주문 내역 보기 ›"
    val productTitle: String,                // "상품"
    val stock: String,                        // "재고"
    val buyAtAffiliate: String,              // "제휴몰에서 구매하기 ↗"
    val affiliateNotice: String,            // "외부 제휴 상품 (CPS/CPA)"
    val addToCart: String,                  // "장바구니 담기"
    val addToCartDone: String,              // "장바구니에 담았습니다"
    val directBuy: String,                   // "바로 구매"
    val cart: String,                         // "장바구니"
    val emptyCart: String,                   // "장바구니가 비어 있습니다"
    val total: String,                       // "합계"
    val checkout: String,                    // "결제하기"
    val ordersNav: String,                    // "주문 내역"
    val noOrders: String,                     // "주문 내역이 없습니다"
    val orderPrefix: String,                 // "주문 "
    val rating: String,                       // "⭐" rating prefix label
    val vendor: String,                       // " · " vendor separator (실제 사용: ⭐ rating · vendor · 재고 stock)

    // Community
    val neighbor: String,                     // "이웃"
    val writePost: String,                    // "＋ 글쓰기" / topbar + FAB
    val searchCrop: String,                   // "작물/제목 검색"
    val all: String,                          // "전체"
    val share: String,                        // "나눔"
    val sell: String,                         // "판매"
    val buy: String,                          // "구입"
    val noPosts: String,                      // "지역에 아직 글이 없어요..."
    val noPostsPrefix: String,                // "" (region 앞)
    val noPostsSuffix: String,                // " 지역에 아직 글이 없어요.\n첫 글을 올려보세요!"
    val postTitle: String,                    // "게시물"
    val typeLabelGeneric: String,             // "유형"
    val titleField: String,                   // "제목 *"
    val titlePh: String,                      // "예: 상추 나눔해요"
    val cropLabel: String,                    // "작물"
    val cropPh: String,                       // "예: 상추, 토마토"
    val quantity: String,                     // "수량"
    val quantityPh: String,                   // "예: 한 봉지, 1kg"
    val priceWon: String,                    // "가격(원)"
    val description: String,                  // "설명"
    val descriptionPh: String,               // "자세한 내용"
    val emoji: String,                        // "대표 이모지"
    val postButton: String,                   // "게시하기"
    val regionLabel: String,                  // "지역" 라벨
    val reserved: String,                      // "예약중"
    val tradedone: String,                    // "거래완료"
    val available: String,                     // "거래 가능"
    val reportAction: String,                 // "신고"
    val blockAction: String,                  // "차단"
    val comments: String,                     // "댓글"
    val addComment: String,                   // "댓글 달기"
    val cropInfoLabel: String,                // "작물:"
    val quantityInfoLabel: String,            // "수량:"

    // Report
    val farmerReportTitle: String,            // "Farmer 리포트 (7일)"
    val waterSummary: String,                 // "관수 요약"
    val totalWaterCount: String,              // "총 관수 횟수"
    val totalWaterAmount: String,            // "총 급수량"
    val autoVsManual: String,                // "자율 / 수동"
    val lastWater: String,                    // "마지막 관수"
    val eventSummary: String,                 // "이벤트 요약"
    val harvestReadyReport: String,          // "🍅 수확 적기 감지"
    val pestDetectedReport: String,          // "🐛 해충 감지"
    val anomaliesReport: String,             // "⚠️ 이상 징후"
    val timesUnit: String,                    // "회"
    val reportNotice: String,                 // "최근 7일 기준 집계입니다."

    // Subscription
    val subscriptionPlan: String,             // "구독 플랜"
    val currentPlanPrefix: String,           // "현재 플랜: "
    val free: String,                         // "무료"
    val perMonth: String,                     // "/월"
    val unlimited: String,                    // "무제한"
    val farmerRegistrationPrefix: String,    // "• Farmer 등록: "
    val farmerRegistrationUnit: String,       // "대"
    val detailedReport: String,               // "상세 리포트"
    val priorityCs: String,                    // "우선 CS"
    val provided: String,                     // "제공"
    val notProvided: String,                 // "미제공"
    val inUse: String,                        // "이용 중"
    val freeConvert: String,                  // "무료 전환"
    val subscribe: String,                    // "구독하기"
    val paymentDemoNotice: String,            // "결제는 데모(시뮬레이션)입니다."

    // Gallery
    val backToList: String,                   // "목록으로"
    val emptyGallery: String,                 // "저장된 영상이 없어요!"
    val galleryTip: String,                   // "Farmer 카메라로 촬영하면 여기에 저장됩니다."
    val savedVideosPrefix: String,            // "🎬 저장된 영상 ("
    val savedVideosSuffix: String,            // "개)"
    val video3sec: String,                    // "📹 3초 영상"

    // Live camera
    val liveCameraPrefix: String,             // "📹 "
    val liveCameraSuffix: String,              // " 카메라"
    val statusWaiting: String,                // "요청 대기"
    val captureSending: String,               // "캡처 요청 전송 중…"
    val captureSentWaiting: String,            // "캡처 요청 전송됨 — 응답 대기 중"
    val captureTimeout: String,               // "시간 초과 — Farmer가 오프라인이거나 카메라 미준비일 수 있습니다"
    val captureDone: String,                  // "영상 수신 완료!"
    val captureFailed: String,                // "요청 실패"
    val savingToDevice: String,               // "기기에 저장 중…"
    val saveCompleteMsg: String,              // "✅ 기기에 저장됨!"
    val saveFailedPrefix: String,             // "저장 실패: "
    val videoCapturedPrefix: String,          // "🎬 3초 영상 (촬영: "
    val videoCapturedSuffix: String,           // ")"
    val justNow: String,                      // "방금"
    val recapture: String,                    // "🔄 다시 촬영"

    // Onboarding
    val startApp: String,                     // "🚀 시작하기!"
    val hello: String,                         // "안녕하세요!"
    val welcomeSubtitle: String,               // "누구나 집에서 키우는\n재미있는 스마트 농장"
    val nickname: String,                      // "닉네임"
    val nicknamePh: String,                   // "예: 농부민준"
    val locationAuto: String,                 // "재배 지역 (자동)"
    val locationDetecting: String,            // "위치·날씨 자동 설정 중…"
    val locationRetry: String,                // "다시 감지"
    val seoulFallback: String,                // "서울"
    val sourceGps: String,                    // "GPS"
    val sourceNetwork: String,                // "네트워크 위치"
    val sourceIp: String,                     // "IP 기반 위치"
    val sourceAuto: String,                    // "자동"
    val setByPrefix: String,                  // "" + 뒤 "으로 설정됨"
    val setBySuffix: String,                  // "으로 설정됨"
    val growingRegion: String,                 // "재배 지역"
    val locationWaitError: String,            // "위치 설정을 기다려 주세요"
    val locationFallback: String,             // "위치를 찾지 못해 기본 지역(서울)을 사용합니다"
    val locationWeatherFailPrefix: String,    // "위치/날씨 자동 설정 실패: "
    val accountFail: String,                   // "계정 생성 실패 (서버 주소 확인!)"
    val autoLocationNotice: String,           // "위치와 날씨는 자동으로 설정됩니다"
    val errorLabel: String                     // "오류"
)

val koreanStrings = AppStrings(
    // 공통
    appName = "OOJOO FARM",
    back = "뒤로",
    close = "닫기",
    online = "🟢 온라인",
    offline = "⚪ 오프라인",
    done = "완료",
    applyComplete = "적용 완료",
    submit = "등록",
    save = "저장",

    // Bottom nav
    home = "홈",
    plants = "식물",
    farmers = "Farmer",
    market = "마켓",
    community = "이웃",

    // Home
    myFarm = "🚜 나의 농장",
    plantHealth = "📋 식물 건강 상태",
    noPlants = "등록된 식물이 없습니다.",
    noPlantsEmpty = "등록된 식물이 없어요!",
    noFarmerAssigned = "담당 Farmer 없음",
    noInfo = "정보없음",
    refresh = "🔄 새로고침",
    locationChecking = "위치 확인 중",
    locationAutoSet = "위치 자동 설정",
    weatherNightRain = "밤 🌙 🌧️ 비 옴",
    weatherNight = "밤 🌙 맑음",
    weatherDayRain = "낮 ☀️ 🌧️ 비 옴",
    weatherDay = "낮 ☀️ 맑음",

    // Settings / Theme editor
    settings = "설정",
    uiCustomize = "UI 커스터마이징",
    language = "언어",
    languageSystem = "시스템 설정",
    languageKorean = "한국어",
    languageEnglish = "English",
    reset = "기본값으로 초기화",
    preview = "미리보기",
    previewDesc = "아래 슬라이더를 조절하면 즉시 이 카드의 둥글기, 그림자 크기, 테두리 두께가 변합니다!",
    uiDetailSettings = "UI 상세 설정",
    cornerRadiusLabel = "모서리 둥글기 (Radius)",
    shadowLabel = "그림자 크기 (Shadow)",
    borderWidthLabel = "테두리 두께 (Border)",
    serverAddress = "서버 주소",
    serverSection = "서버 설정",
    serverApply = "서버 주소 적용",
    serverApplied = "✅ 적용됨: ",
    serverAppliedSuffix = " (앱 재시작 시에도 유지됩니다)",
    restartNotice = "앱 재시작 후 적용됩니다",

    // Plant list / detail / registration
    myPlants = "🌱 내 식물",
    register = "등록",
    delete = "삭제",
    cancel = "취소",
    plantRegister = "식물 등록",
    registerSuccess = "🌱 식물이 등록되었습니다!",
    selectedCrop = "선택된 작물",
    cropInputPrompt = "작물 종류를 선택/입력하세요",
    plantName = "식물 이름",
    plantNameRequired = "식물 이름 *",
    plantNamePh = "예: 방울토마토",
    species = "작물 종류",
    speciesPh = "예: 토마토, 바질",
    speciesUnspecified = "작물 미지정",
    plantedDate = "식재일",
    plantedDateOptional = "식재일 (선택)",
    plantedDatePh = "YYYY-MM-DD (비워도 됨)",
    growthStage = "생장 단계",
    growthStageSeedling = "묘목",
    growthStageVegetative = "영양생장",
    growthStageFlowering = "개화",
    growthStageFruiting = "결실",
    selectFarmer = "담당 Farmer",
    selectLater = "선택 안함 (나중에 연결)",
    selectNone = "선택 안함",
    farmerOnlineBadge = "(온라인)",
    farmerOfflineBadge = "(오프라인)",
    assignFarmer = "＋ Farmer 배정",
    assignFarmerTitle = "🤖 Farmer 배정",
    selectFarmerPrompt = "에 배정할 Farmer를 선택하세요",
    noSlaves = "연결된 Farmer가 없습니다.\nFarmer 페이지에서 먼저 페어링하세요.",
    unassign = "배정 해제",
    deletePlantTitle = "🗑️ 식물 삭제",
    deletePlantConfirm = "을(를) 삭제합니다.\n관련 이벤트/관수 기록도 함께 삭제됩니다.",
    plantDetail = "식물 상세",
    plantNotFound = "식물을 찾을 수 없습니다",
    typeLabel = "종류",
    datePlantedLabel = "식재일",
    stageLabel = "단계",
    farmerLabel = "Farmer",
    unknown = "미상",
    unconnected = "미연결",
    quickWater = "빠른 관수 (원격 지시)",
    healthInfo = "건강 정보 요약",
    overallStatus = "종합 상태",
    waterNeed = "수분 필요",
    waterNeedYes = "예 (관수 필요)",
    waterNeedNo = "아니오 (적정)",
    pestSuspect = "해충 의심",
    found = "발견됨!",
    safe = "안전",
    healthScore = "건강 점수",
    waterHistory = "관수 이력",
    noWaterHistory = "관수 기록 없음",
    autoMode = "자율",
    manualMode = "수동",
    recentEvents = "최근 이벤트",
    noEvents = "이벤트 없음",
    notiHarvestReady = "🍅 수확 적기",
    notiPestDetected = "🐛 해충 감지",
    notiAutoWater = "💧 자율 관수",
    notiManualWater = "💧 수동 관수",
    notiAnomaly = "⚠️ 이상 징후",
    notiCapture = "📷 캡처",

    // Farmer list / pairing
    farmerManage = "🤖 Farmer 관리",
    gallery = "📷 사진첩",
    subscription = "⭐ 구독",
    connectFarmer = "🤝 Farmer 연결",
    pairingTitle = "Farmer 페어링",
    pairingDesc = "마스터에서 만든 코드를\nFarmer 앱에 입력하세요!",
    accountLabel = "계정",
    generatePairCode = "🔑 페어링 코드 생성",
    pairingCode = "페어링 코드",
    validLabel = "유효",
    pairingInstructions = "유효시간 10분 · 1회용 — 이 코드를 Farmer 앱에 입력!",
    noFarmersEmpty = "연결된 Farmer가 없어요!",
    pairingTip = "＋ 버튼으로 페어링해요!",
    lastComm = "마지막 통신",
    pause = "⏸ 정지",
    resumeAction = "▶ 재개",
    fanControl = "🌀 Fan",
    laserControl = "🔦 Laser",
    viewCamera = "📹 카메라 보기 (3초)",
    reportBtn = "📊 리포트",
    deleteFarmerTitle = "🗑️ Farmer 삭제",
    deleteFarmerConfirm = "을(를) 삭제합니다.\n페어링이 해제되고 목록에서 사라집니다.",

    // Notification
    notificationCenter = "🔔 알림 센터",
    noNotifications = "알림이 없어요!",

    // Market
    marketTitle = "마켓",
    searchProducts = "상품 검색 (비료, 토마토, ESP32…)",
    recommendTitle = "내 식물 맞춤 추천",
    bundleKits = "추천 번들 키트",
    contains = "구성:",
    addBundle = "번들 담기",
    products = "상품",
    searchResultsPrefix = "\"",
    searchResultsSuffix = "\" 검색결과",
    affiliate = "제휴",
    selfHost = "자체",
    viewOrders = "주문 내역 보기 ›",
    productTitle = "상품",
    stock = "재고",
    buyAtAffiliate = "제휴몰에서 구매하기 ↗",
    affiliateNotice = "외부 제휴 상품 (CPS/CPA)",
    addToCart = "장바구니 담기",
    addToCartDone = "장바구니에 담았습니다",
    directBuy = "바로 구매",
    cart = "장바구니",
    emptyCart = "장바구니가 비어 있습니다",
    total = "합계",
    checkout = "결제하기",
    ordersNav = "주문 내역",
    noOrders = "주문 내역이 없습니다",
    orderPrefix = "주문 ",
    rating = "⭐",
    vendor = " · ",

    // Community
    neighbor = "이웃",
    writePost = "＋ 글쓰기",
    searchCrop = "작물/제목 검색",
    all = "전체",
    share = "나눔",
    sell = "판매",
    buy = "구입",
    noPosts = "지역에 아직 글이 없어요.\n첫 글을 올려보세요!",
    noPostsPrefix = "",
    noPostsSuffix = " 지역에 아직 글이 없어요.\n첫 글을 올려보세요!",
    postTitle = "게시물",
    typeLabelGeneric = "유형",
    titleField = "제목 *",
    titlePh = "예: 상추 나눔해요",
    cropLabel = "작물",
    cropPh = "예: 상추, 토마토",
    quantity = "수량",
    quantityPh = "예: 한 봉지, 1kg",
    priceWon = "가격(원)",
    description = "설명",
    descriptionPh = "자세한 내용",
    emoji = "대표 이모지",
    postButton = "게시하기",
    regionLabel = "지역",
    reserved = "예약중",
    tradedone = "거래완료",
    available = "거래 가능",
    reportAction = "신고",
    blockAction = "차단",
    comments = "댓글",
    addComment = "댓글 달기",
    cropInfoLabel = "작물:",
    quantityInfoLabel = "수량:",

    // Report
    farmerReportTitle = "Farmer 리포트 (7일)",
    waterSummary = "관수 요약",
    totalWaterCount = "총 관수 횟수",
    totalWaterAmount = "총 급수량",
    autoVsManual = "자율 / 수동",
    lastWater = "마지막 관수",
    eventSummary = "이벤트 요약",
    harvestReadyReport = "🍅 수확 적기 감지",
    pestDetectedReport = "🐛 해충 감지",
    anomaliesReport = "⚠️ 이상 징후",
    timesUnit = "회",
    reportNotice = "최근 7일 기준 집계입니다.",

    // Subscription
    subscriptionPlan = "구독 플랜",
    currentPlanPrefix = "현재 플랜: ",
    free = "무료",
    perMonth = "/월",
    unlimited = "무제한",
    farmerRegistrationPrefix = "• Farmer 등록: ",
    farmerRegistrationUnit = "대",
    detailedReport = "상세 리포트",
    priorityCs = "우선 CS",
    provided = "제공",
    notProvided = "미제공",
    inUse = "이용 중",
    freeConvert = "무료 전환",
    subscribe = "구독하기",
    paymentDemoNotice = "결제는 데모(시뮬레이션)입니다.",

    // Gallery
    backToList = "목록으로",
    emptyGallery = "저장된 영상이 없어요!",
    galleryTip = "Farmer 카메라로 촬영하면 여기에 저장됩니다.",
    savedVideosPrefix = "🎬 저장된 영상 (",
    savedVideosSuffix = "개)",
    video3sec = "📹 3초 영상",

    // Live camera
    liveCameraPrefix = "📹 ",
    liveCameraSuffix = " 카메라",
    statusWaiting = "요청 대기",
    captureSending = "캡처 요청 전송 중…",
    captureSentWaiting = "캡처 요청 전송됨 — 응답 대기 중",
    captureTimeout = "시간 초과 — Farmer가 오프라인이거나 카메라 미준비일 수 있습니다",
    captureDone = "영상 수신 완료!",
    captureFailed = "요청 실패",
    savingToDevice = "기기에 저장 중…",
    saveCompleteMsg = "✅ 기기에 저장됨!",
    saveFailedPrefix = "저장 실패: ",
    videoCapturedPrefix = "🎬 3초 영상 (촬영: ",
    videoCapturedSuffix = ")",
    justNow = "방금",
    recapture = "🔄 다시 촬영",

    // Onboarding
    startApp = "🚀 시작하기!",
    hello = "안녕하세요!",
    welcomeSubtitle = "누구나 집에서 키우는\n재미있는 스마트 농장",
    nickname = "닉네임",
    nicknamePh = "예: 농부민준",
    locationAuto = "재배 지역 (자동)",
    locationDetecting = "위치·날씨 자동 설정 중…",
    locationRetry = "다시 감지",
    seoulFallback = "서울",
    sourceGps = "GPS",
    sourceNetwork = "네트워크 위치",
    sourceIp = "IP 기반 위치",
    sourceAuto = "자동",
    setByPrefix = "",
    setBySuffix = "으로 설정됨",
    growingRegion = "재배 지역",
    locationWaitError = "위치 설정을 기다려 주세요",
    locationFallback = "위치를 찾지 못해 기본 지역(서울)을 사용합니다",
    locationWeatherFailPrefix = "위치/날씨 자동 설정 실패: ",
    accountFail = "계정 생성 실패 (서버 주소 확인!)",
    autoLocationNotice = "위치와 날씨는 자동으로 설정됩니다",
    errorLabel = "오류"
)

val englishStrings = AppStrings(
    // 공통
    appName = "OOJOO FARM",
    back = "Back",
    close = "Close",
    online = "🟢 Online",
    offline = "⚪ Offline",
    done = "Done",
    applyComplete = "Apply",
    submit = "Submit",
    save = "Save",

    // Bottom nav
    home = "Home",
    plants = "Plants",
    farmers = "Farmer",
    market = "Market",
    community = "Neighbors",

    // Home
    myFarm = "🚜 My Farm",
    plantHealth = "📋 Plant Health",
    noPlants = "No plants registered.",
    noPlantsEmpty = "No plants yet!",
    noFarmerAssigned = "No Farmer assigned",
    noInfo = "No info",
    refresh = "🔄 Refresh",
    locationChecking = "Locating…",
    locationAutoSet = "Auto location",
    weatherNightRain = "Night 🌙 🌧️ Rain",
    weatherNight = "Night 🌙 Clear",
    weatherDayRain = "Day ☀️ 🌧️ Rain",
    weatherDay = "Day ☀️ Clear",

    // Settings / Theme editor
    settings = "Settings",
    uiCustomize = "UI Customize",
    language = "Language",
    languageSystem = "System",
    languageKorean = "한국어",
    languageEnglish = "English",
    reset = "Reset to defaults",
    preview = "Preview",
    previewDesc = "Drag the sliders below to instantly change this card's corner radius, shadow size, and border width!",
    uiDetailSettings = "UI Details",
    cornerRadiusLabel = "Corner radius (Radius)",
    shadowLabel = "Shadow size (Shadow)",
    borderWidthLabel = "Border width (Border)",
    serverAddress = "Server address",
    serverSection = "Server",
    serverApply = "Apply server URL",
    serverApplied = "✅ Applied: ",
    serverAppliedSuffix = " (persists across restarts)",
    restartNotice = "Takes effect after app restart",

    // Plant list / detail / registration
    myPlants = "🌱 My Plants",
    register = "Register",
    delete = "Delete",
    cancel = "Cancel",
    plantRegister = "Register Plant",
    registerSuccess = "🌱 Plant registered!",
    selectedCrop = "Selected crop",
    cropInputPrompt = "Pick or type a crop",
    plantName = "Plant name",
    plantNameRequired = "Plant name *",
    plantNamePh = "e.g. Cherry tomato",
    species = "Crop type",
    speciesPh = "e.g. Tomato, Basil",
    speciesUnspecified = "Crop not set",
    plantedDate = "Planted date",
    plantedDateOptional = "Planted date (optional)",
    plantedDatePh = "YYYY-MM-DD (optional)",
    growthStage = "Growth stage",
    growthStageSeedling = "Seedling",
    growthStageVegetative = "Vegetative",
    growthStageFlowering = "Flowering",
    growthStageFruiting = "Fruiting",
    selectFarmer = "Farmer",
    selectLater = "None (connect later)",
    selectNone = "None",
    farmerOnlineBadge = "(Online)",
    farmerOfflineBadge = "(Offline)",
    assignFarmer = "＋ Assign Farmer",
    assignFarmerTitle = "🤖 Assign Farmer",
    selectFarmerPrompt = ": choose a Farmer",
    noSlaves = "No paired Farmers.\nPair one in the Farmers tab first.",
    unassign = "Unassign",
    deletePlantTitle = "🗑️ Delete plant",
    deletePlantConfirm = " will be deleted.\nWatering history & events will also be removed.",
    plantDetail = "Plant detail",
    plantNotFound = "Plant not found",
    typeLabel = "Type",
    datePlantedLabel = "Planted",
    stageLabel = "Stage",
    farmerLabel = "Farmer",
    unknown = "Unknown",
    unconnected = "Not connected",
    quickWater = "Quick Water (remote)",
    healthInfo = "Health summary",
    overallStatus = "Overall",
    waterNeed = "Water needed",
    waterNeedYes = "Yes (water me)",
    waterNeedNo = "No (fine)",
    pestSuspect = "Pest suspected",
    found = "Found!",
    safe = "Safe",
    healthScore = "Health score",
    waterHistory = "Watering log",
    noWaterHistory = "No watering yet",
    autoMode = "Auto",
    manualMode = "Manual",
    recentEvents = "Recent events",
    noEvents = "No events",
    notiHarvestReady = "🍅 Harvest ready",
    notiPestDetected = "🐛 Pest detected",
    notiAutoWater = "💧 Auto water",
    notiManualWater = "💧 Manual water",
    notiAnomaly = "⚠️ Anomaly",
    notiCapture = "📷 Capture",

    // Farmer list / pairing
    farmerManage = "🤖 Manage Farmers",
    gallery = "📷 Gallery",
    subscription = "⭐ Subscribe",
    connectFarmer = "🤝 Connect Farmer",
    pairingTitle = "Farmer pairing",
    pairingDesc = "Enter the code from Master\ninto the Farmer app!",
    accountLabel = "Account",
    generatePairCode = "🔑 Generate pairing code",
    pairingCode = "Pairing code",
    validLabel = "Valid",
    pairingInstructions = "10-minute, one-time code — type it in the Farmer app!",
    noFarmersEmpty = "No Farmers yet!",
    pairingTip = "Tap ＋ to pair one!",
    lastComm = "Last seen",
    pause = "⏸ Pause",
    resumeAction = "▶ Resume",
    fanControl = "🌀 Fan",
    laserControl = "🔦 Laser",
    viewCamera = "📹 View camera (3s)",
    reportBtn = "📊 Report",
    deleteFarmerTitle = "🗑️ Delete Farmer",
    deleteFarmerConfirm = " will be deleted.\nPairing is removed and it disappears from the list.",

    // Notification
    notificationCenter = "🔔 Notifications",
    noNotifications = "No notifications!",

    // Market
    marketTitle = "Market",
    searchProducts = "Search (fertilizer, tomato, ESP32…)",
    recommendTitle = "Recommended for my plants",
    bundleKits = "Recommended bundles",
    contains = "Includes:",
    addBundle = "Add bundle",
    products = "Products",
    searchResultsPrefix = "\"",
    searchResultsSuffix = "\" results",
    affiliate = "Affiliate",
    selfHost = "In-house",
    viewOrders = "View orders ›",
    productTitle = "Product",
    stock = "Stock",
    buyAtAffiliate = "Buy at affiliate ↗",
    affiliateNotice = "External affiliate (CPS/CPA)",
    addToCart = "Add to cart",
    addToCartDone = "Added to cart",
    directBuy = "Buy now",
    cart = "Cart",
    emptyCart = "Your cart is empty",
    total = "Total",
    checkout = "Checkout",
    ordersNav = "Orders",
    noOrders = "No orders yet",
    orderPrefix = "Order ",
    rating = "⭐",
    vendor = " · ",

    // Community
    neighbor = "Neighbors",
    writePost = "＋ Write",
    searchCrop = "Search crop/title",
    all = "All",
    share = "Share",
    sell = "Sell",
    buy = "Buy",
    noPosts = "No posts yet.\nBe the first to post!",
    noPostsPrefix = "",
    noPostsSuffix = " — no posts yet.\nBe the first to post!",
    postTitle = "Post",
    typeLabelGeneric = "Type",
    titleField = "Title *",
    titlePh = "e.g. Free lettuce",
    cropLabel = "Crop",
    cropPh = "e.g. Lettuce, tomato",
    quantity = "Quantity",
    quantityPh = "e.g. 1 bag, 1 kg",
    priceWon = "Price (₩)",
    description = "Description",
    descriptionPh = "More details",
    emoji = "Emoji",
    postButton = "Post",
    regionLabel = "Region",
    reserved = "Reserved",
    tradedone = "Done",
    available = "Available",
    reportAction = "Report",
    blockAction = "Block",
    comments = "Comments",
    addComment = "Add a comment",
    cropInfoLabel = "Crop:",
    quantityInfoLabel = "Qty:",

    // Report
    farmerReportTitle = "Farmer report (7 days)",
    waterSummary = "Watering summary",
    totalWaterCount = "Total waterings",
    totalWaterAmount = "Total water",
    autoVsManual = "Auto / Manual",
    lastWater = "Last watering",
    eventSummary = "Event summary",
    harvestReadyReport = "🍅 Harvest-ready",
    pestDetectedReport = "🐛 Pest detected",
    anomaliesReport = "⚠️ Anomalies",
    timesUnit = "×",
    reportNotice = " Aggregated over the last 7 days.",

    // Subscription
    subscriptionPlan = "Subscription plan",
    currentPlanPrefix = "Current plan: ",
    free = "Free",
    perMonth = "/mo",
    unlimited = "Unlimited",
    farmerRegistrationPrefix = "• Farmers: ",
    farmerRegistrationUnit = "",
    detailedReport = "Detailed report",
    priorityCs = "Priority CS",
    provided = "Yes",
    notProvided = "No",
    inUse = "Current",
    freeConvert = "Switch to free",
    subscribe = "Subscribe",
    paymentDemoNotice = "Payments are a demo (simulated).",

    // Gallery
    backToList = "List",
    emptyGallery = "No saved videos!",
    galleryTip = "Videos from the Farmer camera are saved here.",
    savedVideosPrefix = "🎬 Saved videos (",
    savedVideosSuffix = ")",
    video3sec = "📹 3s video",

    // Live camera
    liveCameraPrefix = "📹 ",
    liveCameraSuffix = " camera",
    statusWaiting = "Waiting",
    captureSending = "Sending capture request…",
    captureSentWaiting = "Capture requested — waiting for response",
    captureTimeout = "Timed out — Farmer may be offline or camera not ready",
    captureDone = "Video received!",
    captureFailed = "Request failed",
    savingToDevice = "Saving to device…",
    saveCompleteMsg = "✅ Saved to device!",
    saveFailedPrefix = "Save failed: ",
    videoCapturedPrefix = "🎬 3s video (shot: ",
    videoCapturedSuffix = ")",
    justNow = "just now",
    recapture = "🔄 Capture again",

    // Onboarding
    startApp = "🚀 Get Started!",
    hello = "Hello!",
    welcomeSubtitle = "Grow your own smart farm\nat home",
    nickname = "Nickname",
    nicknamePh = "e.g. Farmer Min-jun",
    locationAuto = "Growing region (auto)",
    locationDetecting = "Detecting location & weather…",
    locationRetry = "Retry",
    seoulFallback = "Seoul",
    sourceGps = "GPS",
    sourceNetwork = "Network location",
    sourceIp = "IP-based location",
    sourceAuto = "Auto",
    setByPrefix = "",
    setBySuffix = " (auto-set)",
    growingRegion = "Growing region",
    locationWaitError = "Please wait for location detection",
    locationFallback = "Location not found; using Seoul as default.",
    locationWeatherFailPrefix = "Auto location/weather failed: ",
    accountFail = "Account creation failed (check server URL!)",
    autoLocationNotice = "Location and weather are set automatically.",
    errorLabel = "Error"
)

val LocalAppStrings = compositionLocalOf { koreanStrings }

@Composable
fun AppStringsProvider(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // appLanguageState.value 를 읽어 Compose 가 상태 변화를 관찰하도록 함.
    // null 이면 시스템/Prefs 기반으로 결정 (초기 진입 또는 SYSTEM 모드).
    val lang = AppLocale.appLanguageState.value ?: AppLocale.resolve(ctx)
    val strings = if (lang == AppLocale.KOREAN) koreanStrings else englishStrings
    CompositionLocalProvider(LocalAppStrings provides strings, content = content)
}