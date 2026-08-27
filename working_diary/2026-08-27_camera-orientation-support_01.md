# 작업 일지 — 2026-08-27

## 카메라 가로/세로 촬영 방향 지원 (Farmer 촬영 모드 따라감)

**요구사항:**
- 카메라 가로모드/세로모드 촬영 모두 지원
- 촬영 방향은 Farmer 기기의 현재 방향(디스플레이 회전)을 따른다

**문제 원인:**
- 카메라가 `CameraHost`(서비스 소유 LifecycleOwner)에 바인딩되어 있어 Activity 디스플레이 회전 정보를 받지 못함
- `ImageCapture`/`VideoCapture`의 `targetRotation`이 설정되지 않아 가로 모드에서 사진/영상이 회전된 채 저장
- `captureFrame()`(ROI 모니터링)이 센서 좌표계 원본 비트맵을 그대로 사용 → upright 프리뷰 위에 그린 ROI 정규화 좌표와 어긋남
- `BitmapFactory.decodeFile`은 EXIF 회전을 무시 → 성장 숏클립에서 가로 사진이 돌아가서 합성됨

**변경 내용:**

| 파일 | 내용 |
|---|---|
| `android/slave/.../vision/OrientationUtil.kt` (신규) | `rotateBitmap(bitmap, degrees)` — upright 회전 (원본 recycle). `decodeUpright(path)` — EXIF 방향 태그 적용 JPEG 디코딩 |
| `android/slave/.../vision/CameraHost.kt` | `DisplayManager.DisplayListener` 등록으로 디스플레이 회전 추적 → Preview/ImageCapture/ImageAnalysis/VideoCapture 전체에 `targetRotation` 실시간 갱신. use case 참조 보관, `attach()` 시에도 회전 동기화, `stop()`에서 리스너 해제 |
| `android/slave/.../vision/CameraHolder.kt` | `captureFrame()` — 캡처 비트맵을 `imageInfo.rotationDegrees`만큼 회전해 upright 반환 (ROI 좌표계 일치) |
| `android/slave/.../vision/CameraPreview.kt` | 대시보드 수동 촬영 분석용 `imageProxyToBitmap`에도 upright 회전 적용 (CNN 입력 방향 일관성) |
| `android/slave/.../service/FarmerEngine.kt` | 즉시 사진 촬영 후 분석을 `OrientationUtil.decodeUpright`로 변경 |
| `android/slave/.../vision/GrowthClipRenderer.kt` | 성장 숏클립 사진 로딩을 `decodeUpright`로 변경 — 가로 사진도 바르게 합성 |
| `dist/oojoo-farm-farmer-debug.apk` | 재빌드 (18.3MB) |

**동작 흐름:**
1. 기기 회전 → `DisplayListener.onDisplayChanged` → 전체 use case `targetRotation` 갱신
2. 사진(JPEG EXIF)/영상(회전 메타데이터)이 현재 방향 기준으로 저장됨
3. ROI 모니터링 프레임 캡처 시 회전 보정 → 프리뷰에서 지정한 ROI 좌표 그대로 크롭
4. Manifest에 `screenOrientation` 고정이 없어 Activity는 이미 자유 회전 — 시스템 자동 회전 설정을 따름 (회전 잠금 시 고정 방향으로 촬영)

**검증:**
- `:slave:app:compileDebugKotlin` ✅ (1차 실패: `start()`의 지역 `val appCtx`가 필드를 가림 → `this.appCtx = appCtx`로 수정)
- `:slave:app:assembleDebug` ✅ → dist APK 갱신

**남은 작업:**
- 실기기 가로/세로 전환 상태에서 사진·영상·ROI 크롭 정상 여부 확인
- ROI 설정 시점과 모니터링 시점의 방향이 다르면 ROI 좌표가 어긋남 (기기 고정 전제 — 방향 변경 시 ROI 재설정 필요)
