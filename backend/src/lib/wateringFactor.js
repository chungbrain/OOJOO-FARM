// 관수량 가중치 계산: 기준량 × 온도가중치 × 습도가중치 × 강수보정
// PRD 4.4 알고리즘. 순수 함수로 분리해 테스트 가능하게 함.
export function computeWateringFactor(temp, humidity, precipitation) {
  let factor = 1.0;
  if (temp != null) {
    if (temp >= 28) factor *= 1.2 + Math.min(0.3, (temp - 28) * 0.05);
    else if (temp <= 15) factor *= 0.6 + Math.max(-0.2, (15 - temp) * 0.04);
  }
  if (humidity != null && humidity >= 70) factor *= 0.7;
  if (precipitation != null && precipitation > 0) factor *= 0.3;
  return Math.round(factor * 100) / 100;
}
