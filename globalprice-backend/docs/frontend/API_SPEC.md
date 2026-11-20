# GlobalPrice 프론트 ↔ 백엔드 API 명세 (초안)

프론트엔드 `js/api.js`가 호출하는 REST API 형식입니다.
`js/config.js`에서 `USE_MOCK: false`로 바꾸면 아래 주소로 요청합니다.
지금은 같은 형식의 예시 데이터(`js/mock-data.js`)로 동작합니다.

- Base URL: `API_BASE` (같은 Spring Boot 서버의 `static/`에서 서비스하면 `''`)
- 형식: JSON, UTF-8
- 금액: 원화는 정수(원), 외화는 소수
- 인증: **세션 방식** (백엔드 1주차 결정). 로그인 성공 시 서버가 `JSESSIONID` 쿠키를 내려주고, 프론트는 `credentials: 'same-origin'`으로 요청해 쿠키가 자동으로 같이 가요. 토큰 · `Authorization` 헤더는 쓰지 않아요.
  - 로그인 상태는 페이지가 열릴 때 `GET /api/auth/me`로 확인해요 (6번 참고). 브라우저의 `gp_user`는 화면 표시용 캐시일 뿐이에요.
- 오류: HTTP 상태코드 + `{ "status": 404, "code": "PRODUCT_NOT_FOUND", "message": "사용자에게 보여줄 문구", "timestamp": "..." }` (백엔드 `ErrorResponse`)
  - 프론트는 `code`로 화면을 나누고 `message`를 보여줘요 (`js/common.js`의 `ERRORS`). `code`가 없으면 상태코드로 판단해요.
  - `code` 이름은 백엔드 `ErrorCode` enum과 같아요. 예전 이름(`UNAUTHORIZED`, `EXTERNAL_UNAVAILABLE`)이 와도 같은 화면이 나와요.

| code | HTTP | 화면 |
|---|---|---|
| INVALID_INPUT | 400 | 입력값 확인 안내 |
| LOGIN_REQUIRED | 401 | "로그인이 필요해요" + 로그인 버튼 |
| LOGIN_FAILED | 401 | 로그인 폼 아래에 message 표시 |
| FORBIDDEN | 403 | 검색 버튼 |
| NOT_FOUND | 404 | "페이지를 찾을 수 없어요" + 검색 버튼 |
| PRODUCT_NOT_FOUND | 404 | "상품을 찾을 수 없어요" + 검색 버튼 |
| METHOD_NOT_ALLOWED | 405 | 안내만 |
| DUPLICATE_EMAIL | 409 | 가입 폼에 안내 |
| EXTERNAL_INVALID_RESPONSE | 502 | 다시 시도 버튼 (**백엔드 ErrorCode에 추가 요청**: 외부 API가 응답은 했는데 형식이 틀린 경우) |
| EXTERNAL_API_UNAVAILABLE | 503 | 다시 시도 버튼 |
| INTERNAL_ERROR | 500 | 다시 시도 버튼 |
- 프론트 요청 제한 시간: 8초 (`TIMEOUT_MS`). 넘으면 "응답이 늦어요" 안내

> **예상 구매비용은 백엔드에서 계산해서 내려주는 걸 권장합니다** (정확성 · 일관성).
> 계산 규칙은 `js/price.js`와 같게 맞추면 됩니다.
> 예상 구매비용 = (판매가 − 쿠폰) + 배송비 + 관세 · 부가세(면세 기준 초과 시) + 해외결제 수수료(외화 결제 시)

---

## 1. 환율 — `GET /api/exchange-rates`
DB: **환율**

```json
{
  "base": "KRW",
  "source": "한국수출입은행",
  "baseDate": "2026-11-10",
  "updatedAt": "2026-11-05T11:00:00+09:00",
  "rates": { "USD": 1400, "JPY": 9.5, "EUR": 1510 }
}
```
- `JPY`는 1엔당 원화입니다. 화면에는 100엔 기준으로 표시돼요.
- 외부 환율 API가 실패하면 마지막 저장값과 그때의 `updatedAt`을 내려주세요. 화면에 기준 시각이 표시됩니다.

## 2. 홈 — `GET /api/home`
```json
{
  "categories": ["패션 · 신발", "디지털 · 가전"],
  "hotKeywords": ["러닝화", "무선 헤드폰"],
  "events": [ { "id": "ali-1111", "storeId": "aliexpress", "storeName": "AliExpress", "name": "광군제", "mark": "AE",
                "start": "2026-11-11", "end": "2026-11-19", "benefit": "행사 할인 + 스토어 쿠폰", "tag": "중국 직구" } ],
  "popular": [ ProductSummary ]
}
```
- `events`는 `store_events`에서 진행 중 · 예정인 행사를 보내요. `start` · `end` ← `start_at` · `end_at`, `benefit` ← `description`, `id` ← `store_event_id`, `name` ← `event_name`. `mark` · `tag`는 화면용이라 없어도 돼요.
- 일정이 미정인 행사는 `start`와 `end`를 `null`로 보내세요. 화면에 "[일정 확인]"으로 나와요.

## 3. 상품 검색 — `GET /api/products`
DB: **상품, 쇼핑몰, 상품가격, 배송비, 관세율, 수수료율**

| 쿼리 | 예시 | 설명 |
|---|---|---|
| `q` | `러닝화` | 상품명 · 브랜드 · 모델명 |
| `sort` | `total` · `popular` · `rating` · `discount` | 예상 구매비용 · 인기 · 평점 · 할인율 |
| `coupon` | `true` / `false` | 쿠폰 · 행사 적용가 기준 여부 |
| `stores` | `aliexpress,ebay` | 판매처 ID (쉼표 구분) |
| `countries` | `US,CN,JP,KRW` | 구매 국가 (KRW = 국내 직구관 원화 결제) |
| `benefits` | `event,freeShip,taxIncluded` | 행사 진행 중 · 무료배송 · 관부가세 포함가 |

```json
{ "query": "러닝화", "total": 4, "ratesUpdatedAt": "2026-11-05T11:00:00+09:00", "items": [ ProductSummary ] }
```

**ProductSummary**
```json
{
  "id": 1, "name": "러닝화 · US 9 / 블랙", "spec": "로드 러닝 · 남성용", "categoryName": "패션 · 신발",
  "rating": 4.6, "reviewCount": 1280, "popularity": 98,
  "badges": ["행사 진행 중", "최근 30일 최저"],
  "offerCount": 5, "bestTotal": 120735, "bestNormalTotal": 134774, "bestStore": "AliExpress",
  "offers": [ Offer ]
}
```
- `offers`는 예상 구매비용이 낮은 순으로 상위 3개만 보내면 돼요.

## 4. 상품 상세 — `GET /api/products/{id}?coupon=true`
```json
{
  "id": 1, "name": "...", "brand": "...", "model": "...", "spec": "...", "description": "상품 설명 1~2문장", "categoryName": "...",
  "rating": 4.6, "reviewCount": 1280, "badges": [],
  "bestTotal": 120735, "bestNormalTotal": 134774, "bestStore": "AliExpress",
  "offers": [ Offer ],
  "events": [ Event ],
  "rates": { "source": "...", "updatedAt": "...", "rates": { "USD": 1400, "JPY": 9.5, "EUR": 1510 } }
}
```

- 상세 화면 상단의 "사이트별 가격 한눈에" 그래프는 `offers[].calc`로 그려요. 따로 API는 필요 없어요.

**Offer** (판매처 옵션 1건 = `mall_product_variants` 1행 + 최신 `price_calculations` 1행)
```json
{
  "mallProductId": 1, "mallVariantId": 1,
  "storeId": "amazon-us", "storeName": "Amazon US", "countryName": "미국", "countryCode": "US",
  "variantName": "256GB", "stockStatus": "IN_STOCK",
  "status": "ok", "calculatedAt": "2026-11-12T09:00:00+09:00",
  "currency": "USD", "price": 799.00, "shipping": 15.00, "url": "https://www.amazon.com/s?k=iPhone+17",
  "appliedEvent": {
    "storeEventId": 1, "eventName": "Amazon 테스트 할인", "eventType": "COUPON",
    "discountType": "RATE", "discountValue": 0.10, "maxDiscountAmount": 50.00, "discountAmount": 50.00
  },
  "taxIncluded": false,
  "calc": {
    "productPriceKrw": 1118600, "shippingFeeKrw": 21000, "discountAmountKrw": 70000,
    "customsDutyKrw": 0, "vatKrw": 106960, "paymentFeeKrw": 10696, "totalAmountKrw": 1187256,
    "taxed": true, "exemptionUsd": 200
  },
  "total": 1187256, "totalNormal": 1264956
}
```
위 값은 DB 시드(iPhone 17 · Amazon US)를 계산 규칙대로 계산한 값이에요. `db/04_fix_seed_calculations.sql`과 같아요.

| 필드 | DB | 비고 |
|---|---|---|
| `mallProductId` · `mallVariantId` · `variantName` | `mall_product_variants` | |
| `stockStatus` | `mall_product_variants.stock_status` | `OUT_OF_STOCK`이면 "품절" 표시, 최저가에서 빠짐 |
| `currency` · `shipping` · `url` | `mall_products.currency_code` · `shipping_fee` · `product_url` | 배송비는 상품별 값 > `shipping_rules` |
| `price` | `mall_product_variants.current_price` | 쇼핑몰 통화 |
| `storeName` · `countryCode` | `shopping_malls.mall_name` · `country_code` | `storeId`는 화면용 키. `mall_id`를 문자열로 보내도 됨 |
| `calc.*Krw` | `price_calculations` 같은 이름 컬럼 | camelCase로만 바꿈 |
| `calc.exemptionUsd` | `tax_rules.exemption_amount_usd` | `taxed`는 면세 판정 결과 |
| `calculatedAt` | `price_calculations.calculated_at` | |
| `appliedEvent` | `price_calculations.applied_event_id` → `store_events` | 할인 없으면 `null`. `discountAmount`는 쇼핑몰 통화 할인액 |
| `total` | `price_calculations.total_amount_krw` | `calc.totalAmountKrw`와 같은 값 |
| `totalNormal` | (계산) | 할인 없이 계산한 합계. 검색의 취소선 가격용. 없으면 생략 가능 |
| `title` | `mall_products.listing_title` | 쇼핑몰에 올라온 원래 상품명. 같은 상품으로 묶이면 상품 이름(`name`)은 대표 이름, 판매처 줄은 이 값 |
| `seller` | (계산) | 상품 링크 도메인 (예: `techdealz.com`). Shopify처럼 한 플랫폼에 상점이 여러 곳일 때만, 아니면 `null` |

- `status`: 판매처 가격 상태 (백엔드 대체값 3단계)
  - `"ok"`: 이번 갱신 성공
  - `"stale"`: 갱신 실패 → 저장된 값. 화면에 `calculatedAt` 기준 "3시간 전 가격"으로 표시
  - `"unavailable"`: 저장값도 없음(`price_calculations` 행 없음) → `price` · `calc` · `total`을 `null`로. 화면에 "일시적으로 조회 불가"로 표시하고 비교 · 최저가에서 빠져요
  - DB에 상태 컬럼이 없으니(`calculated_at` 차이로 판단) **`status`를 안 보내도 돼요.** 그러면 프론트가 `calculatedAt`이 `STALE_HOURS`(6시간)보다 오래되면 stale, `total`이 없으면 unavailable로 판단해요.
  - `offers`는 `total` 낮은 순으로 보내 주세요. 프론트도 비교 대상 → 품절 → 조회 불가 순으로 다시 정렬해요.
- 국내 직구관(원화 결제)은 `currency: "KRW"`로 보내면 수수료가 0원이에요.

### 화면에는 있는데 DB에 없는 값 (팀 확인 필요)
| 화면 | 필드 | 지금 처리 |
|---|---|---|
| 검색 · 상세 평점, 리뷰 수 · 평점순 정렬 | `rating` · `reviewCount` | `null`이면 화면에서 숨김. 평점순 정렬을 유지할지, 컬럼을 추가할지 정해야 함 |
| 인기순 정렬 | `popularity` | DB 컬럼 없음. `wishlists` 수로 계산하는 방법 제안 |
| 배송 기간 "7~12일" | `deliveryDays` | `null`이면 숨김 |
| 국내 직구관 "관부가세 포함가" | `taxIncluded` | DB 컬럼 없음. 쇼핑몰 단위면 `shopping_malls`에 컬럼 추가 제안 |
| "최근 30일 최저" 배지 | `badges` | `price_history`로 계산 가능 |
| 배송비 안내 "우주패스 무료" | `shippingNote` | 없으면 생략 |

## 5. 가격 변동 이력 — `GET /api/products/{id}/price-history?period=6m`
DB: **가격변동 이력**. `period`: `1m` · `3m` · `6m` · `1y`
```json
{ "productId": 1, "period": "6m",
  "points": [ { "date": "2026-05-14", "lowestWithCoupon": 150000, "lowest": 158000 } ] }
```

## 6. 회원 — DB: **회원**
| 메서드 | 주소 | 요청 | 응답 |
|---|---|---|---|
| POST | `/api/auth/signup` | `{ email, nickname, password }` | `{ email, nickname }` · 중복이면 409 |
| POST | `/api/auth/login` | `{ email, password }` | `{ email, nickname }` + `Set-Cookie: JSESSIONID` · 실패 401 `LOGIN_FAILED` |
| GET | `/api/auth/me` | — | 로그인 중이면 `{ email, nickname }`, 아니면 401 `LOGIN_REQUIRED` |
| POST | `/api/auth/logout` | — | 204 (세션 무효화) |

- 비밀번호는 서버에서 BCrypt로 저장 (백엔드 결정과 같음).
- 로그인 성공 시 **세션 ID를 새로 발급**해 주세요 (`request.changeSessionId()` · 세션 고정 공격 방지).
- 로그인 응답은 `{ email, nickname }`과 `{ user: { email, nickname } }` 둘 다 프론트가 받을 수 있어요.
- 4번 상품 상세 응답에 로그인 중이면 `wished: true|false`를 같이 넣어 주면 하트 표시가 정확해져요 (선택).

## 7. 관심 상품(찜) — DB: **찜 목록** (로그인 필요)
| 메서드 | 주소 | 요청 / 응답 |
|---|---|---|
| GET | `/api/wishlist` | `[ ProductSummary ]` |
| POST | `/api/wishlist` | `{ productId }` |
| DELETE | `/api/wishlist/{productId}` | 204 |

## 8. 가격 하락 알림 — DB: **알림** (로그인 필요) · **필수 기능**

> 교수님 피드백으로 필수 기능이 됐어요. `config.js`의 `FEATURES.alerts: true`. 백엔드 계획표의 6단계(선택)에서 앞으로 당겨 주세요.
> 프론트는 지금 상세 화면에서 `TARGET_PRICE`(목표가) 알림만 만들어요.
| 메서드 | 주소 | 요청 / 응답 |
|---|---|---|
| GET | `/api/alerts` | `[ { alertId, alertBasis, productId, productName, storeEventId, targetPriceKrw, isActive, lastNotifiedAt, createdAt } ]` |
| POST | `/api/alerts` | `{ alertBasis, productId, storeEventId, targetPriceKrw }` |
| DELETE | `/api/alerts/{alertId}` | 204 |

- `alertBasis` (DB `price_alerts.alert_basis`): `TARGET_PRICE`(productId + targetPriceKrw) · `PERIOD_LOWEST`(productId) · `EVENT_START` · `COUPON_EXPIRY`(storeEventId). 조합은 DB CHECK와 같아요.
- 같은 회원 · 같은 조건 · 같은 대상은 1건이라, 목표가를 바꾸면 POST가 UPDATE로 동작하게 해 주세요(또는 409).

---

### DB 테이블과 API 대응 (02_create_tables.sql 19개 기준)
| DB | 쓰는 API |
|---|---|
| `users` | 6 |
| `products` · `brands` · `categories` | 3, 4 |
| `shopping_malls` · `mall_products` · `mall_product_variants` | 3, 4 (Offer) |
| `exchange_rates` | 1, 4 (`rates`) |
| `shipping_rules` · `tax_rules` · `fee_rules` | 계산에만 사용 (API로 직접 안 보냄) |
| `price_calculations` | 3, 4 (`calc` · `total`) |
| `store_events` | 2 (`events`), 4 (`appliedEvent` · `events`) |
| `price_history` | 5 |
| `wishlists` | 7 |
| `price_alerts` · `notifications` | 8 (2차) |
| `api_sources` · `api_sync_logs` | 화면 없음 (백엔드 내부) |
