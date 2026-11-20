/* =========================================================
   예시 데이터 (USE_MOCK = true 일 때 사용)
   - 실제 가격 · 쿠폰이 아닌 프로토타입용 가정값입니다.
   - 구조는 API_SPEC.md 의 응답 형식과 같습니다.
   ========================================================= */
window.GP = window.GP || {};

GP.mock = {
  exchangeRates: {
    base: 'KRW',
    source: '한국수출입은행 (예시)',
    baseDate: '2026-11-10',
    updatedAt: '2026-11-05T11:00:00+09:00',
    rates: { USD: 1400, JPY: 9.5, EUR: 1510 } // 1 단위당 원화 (JPY는 1엔당) — DB 시드 exchange_rates 와 같은 값
  },

  categories: ['패션 · 신발', '디지털 · 가전', '뷰티 · 향수', '건강 · 영양제', '생활 · 주방', '스포츠 · 캠핑', '완구 · 키즈'],

  hotKeywords: ['러닝화', '무선 헤드폰', '비타민 D', '캠핑 의자', '로봇청소기', '패딩 점퍼', '전동 드릴', '레고'],

  // 일정 · 혜택: 2025년 11월 보도 기준 예시
  events: [
    { id: 'ali-1111', storeId: 'aliexpress', storeName: 'AliExpress', name: '광군제', mark: 'AE', start: '2026-11-11', end: '2026-11-19', benefit: '행사 할인 + 스토어 쿠폰, 무료배송 상품 다수', tag: '중국 직구' },
    { id: 'gm-bigsmile', storeId: 'gmarket', storeName: 'G마켓 해외직구', name: '빅스마일데이', mark: 'G', start: '2026-11-01', end: '2026-11-11', benefit: '매일 할인 쿠폰팩 · 판매자 할인, 원화 결제', tag: '관부가세 포함가 상품' },
    { id: '11st-grand', storeId: '11st-amazon', storeName: '11번가 아마존 글로벌 스토어', name: '그랜드 십일절', mark: '11', start: '2026-11-01', end: '2026-11-11', benefit: '할인 쿠폰 · 우주패스 회원 무료 해외배송', tag: '아마존 상품 원화 결제' },
    // DB 03_seed_data.sql 의 store_events 2건 (할인 규칙까지 같음)
    { id: 'amz-test', storeId: 'amazon-us', storeName: 'Amazon US', name: 'Amazon 테스트 할인', mark: 'A', start: '2026-11-11', end: '2026-12-31', benefit: '10% 할인 · $100 이상 · 최대 $50 (테스트 쿠폰)', tag: '시드 데이터',
      eventType: 'COUPON', discountType: 'RATE', discountValue: 0.10, minPurchaseAmount: 100, maxDiscountAmount: 50 },
    { id: 'rak-test', storeId: 'rakuten', storeName: 'Rakuten Japan', name: 'Rakuten 테스트 할인', mark: 'R', start: '2026-11-11', end: '2026-12-31', benefit: '5% 할인 · ¥10,000 이상 · 최대 ¥2,000 (테스트 쿠폰)', tag: '시드 데이터',
      eventType: 'COUPON', discountType: 'RATE', discountValue: 0.05, minPurchaseAmount: 10000, maxDiscountAmount: 2000 },
    { id: 'amz-bf', storeId: 'amazon-us', storeName: 'Amazon US', name: '블랙프라이데이', mark: 'A', start: null, end: null, benefit: '타임딜 · 카테고리 할인 (배송비 · 관부가세 별도)', tag: '미국 $200 초과 시 과세' }
  ],

  stores: {
    'aliexpress': { name: 'AliExpress', country: 'CN', countryName: '중국' },
    'gmarket': { name: 'G마켓 해외직구', country: 'KR', countryName: '원화 결제' },
    '11st-amazon': { name: '11번가 아마존 글로벌', country: 'US', countryName: '원화 결제' },
    'amazon-us': { name: 'Amazon US', country: 'US', countryName: '미국' },
    'ebay': { name: 'eBay', country: 'US', countryName: '미국' },
    'rakuten': { name: 'Rakuten Japan', country: 'JP', countryName: '일본' }
  },

  /* offer 필드
     eventId: store_events 의 행사를 적용 (할인 규칙은 이벤트에 있음)
     coupon: { label, amount, eventId } — 판매처별 정액 쿠폰 (간단 예시용, AMOUNT 할인으로 계산)
     variantName · stockStatus: mall_product_variants (IN_STOCK · OUT_OF_STOCK · UNKNOWN)
     status: 'ok'(기본) · 'stale'(갱신 실패, 저장값 표시 — staleHours 시간 전) · 'unavailable'(저장값 없음 → 조회 불가)
       → 상품 1번에 eBay 'stale', Amazon US 'unavailable' 예시를 넣어둠
     price · shipping · coupon.amount 는 currency 단위
     taxIncluded: 판매가에 관부가세 포함 (국내 직구관)
     originCountry: 면세 기준 판단용 (US = $200, 그 외 $150 · 물품가격 기준, 국제배송비 제외) */
  products: [
    {
      id: 1, name: '[예시] 러닝화 · US 9 / 블랙', brand: '[브랜드]', model: '[MODEL-NO]', category: 'shoes', categoryName: '패션 · 신발',
      spec: '로드 러닝 · 남성용 · 메시 갑피', description: '[예시] 쿠션감 있는 데일리 러닝화예요. 판매처마다 사이즈 · 색상 재고와 배송 기간이 달라요.', rating: 4.6, reviewCount: 1280, popularity: 98, lowIn30d: true,
      offers: [
        { storeId: 'aliexpress', currency: 'USD', price: 96.00, shipping: 0, coupon: { label: '광군제 쿠폰', amount: 10, eventId: 'ali-1111' }, taxIncluded: false, originCountry: 'CN', deliveryDays: [10, 20], url: '#' },
        { storeId: 'gmarket', currency: 'KRW', price: 168000, shipping: 0, coupon: { label: '빅스마일 쿠폰', amount: 10000, eventId: 'gm-bigsmile' }, taxIncluded: true, originCountry: 'US', deliveryDays: [7, 14], url: '#' },
        { storeId: '11st-amazon', currency: 'KRW', price: 172900, shipping: 0, shippingNote: '우주패스 무료', coupon: { label: '십일절 쿠폰', amount: 12000, eventId: '11st-grand' }, taxIncluded: false, originCountry: 'US', deliveryDays: [6, 10], url: '#' },
        { storeId: 'ebay', status: 'stale', staleHours: 3, currency: 'USD', price: 109.99, shipping: 18.00, coupon: null, taxIncluded: false, originCountry: 'US', deliveryDays: [7, 12], url: '#' },
        { storeId: 'amazon-us', status: 'unavailable', currency: 'USD', price: 119.00, shipping: 12.50, coupon: null, taxIncluded: false, originCountry: 'US', deliveryDays: [5, 9], url: '#' }
      ]
    },
    {
      id: 2, name: '[예시] 트레일 러닝화 · US 9', brand: '[브랜드]', model: '[MODEL-NO]', category: 'shoes', categoryName: '패션 · 신발',
      spec: '트레일 · 남녀공용 · 방수 갑피', description: '[예시] 비 오는 날 트레일에도 쓰기 좋은 방수 러닝화예요. 해외 판매처 가격 차이가 큰 편이에요.', rating: 4.4, reviewCount: 640, popularity: 84, lowIn30d: false,
      offers: [
        { storeId: 'aliexpress', currency: 'USD', price: 88.00, shipping: 0, coupon: { label: '광군제 쿠폰', amount: 9, eventId: 'ali-1111' }, taxIncluded: false, originCountry: 'CN', deliveryDays: [10, 20], url: '#' },
        { storeId: 'gmarket', currency: 'KRW', price: 149000, shipping: 0, coupon: { label: '빅스마일 쿠폰', amount: 10000, eventId: 'gm-bigsmile' }, taxIncluded: true, originCountry: 'US', deliveryDays: [7, 14], url: '#' },
        { storeId: 'amazon-us', currency: 'USD', price: 99.00, shipping: 9.99, coupon: null, taxIncluded: false, originCountry: 'US', deliveryDays: [5, 9], url: '#' }
      ]
    },
    {
      id: 3, name: '[예시] 쿠셔닝 러닝화 · US 9', brand: '[브랜드]', model: '[MODEL-NO]', category: 'shoes', categoryName: '패션 · 신발',
      spec: '로드 러닝 · 여성용 · 고쿠션 미드솔', description: '[예시] 장거리용 고쿠션 러닝화예요. 행사 기간에 쿠폰 할인이 자주 붙어요.', rating: 4.8, reviewCount: 410, popularity: 77, lowIn30d: true,
      offers: [
        { storeId: '11st-amazon', currency: 'KRW', price: 161500, shipping: 0, shippingNote: '우주패스 무료', coupon: { label: '십일절 쿠폰', amount: 12000, eventId: '11st-grand' }, taxIncluded: false, originCountry: 'US', deliveryDays: [6, 10], url: '#' },
        { storeId: 'ebay', currency: 'USD', price: 104.00, shipping: 8.50, coupon: null, taxIncluded: false, originCountry: 'US', deliveryDays: [7, 12], url: '#' },
        { storeId: 'gmarket', currency: 'KRW', price: 172000, shipping: 0, coupon: { label: '빅스마일 쿠폰', amount: 10000, eventId: 'gm-bigsmile' }, taxIncluded: true, originCountry: 'US', deliveryDays: [7, 14], url: '#' }
      ]
    },
    {
      id: 4, name: '[예시] 러닝 양말 3팩', brand: '[브랜드]', model: '[MODEL-NO]', category: 'clothing', categoryName: '패션 · 신발',
      spec: '쿨맥스 소재 · 발목 길이 · 프리사이즈', description: '[예시] 땀 배출이 좋은 기능성 양말 묶음이에요. 묶음 수량을 맞춰서 비교해요.', rating: 4.3, reviewCount: 2210, popularity: 70, lowIn30d: false,
      offers: [
        { storeId: 'aliexpress', currency: 'USD', price: 8.60, shipping: 0, coupon: { label: '광군제 쿠폰', amount: 1.5, eventId: 'ali-1111' }, taxIncluded: false, originCountry: 'CN', deliveryDays: [10, 20], url: '#' },
        { storeId: '11st-amazon', currency: 'KRW', price: 16900, shipping: 0, shippingNote: '우주패스 무료', coupon: { label: '십일절 쿠폰', amount: 2000, eventId: '11st-grand' }, taxIncluded: false, originCountry: 'US', deliveryDays: [6, 10], url: '#' },
        { storeId: 'amazon-us', currency: 'USD', price: 12.99, shipping: 4.99, coupon: null, taxIncluded: false, originCountry: 'US', deliveryDays: [5, 9], url: '#' }
      ]
    },
    {
      id: 5, name: '[예시] 무선 노이즈캔슬링 헤드폰', brand: '[브랜드]', model: '[MODEL-NO]', category: 'electronics', categoryName: '디지털 · 가전',
      spec: '블루투스 · 노이즈캔슬링 · 최대 [N]시간 재생', description: '[예시] 노이즈캔슬링 무선 헤드폰이에요. 전자기기라 관세율이 신발 · 의류와 달라요.', rating: 4.7, reviewCount: 3050, popularity: 92, lowIn30d: false,
      offers: [
        { storeId: 'gmarket', currency: 'KRW', price: 459000, shipping: 0, coupon: { label: '빅스마일 쿠폰', amount: 10000, eventId: 'gm-bigsmile' }, taxIncluded: true, originCountry: 'US', deliveryDays: [7, 14], url: '#' },
        { storeId: 'rakuten', currency: 'JPY', price: 39800, shipping: 1500, coupon: null, taxIncluded: false, originCountry: 'JP', deliveryDays: [4, 7], url: '#' },
        { storeId: 'ebay', currency: 'USD', price: 259.00, shipping: 20.00, coupon: null, taxIncluded: false, originCountry: 'US', deliveryDays: [7, 12], url: '#' },
        { storeId: 'amazon-us', currency: 'USD', price: 279.00, shipping: 15.00, coupon: null, taxIncluded: false, originCountry: 'US', deliveryDays: [5, 9], url: '#' }
      ]
    },
    // ---- DB 03_seed_data.sql 과 같은 테스트 상품 (가격 · 배송비 · 행사 · 환율 동일) ----
    //   백엔드 PriceCalculator 결과를 이 값과 비교해 검증할 수 있음 (db/04_fix_seed_calculations.sql)
    {
      id: 6, name: 'iPhone 17 · 256GB', brand: 'Apple', model: 'IPHONE17-TEST', category: 'smartphone', categoryName: '스마트폰',
      spec: '256GB', description: '[시드 데이터] DB 테스트 데이터와 같은 상품이에요. 실제 상품 · 가격이 아니에요.', rating: null, reviewCount: null, popularity: 60, lowIn30d: false,
      offers: [
        { storeId: 'amazon-us', variantName: '256GB', stockStatus: 'IN_STOCK', currency: 'USD', price: 799.00, shipping: 15.00, eventId: 'amz-test', taxIncluded: false, originCountry: 'US', url: 'https://www.amazon.com/s?k=iPhone+17' },
        { storeId: 'rakuten', variantName: '256GB', stockStatus: 'IN_STOCK', currency: 'JPY', price: 119800, shipping: 1200, eventId: 'rak-test', taxIncluded: false, originCountry: 'JP', url: 'https://search.rakuten.co.jp/search/mall/iPhone+17/' }
      ]
    },
    {
      id: 7, name: 'Galaxy S25 · 256GB', brand: 'Samsung', model: 'GALAXYS25-TEST', category: 'smartphone', categoryName: '스마트폰',
      spec: '256GB', description: '[시드 데이터] DB 테스트 데이터와 같은 상품이에요. 실제 상품 · 가격이 아니에요.', rating: null, reviewCount: null, popularity: 55, lowIn30d: false,
      offers: [
        { storeId: 'amazon-us', variantName: '256GB', stockStatus: 'IN_STOCK', currency: 'USD', price: 749.00, shipping: 12.00, eventId: 'amz-test', taxIncluded: false, originCountry: 'US', url: 'https://www.amazon.com/s?k=Galaxy+S25' }
      ]
    }
  ]
};
