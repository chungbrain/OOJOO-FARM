// 경계 입력 검증 도메인 도우미 — 라우터가 호출 진입점에서 사용.
// 내부 로직은 이 검증이 이미 지난 값을 전제한다.

// slave가 실제로 처리하는 명령 action (FarmerEngine.handleSSECommand 분기).
// 이 집합 밖 action을 큐에 넣으면 slave는 무시하지만 행은 영원히 남는다.
export const COMMAND_ACTIONS = new Set([
  'water', 'pause', 'resume', 'fan', 'laser',
  'capture_photo', 'capture_video', 'generate_growth_clip', 'diagnose_video',
  'live_start', 'live_stop',
]);

// 관수 명령/로그 공통 수치 범위.
// amountMl: 밸브 60s 캡에 비례하는 리터 수 — 1..30000 ml (30 L) 초과면 의도가 아니다.
// weatherFactor: wateringFactor.js 산출 범위(약 0.12..1.5)를 넉넉히 0..3.
export function validCommandNumbers(amountMl, weatherFactor) {
  if (!Number.isFinite(amountMl) || amountMl <= 0 || amountMl > 30000) {
    return 'amountMl must be a number in 1..30000 (ml)';
  }
  if (!Number.isFinite(weatherFactor) || weatherFactor <= 0 || weatherFactor > 3) {
    return 'weatherFactor must be a number in 0..3';
  }
  return null;
}

// 이벤트 type: 오픈 집합(날씨 등 신규 유형 추가 예정)이라 길만 제한.
export function validEventType(type) {
  return typeof type === 'string' && type.length > 0 && type.length <= 50;
}

// 커뮤니티 게시물 좌표 범위. null/누락은 허용 (지역 피드만 사용).
export function validCoord(lat, lon) {
  if (lat != null) {
    const v = Number(lat);
    if (!Number.isFinite(v) || v < -90 || v > 90) return 'lat must be a number in -90..90';
  }
  if (lon != null) {
    const v = Number(lon);
    if (!Number.isFinite(v) || v < -180 || v > 180) return 'lon must be a number in -180..180';
  }
  return null;
}
