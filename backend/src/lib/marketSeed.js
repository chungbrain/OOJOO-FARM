// 마켓 시드 데이터. 최초 기동 시 products/bundles 가 비어 있으면 삽입한다.
// vendor: 'self'(자체 상품, 결제) 또는 외부 제휴몰명(affiliate_url 로 이동, CPS/CPA).
// image 는 이모지(프로토타입/에뮬레이터에서 이미지 대체).

export const CATEGORIES = [
  { key: 'fertilizer', label: '비료·배양토' },
  { key: 'seed', label: '씨앗·모종' },
  { key: 'watering', label: '급수·밸브·펌프' },
  { key: 'pest', label: '해충 퇴치' },
  { key: 'controller', label: '제어(ESP32)' },
  { key: 'lighting', label: '조명' },
  { key: 'pot', label: '화분·틀' },
  { key: 'meter', label: '계측기' },
  { key: 'phone', label: 'Farmer용 폰' },
];

export const PRODUCTS = [
  // 비료·배양토
  { id: 'prd_fert_01', category: 'fertilizer', name: '유기농 채소 비료 2kg', description: '채소·과채용 완효성 유기질 비료. 결실기 영양 보충에 적합.', price: 12900, vendor: 'self', image: '🌿', affiliate_url: null, stock: 120, rating: 4.7, tags: 'fruiting,vegetative,vegetable' },
  { id: 'prd_fert_02', category: 'fertilizer', name: '프리미엄 배양토 20L', description: '배수·보습 균형 상토. 파종·모종 이식용.', price: 15900, vendor: 'self', image: '🪴', affiliate_url: null, stock: 80, rating: 4.6, tags: 'seedling,soil' },
  { id: 'prd_fert_03', category: 'fertilizer', name: '액상 칼슘·마그네슘', description: '토마토 배꼽썩음병 예방 보충제.', price: 9900, vendor: 'GreenMart', image: '🧪', affiliate_url: 'https://example.com/aff/calmag', stock: 999, rating: 4.4, tags: 'tomato,fruiting' },

  // 씨앗·모종
  { id: 'prd_seed_01', category: 'seed', name: '방울토마토 씨앗 30립', description: '베란다 재배에 적합한 왜성 품종.', price: 3900, vendor: 'self', image: '🍅', affiliate_url: null, stock: 300, rating: 4.8, tags: 'tomato,seed' },
  { id: 'prd_seed_02', category: 'seed', name: '바질 모종 3포트', description: '허브 초보용 튼튼한 모종.', price: 6900, vendor: 'self', image: '🌱', affiliate_url: null, stock: 150, rating: 4.5, tags: 'basil,herb,seedling' },
  { id: 'prd_seed_03', category: 'seed', name: '상추 혼합 씨앗', description: '적·청상추 혼합. 사계절 재배.', price: 2900, vendor: 'self', image: '🥬', affiliate_url: null, stock: 400, rating: 4.6, tags: 'lettuce,seed' },

  // 급수·밸브·펌프
  { id: 'prd_water_01', category: 'watering', name: '12V 솔레노이드 밸브', description: '자동 관수용 전자 밸브. ESP32 릴레이 연동.', price: 8900, vendor: 'self', image: '🚰', affiliate_url: null, stock: 90, rating: 4.5, tags: 'valve,watering,hardware' },
  { id: 'prd_water_02', category: 'watering', name: '소형 워터펌프 세트', description: '탱크→화분 급수용 12V 펌프 + 호스.', price: 13900, vendor: 'self', image: '💧', affiliate_url: null, stock: 70, rating: 4.4, tags: 'pump,watering,hardware' },
  { id: 'prd_water_03', category: 'watering', name: '급수 탱크 10L', description: '자동 관수 저수조.', price: 11900, vendor: 'AquaShop', image: '🛢️', affiliate_url: 'https://example.com/aff/tank', stock: 999, rating: 4.3, tags: 'tank,watering' },

  // 해충 퇴치
  { id: 'prd_pest_01', category: 'pest', name: '퇴치용 DC 팬 모듈', description: '곤충 접근 시 자동 가동. Fan 제어 대응.', price: 7900, vendor: 'self', image: '🌀', affiliate_url: null, stock: 60, rating: 4.2, tags: 'fan,pest,hardware' },
  { id: 'prd_pest_02', category: 'pest', name: '저출력 레이저 모듈(Class1)', description: '안전 규격 퇴치용 레이저 + 서보. 마스터 승인 제어.', price: 24900, vendor: 'self', image: '🔦', affiliate_url: null, stock: 25, rating: 4.1, tags: 'laser,pest,hardware' },

  // 제어(ESP32)
  { id: 'prd_ctrl_01', category: 'controller', name: 'ESP32 컨트롤러 보드', description: 'BLE+WiFi. 밸브/팬/레이저 릴레이 제어 허브.', price: 9900, vendor: 'self', image: '🔌', affiliate_url: null, stock: 200, rating: 4.8, tags: 'esp32,controller,hardware,ble' },
  { id: 'prd_ctrl_02', category: 'controller', name: '4채널 릴레이 모듈', description: 'ESP32용 다채널 릴레이.', price: 5900, vendor: 'self', image: '🎛️', affiliate_url: null, stock: 180, rating: 4.6, tags: 'relay,controller,hardware' },

  // 조명
  { id: 'prd_light_01', category: 'lighting', name: 'LED 식물 성장등 20W', description: '풀스펙트럼 실내 재배 조명.', price: 18900, vendor: 'BrightGrow', image: '💡', affiliate_url: 'https://example.com/aff/led', stock: 999, rating: 4.5, tags: 'light,indoor' },

  // 화분·틀
  { id: 'prd_pot_01', category: 'pot', name: '자동관수 화분 3개', description: '저면관수 화분 세트.', price: 14900, vendor: 'self', image: '🪟', affiliate_url: null, stock: 110, rating: 4.4, tags: 'pot' },

  // 계측기
  { id: 'prd_meter_01', category: 'meter', name: '토양 수분·온도 센서', description: '아날로그 토양 수분 + 온도 측정.', price: 4900, vendor: 'self', image: '📟', affiliate_url: null, stock: 140, rating: 4.3, tags: 'sensor,meter,hardware' },

  // Farmer용 폰
  { id: 'prd_phone_01', category: 'phone', name: '중고 갤럭시 (Farmer용)', description: 'minSdk24 호환 정비 완료 중고폰. 슬레이브 전용 추천.', price: 59000, vendor: 'ReusePhone', image: '📱', affiliate_url: 'https://example.com/aff/phone', stock: 999, rating: 4.2, tags: 'phone,slave,recommended' },
];

export const BUNDLES = [
  { id: 'bdl_starter', name: '초보 자동재배 스타터 키트', description: 'ESP32 + 밸브 + 펌프 + 배양토 + 방울토마토 씨앗', price: 39900, image: '📦', product_ids: 'prd_ctrl_01,prd_water_01,prd_water_02,prd_fert_02,prd_seed_01' },
  { id: 'bdl_pest', name: '해충 대응 키트', description: '팬 + 레이저 + 릴레이 모듈', price: 34900, image: '🛡️', product_ids: 'prd_pest_01,prd_pest_02,prd_ctrl_02' },
  { id: 'bdl_farmer', name: 'Farmer 기기 번들', description: '중고폰 + ESP32 + 토양센서', price: 69900, image: '🤖', product_ids: 'prd_phone_01,prd_ctrl_01,prd_meter_01' },
];

export function seedMarket(db) {
  const count = db.prepare('SELECT COUNT(*) AS n FROM products').get().n;
  if (count > 0) return;
  const insP = db.prepare(`INSERT INTO products(id, category, name, description, price, vendor, image, affiliate_url, stock, rating, tags)
    VALUES(?,?,?,?,?,?,?,?,?,?,?)`);
  for (const p of PRODUCTS) {
    insP.run(p.id, p.category, p.name, p.description, p.price, p.vendor, p.image, p.affiliate_url, p.stock, p.rating, p.tags);
  }
  const insB = db.prepare('INSERT INTO bundles(id, name, description, price, image, product_ids) VALUES(?,?,?,?,?,?)');
  for (const b of BUNDLES) {
    insB.run(b.id, b.name, b.description, b.price, b.image, b.product_ids);
  }
}
