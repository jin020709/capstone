/* =========================================================
   설정 — 백엔드 연결 시 여기만 바꾸면 됩니다.
   ========================================================= */
window.GP = window.GP || {};

GP.config = {
  // true: js/mock-data.js 예시 데이터 사용 (백엔드 없이 동작)
  // false: Spring Boot API 호출 (BACKEND_SPEC.md 6장 참고)
  USE_MOCK: false,

  // Spring Boot 서버 주소. 같은 서버(static/)에서 서비스하면 '' 그대로 두면 됨
  API_BASE: '',

  // 요청 제한 시간(ms) — 외부 API 지연 대응 (비기능 요구: 안정성 · 응답 속도)
  TIMEOUT_MS: 8000,

  // 검색 결과 한 페이지 개수 (백엔드 size: 1~50)
  PAGE_SIZE: 20,

  // 검색 필터의 판매처 목록 (실제 서버 모드). id 는 백엔드 stores 파라미터 값 = MallSource.storeKey()
  //   목업 모드는 mock-data.js 의 stores 를 그대로 씀. 쇼핑몰을 켜고 끄면 여기만 맞추면 됨
  STORES: [
    { id: 'ebay', name: 'eBay' },
    { id: 'shopify', name: 'Shopify' },
    { id: 'aliexpress', name: 'AliExpress' },
    { id: 'etsy', name: 'Etsy' }
  ],

  // 기능 켜고 끄기 — 최종 발표 범위 기준
  FEATURES: {
    alerts: true // 가격 하락 알림: 교수님 피드백으로 필수 기능. 끄면 메뉴 · 버튼이 숨겨짐
  },

  // 판매처 가격이 이 시간보다 오래되면 '○시간 전 가격'으로 표시 (백엔드가 status 를 안 줄 때)
  //   DB: price_calculations.calculated_at 기준, 갱신 주기에 맞춰 조정
  STALE_HOURS: 6,

  // 예상 구매비용 계산 기준 (목업 · 홈 계산기 전용. 실제 값은 백엔드 PriceCalculator 가 계산)
  //   2026-11-12 관세청 안내 기준으로 확인 — 자세한 근거는 '관세·면세·수수료 기준 조사' 보고서
  PRICE_RULES: {
    vatRate: 0.10,                 // 부가세 10%
    cardFeeRate: 0.01,             // 해외결제 수수료 1% (외화 결제 시만) — DB fee_rules '해외 결제 수수료'. 실제는 브랜드 1~1.1% + 카드사 0.25% 정도라 팀 확정 후 조정
    fixedFeeKrw: 0,                // 고정 수수료 (DB fee_rules.fixed_fee_krw)
    minTaxKrw: 10000,              // 관세 + 부가세 합계가 1만원 미만이면 징수 안 함 (관세법 제40조)
    dutyFreeUSD: { US: 200, DEFAULT: 150 }, // 면세 기준: 물품가격(국제배송비 제외) 기준. 미국 $200은 목록통관 물품만
    dutyRates: {                   // 품목별 기본 관세율 (FTA · HS코드에 따라 달라질 수 있음)
      shoes: 0.13,                 // 신발 13%
      clothing: 0.13,              // 의류 13%
      smartphone: 0,               // 휴대폰 0% (정보기술협정 ITA)
      laptop: 0,                   // 노트북 · 태블릿 0% (ITA)
      electronics: 0.08,           // 헤드폰 · 이어폰 · 카메라 · 가전 8%
      beauty: 0.08,                // 화장품 · 향수 8% (60ml 넘는 향수는 개별소비세 별도)
      supplements: 0.08,           // 건강기능식품 8% (목록통관 안 됨 → 일반 수입신고)
      general: 0.08,
      books: 0
    }
  }
};
