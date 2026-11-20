# GlobalPrice 백엔드 명세서 (Claude Code 전달용)

- 버전: v1.0 (2026-11-13) · 이전 초안(v0.x)은 폐기하고 새로 작성
- 스택: Java 25, Spring Boot 4.1.1, Gradle 9.2, MySQL 8.0.x
- 입력 자료: `01_create_database.sql`, `02_create_tables.sql` (DB의 기준. 임의로 고치지 말 것)
- 표기: **[확인]** = 구현 전에 공식 문서를 직접 열어 확인할 것. **[결정]** = 이 문서가 정한 값(팀이 바꾸면 따름)

---

## 0. 작업 방식 (Claude Code에게)

1. 이 문서를 처음부터 끝까지 읽고, 12장의 단계 순서대로 구현한다. 한 단계가 끝날 때마다 빌드와 테스트가 통과해야 다음으로 간다.
2. **외부 API 어댑터는 코드를 쓰기 전에 공식 문서(WebFetch)로 요청 · 응답 형식을 확인**한다. 이 문서의 외부 API 설명은 공개 자료와 팀원의 실제 호출 결과를 정리한 것이라 틀릴 수 있다. 다르면 공식 문서를 따르고 차이를 보고한다.
3. DB 스크립트(`01`, `02`)는 수정하지 않는다. 변경이 필요하면 `03_migration_*.sql`을 새로 만든다 (11장).
4. 프론트엔드는 별도 저장소의 `API_SPEC.md`가 필드 단위 기준이다. 이 문서의 응답 필드와 다르면 **프론트 `API_SPEC.md`에 맞추고** 차이를 보고한다.
5. API 키 · 시크릿은 환경변수로만 받고 저장소에 넣지 않는다. 로그에 Authorization 헤더 · 서명 · 키가 찍히지 않게 한다.

---

## 1. 제품 개요와 1차 범위

**서비스**: 다나와와 비슷한 가격 비교 사이트인데, 대상이 해외 쇼핑몰이다. 사용자가 상품을 검색하면 해외 쇼핑몰 API를 **실시간으로** 조회해서, 배송비 · 할인 · 환율 · 관세 · 수수료까지 반영한 **한국 도착 예상 구매비용(원)** 으로 정렬해 보여준다.

**1차에서 하는 것**
- 검색어 → 쇼핑몰 4곳(eBay, Shopify, AliExpress, Etsy) 동시 조회 → 예상 구매비용 계산 → 가격순 정렬
- 상품 상세, 가격 변동 그래프, 환율 조회
- 회원(세션 로그인), 관심 상품, 가격 알림(웹 안에서 확인)

**1차에서 하지 않는 것**
- **같은 상품 묶기** (쇼핑몰 간 동일 상품 매칭). 단, 2차에 붙일 수 있게 인터페이스만 둔다 (4-4).
- Gemini · ELECTRA 등 AI 사용
- 이메일 · 푸시 알림, 평점 · 인기순 정렬, 카테고리 필터(후순위, 6장)

**1차의 상품 개념**: 상품 1건 = 쇼핑몰 판매 상품 1건. DB에서는 `products` 1행과 `mall_products` 1행이 항상 1:1이다. 2차에 묶기가 들어오면 `products`를 합치는 방식으로 확장한다.

---

## 2. 전체 흐름

### 2-1. 검색 (사용자 요청 안에서 외부 API 호출)

```
GET /api/products?q=아이폰 17
 1. 검색어 검증 (1~50자) · 정규화 · 한영 별칭 변환 (아이폰 → iphone)
 2. 활성 쇼핑몰 소스에 동시 호출 (가상 스레드, 소스별 타임아웃)
      └ 소스별 캐시 확인 (검색어 기준 10분) → 있으면 외부 호출 없음
 3. 1차 걸러내기: 새 상품만, 액세서리 · 부품 제외, 한국 배송 불가 제외
 4. 각 offer마다 계산 (5장): 배송비 → 할인(쿠폰은 DB) → 환율 → 관세 · 부가세 → 수수료
 5. (묶기 없음: ProductGrouper no-op)
 6. 예상 구매비용 오름차순 정렬, 필터, 페이지 자르기
 7. 반환 (소스별 상태 포함)
 * 소스 응답을 받으면 DB에 저장(upsert)하고 productId를 붙인다 (4-3)
```

### 2-2. 스케줄러 (사용자 요청과 무관)

| 작업 | 시각 (Asia/Seoul) | 내용 |
| --- | --- | --- |
| 환율 수집 | 평일 11:30, 15:00 재시도 | 한국수출입은행 API → `exchange_rates` |
| 관심 상품 가격 갱신 | 6시간마다 | 찜 · 알림이 걸린 상품만 쇼핑몰 API로 다시 조회 → 가격 저장 → 알림 판정 |

검색 때 저장된 가격이 있어서 **관심 상품이 아닌 상품은 갱신하지 않는다**. 가격 이력도 검색됐거나 갱신된 상품 기준으로만 쌓인다.

---

## 3. 프로젝트 구조

패키지 루트는 기존 프로젝트 것을 따르고, 없으면 `com.globalprice`.

```
config        RestClient · 캐시 · 스케줄러 · 세션 · 프로퍼티(record)
common        ErrorCode, ApiException, 전역 예외 처리, 시간(Clock) 빈
auth          회원가입 · 로그인 (users)
search        검색 서비스, 별칭 변환, 걸러내기, ProductGrouper
source        MallSource 인터페이스 + ebay / shopify / aliexpress / etsy 구현
rate          한국수출입은행 환율 클라이언트 · 환율 서비스
pricing       PriceCalculator (순수 함수), 규칙 조회 (shipping/tax/fee/events)
catalog       상품 저장(upsert) · 상세 · 가격 이력
wishlist      관심 상품
alert         알림 조건 · 판정 · 받은 알림
scheduler     환율 · 관심 상품 갱신 작업
web           @RestController
```

- 계층: Controller → Service → Repository. `source`와 `rate`만 외부 HTTP를 호출한다.
- `pricing.PriceCalculator`는 DB · 시간 · 네트워크에 의존하지 않는 순수 함수로 만든다 (입력은 값, 출력은 값).

### 기술 선택

| 항목 | 선택 |
| --- | --- |
| 웹 | Spring Web MVC + Bean Validation |
| 외부 호출 | `RestClient` (동기). 가상 스레드에서 병렬 실행 |
| 동시 호출 | `Executors.newVirtualThreadPerTaskExecutor()` + `Future.get(timeout)`. `StructuredTaskScope`는 Java 25에서도 미리보기라 쓰지 않는다 |
| 캐시 | Spring Cache + Caffeine (소스별 검색 결과, 10분, 최대 500건) |
| 영속성 | Spring Data JPA, `ddl-auto: none`, `open-in-view: false`. 대량 upsert는 `JdbcTemplate` 허용 |
| 로그인 | `HttpSession` + `spring-security-crypto`(BCrypt). Spring Security 전체는 쓰지 않는다 |
| JSON | Boot 4 기본 Jackson 사용 **[확인: 패키지명 · 설정 방식]** |
| 테스트 | JUnit 5, MockWebServer(또는 WireMock), Testcontainers MySQL 8 |

Boot 4.1.1의 스타터 이름(웹은 `spring-boot-starter-webmvc` 등)과 Jackson 버전은 프로젝트를 만들 때 **[확인]**. `spring.threads.virtual.enabled=true`.

---

## 4. 외부 쇼핑몰 연동

### 4-1. 공통 인터페이스

```java
public interface MallSource {
    String sourceName();                              // api_sources.source_name
    boolean enabled();
    List<MallOffer> search(String query, SearchOptions options);   // 검색
    List<MallOffer> refresh(List<String> externalProductIds);      // 저장된 상품 가격 갱신
}

public record MallOffer(
    String externalProductId,          // mall_products.external_product_id
    String externalVariantId,          // 옵션 구분이 없으면 "DEFAULT"
    String title,
    String variantName,                // 예: 256GB (모르면 null)
    BigDecimal price,                  // 쇼핑몰 통화 기준, 소수 그대로
    String currencyCode,               // USD, JPY, EUR ...
    BigDecimal shippingFee,            // 한국 배송비를 응답으로 받으면 값, 모르면 null
    String condition,                  // NEW만 통과
    String originCountry,              // 모르면 null → shopping_malls.country_code 사용
    String productUrl,                 // 가능하면 제휴 링크 (AliExpress)
    String imageUrl,
    StockStatus stock,                 // IN_STOCK / OUT_OF_STOCK / UNKNOWN
    Integer shipDays,                  // 알면 값 (동률 정렬용)
    String gtin) {}                    // 알면 값 (2차 묶기용, 1차는 저장만 하지 않아도 됨)
```

- 소스 실패는 `SourceException`(sealed: `RateLimited`, `AuthFailed`, `Timeout`, `BadResponse`)으로 올린다. 소스 하나가 실패해도 다른 소스 결과는 그대로 쓴다.
- 새 쇼핑몰 = `MallSource` 구현체 1개 + `api_sources` 1행 + `shopping_malls` 1행.

### 4-2. 소스별 명세

#### eBay Browse API (먼저 구현)

| 항목 | 내용 |
| --- | --- |
| 검색 | `GET https://api.ebay.com/buy/browse/v1/item_summary/search?q={q}&filter=deliveryCountry:KR,conditions:{NEW}&sort=price&limit=20` |
| 헤더 | `Authorization: Bearer {앱 토큰}`, `X-EBAY-C-MARKETPLACE-ID: EBAY_US`, `X-EBAY-C-ENDUSERCTX: contextualLocation=country=KR` |
| 인증 | OAuth client credentials. 토큰은 메모리에 두고 만료 전에 갱신 **[확인: 토큰 URL · scope]** |
| 갱신 | 단건 `getItem`으로 갱신 **[확인]** |
| 매핑 | `itemId`→externalProductId, `price.value`/`price.currency`, `shippingOptions[].shippingCost`, `itemWebUrl`, `image.imageUrl`, `itemLocation.country`, `condition` |
| 한도 | 기본 하루 5,000회. 개발 중에는 샌드박스(`api.sandbox.ebay.com`) 사용. 운영 키는 eBay 승인 필요 |
| 비고 | 검색 결과에는 용량 · 색상 옵션이 구분되지 않을 수 있다 → 옵션은 `DEFAULT` 1건으로 저장 |

#### Shopify (Global Catalog MCP)

팀원이 아래 호출로 응답을 확인했다. 개별 상점이 아니라 Shopify 전역 카탈로그를 한 번에 검색한다.

```
POST https://catalog.shopify.com/api/ucp/mcp
Accept: application/json, text/event-stream
Content-Type: application/json

{"jsonrpc":"2.0","method":"tools/call","id":1,
 "params":{"name":"search_catalog","arguments":{
   "meta":{"ucp-agent":{"profile":"https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json"}},
   "catalog":{"query":"wireless headphones"}}}}
```

| 항목 | 내용 |
| --- | --- |
| 인증 | 키 없이 호출됨. 에이전트 프로필 URL은 `application.yml` 설정값으로 둔다. 위 URL이 운영에서도 허용되는지 **[확인]** |
| 응답 형식 | JSON 또는 `text/event-stream`(SSE) 둘 다 올 수 있으므로 둘 다 파싱 |
| 도구 | `search_catalog`(검색), `lookup_catalog`(ID 목록 조회, 최대 50개), `get_product`(상세) **[확인]** |
| 필터 | `catalog.filters`의 `price`(최소 통화 단위 정수), `condition`(`new`), 판매자 출발국 등 **[확인: 정확한 키]** |
| 가격 | **최소 통화 단위 정수** (USD 12345 = 123.45). 통화별 소수 자릿수 반영해 환산 (JPY는 0자리) |
| 응답 구조 | 상품 아래 여러 판매자의 offer. 1차는 offer 하나를 `MallOffer` 하나로 펼쳐서 쓴다 (묶지 않음) |
| 쇼핑몰 행 | 판매자(shop) 단위 `shopping_malls` 자동 생성은 하지 않고, 1차는 `Shopify` 1행으로 통일 **[결정]**. `external_product_id`는 판매자 구분이 필요하면 `{shop}:{id}` 형식으로 만든다 |
| 주의 | 기존 Global Catalog REST API는 2026-11-02 종료. 쓰지 않는다 |

#### AliExpress Affiliate API

| 항목 | 내용 |
| --- | --- |
| 엔드포인트 | `https://api-sg.aliexpress.com/sync` + `method` 파라미터 **[확인: 경로]** |
| 메서드 | 검색 `aliexpress.affiliate.product.query`, 상세 `aliexpress.affiliate.productdetail.get`(`product_ids` 콤마 구분) |
| 공통 파라미터 | `app_key`, `method`, `timestamp`, `sign_method`, `format=json`, `v=2.0`, `sign` |
| 서명 | 모든 파라미터를 key 오름차순으로 `key+value` 이어 붙여 HMAC(app_secret) 후 대문자 16진수. SHA256 우선, 안 되면 MD5 **[확인]**. 서명 계산은 단위 테스트로 검증 |
| 검색 파라미터 | `keywords`, `page_no`, `page_size`, `tracking_id`(필수), `target_currency`, `target_language`, `ship_to_country=KR`, `sort` **[확인: ship_to_country 지원]** |
| 응답 | `aliexpress_affiliate_product_query_response.resp_result` → `resp_code == 200` 확인 → `result.products[]` |
| 매핑 | `product_id`, `target_sale_price`, `target_original_price`, `product_detail_url`·`promotion_link`(구매 링크는 `promotion_link`), `product_main_image_url`, `ship_to_days` |
| 비고 | 배송비 없음 → `shipping_rules` 사용. 옵션 없음 → `DEFAULT`. 제휴 승인 필요 |

#### Etsy Open API v3 (마지막, 범위 재검토 대상)

| 항목 | 내용 |
| --- | --- |
| 검색 | `GET https://openapi.etsy.com/v3/application/listings/active?keywords={q}&limit=20&sort_on=price&sort_order=asc` |
| 인증 | 공개 조회는 `x-api-key` 헤더만. OAuth 불필요 |
| 가격 | `price = {amount, divisor, currency_code}` → `amount ÷ divisor` |
| 배송비 | 응답에 한국 배송비 없음 → `shipping_rules` |
| 한도 | 자료마다 다름(초당 5~10, 하루 5,000~10,000) → 보수적으로 초당 5회로 시작 **[확인]** |
| 위험 | 수공예 · 빈티지 중심이라 전자기기 신품 비교와 맞지 않을 수 있다. 구현은 하되 `globalprice.sources.etsy.enabled=false`로 시작하고, 결과를 본 뒤 켤지 정한다 |

### 4-3. 저장 규칙 (검색 결과 → DB)

소스 응답을 받으면 (캐시 미스일 때만) 아래처럼 저장하고, 각 offer에 `productId`를 붙인다.

1. `mall_products`를 `(mall_id, external_product_id)`로 조회. 없으면 `products`(`product_name`=제목, `image_url`, 나머지 null) 1행과 `mall_products` 1행을 만든다.
2. `mall_product_variants`를 `(mall_product_id, external_variant_id)`로 upsert (`current_price`, `stock_status`). `mall_products.last_fetched_at` 갱신, 통화 · 배송비 · URL 갱신.
3. `price_history`에 현재 가격을 기록한다. **마지막 기록과 가격이 같고 6시간이 안 지났으면 기록하지 않는다** (이력 폭증 방지). `total_amount_krw`는 쿠폰을 뺀 기본 계산값(5장), 계산이 불가하면 null.
4. 저장 실패가 검색 응답을 막지 않게 한다. 저장에 실패한 offer는 `productId` 없이 응답하지 말고 **그 offer만 응답에서 제외**하고 로그를 남긴다 (프론트가 상세로 이동할 수 없기 때문).

`price_calculations` 테이블은 1차에서 **사용하지 않는다** (계산은 요청마다 메모리에서). 삭제하지 말고 둔다.

### 4-4. 2차 대비 (지금 만들 것)

```java
public interface ProductGrouper {
    List<ProductGroup> group(List<PricedOffer> offers);
}
// 1차 구현: offer 1개 = ProductGroup 1개 (NoopProductGrouper)
```

응답은 처음부터 **그룹 단위 `{product, offers[]}`** 로 만든다. 1차에서 `offers`는 항상 1개다. 2차에 묶기가 들어와도 응답 형태가 바뀌지 않는다.

### 4-5. 소스 호출 정책

| 상황 | 동작 |
| --- | --- |
| 타임아웃 | 연결 2초 · 읽기 3초. 검색 전체는 소스 병렬이라 최대 약 5초 |
| 5xx | 검색(사용자 요청 중)은 재시도하지 않고 그 소스를 `error`로 표시. 스케줄러 갱신은 최대 3회(1·2·4초) |
| 429 | 재시도하지 않고 그 소스를 `error`로 표시. 이후 일정 시간(기본 10분) 그 소스 호출 중단 |
| 401 · 403 | 재시도 안 함, 즉시 `api_sync_logs`에 기록, 로그에 키 노출 금지 |
| 형식 오류 | 해당 건만 건너뜀, 전체 파싱 불가면 `BadResponse` |
| 일일 예산 | 소스별 하루 호출 상한(`daily-budget`)을 메모리 카운터로 센다. 초과 시 그 소스는 `skipped` |

`api_sync_logs`에는 **스케줄러 회차와 검색 중 실패 건만** 기록한다 (성공한 검색 호출까지 쓰면 행이 폭증한다). 필드: `status`(`SUCCESS`/`PARTIAL`/`FAILED`), `http_status`, `records_fetched`, `records_skipped`, `error_message`.

---

## 5. 가격 계산 (PriceCalculator)

DB의 규칙 · 환율과 offer만 입력으로 받는 순수 함수. 모든 금액은 `BigDecimal`, **항목마다 원 단위 HALF_UP 반올림 후 합산**한다 (환율은 소수 6자리).

### 입력 / 출력

```
입력: offer(price, currency, shippingFee?, mall, originCountry), 환율 맵, 규칙(shipping/tax/fee), 행사 후보, couponEnabled
출력: Breakdown { productPriceKrw, discountAmountKrw, shippingFeeKrw, customsDutyKrw, vatKrw,
                  paymentFeeKrw, totalAmountKrw, appliedEvent?, shippingKnown, taxExempt, fxBaseDate }
```

### 계산 순서

1. **환율**: 통화별 최신 `base_date`의 `tts_rate`(전신환매도율, 없으면 `rate_to_krw`). 통화가 KRW면 1.
2. **상품가(원)** = `round(price × 환율)`
3. **할인** (행사는 DB의 `store_events`)
   - 후보: 같은 `mall_id`, `is_active`, 현재 시각이 `start_at`~`end_at`, `min_purchase_amount`(쇼핑몰 통화)를 충족
   - `SALE`은 항상 후보, `COUPON`은 `couponEnabled=true`일 때만 후보
   - 할인액(쇼핑몰 통화): RATE → `price × discount_value`, `max_discount_amount`로 상한 / AMOUNT → `discount_value`. 할인 정보가 없는 SALE은 0
   - 후보 중 **할인액이 가장 큰 1건만** 적용 → 원화 환산 후 `min(할인, 상품가)`
4. **배송비**
   - `offer.shippingFee`가 있으면 그 값 / 없으면 해당 쇼핑몰의 활성 `shipping_rules`(`base_fee`, `free_shipping_threshold` 이상이면 0)
   - 둘 다 없으면 0으로 두고 `shippingKnown=false` (화면에서 "배송비 미포함" 안내)
5. **면세 판정**: `(상품가 − 할인)`을 USD로 환산해 `tax_rules.exemption_amount_usd` 이하이면 관세 · 부가세 0 (배송비 제외하고 판정)
6. **과세가격** = 상품가 − 할인 + 배송비
   - 관세 = `round(과세가격 × duty_rate)`
   - 부가세 = `round((과세가격 + 관세) × vat_rate)`
   - **관세 + 부가세가 10,000원 미만이면 둘 다 0** (관세법 제40조)
7. **결제 수수료** = `round((상품가 − 할인 + 배송비) × fee_rate + fixed_fee_krw)`. 통화가 KRW인 국내 직구관은 0
8. **합계** = 상품가 − 할인 + 배송비 + 관세 + 부가세 + 수수료 (항목은 모두 이미 반올림된 값)

### 규칙 선택

- 세금: 활성 `tax_rules` 중 `카테고리+국가 > 카테고리 > 국가 > 기본` 순으로 가장 구체적인 1건. 1차는 카테고리를 모르므로(`category_id` null) **국가 > 기본** 만 쓴다. 국가는 `offer.originCountry` → 없으면 `shopping_malls.country_code`.
- 배송비 · 수수료: 위 순서대로.
- 규칙이 하나도 없으면 오류로 터뜨리지 말고, 세금 0 · 수수료 0으로 계산하고 `ruleMissing` 경고를 로그에 남긴다.

### 단위 테스트 (필수, JUnit)

면세 한도 직전 · 직후(미국 $200 / 기타 $150), 정률 할인 + 최대 할인 상한, 최소 구매금액 미달 쿠폰, 정액 할인 환산, 할인액이 상품가를 넘는 경우, 할인 정보 없는 SALE, 관세 + 부가세 1만원 미만, 무료배송 기준 경계, 원화 결제(수수료 0), 배송비 정보 없음, 100단위 통화(JPY·IDR) 소수점, 규칙 우선순위, 반올림 경계. 기대값은 손계산 표를 테스트 안에 주석으로 남긴다. 시드 SQL에 기준 금액(1,187,256 / 1,254,855 / 1,104,894원)이 있으면 그 입력으로 같은 합계가 나오는지 확인한다.

---

## 6. 내부 API

공통: JSON · UTF-8 · camelCase. 원화 금액은 정수, 쇼핑몰 통화 금액은 소수 허용(`currencyCode` 동반). 시각은 `yyyy-MM-dd'T'HH:mm:ss`(KST). 인증은 세션 쿠키(`JSESSIONID`). 페이지는 `page`(0부터) · `size`(기본 20, 최대 50).

### 6-1. 오류 응답

```json
{ "status": 400, "code": "INVALID_INPUT", "message": "...", "timestamp": "..." }
```

| code | HTTP | 상황 |
| --- | --- | --- |
| INVALID_INPUT | 400 | 입력 검증 실패 (검색어 비어 있음 · 50자 초과 등) |
| LOGIN_REQUIRED | 401 | 로그인 필요 |
| LOGIN_FAILED | 401 | 이메일 또는 비밀번호 불일치 (어느 쪽인지 구분하지 않음) |
| NOT_FOUND / PRODUCT_NOT_FOUND | 404 | 없음, 또는 남의 리소스 |
| DUPLICATE_EMAIL | 409 | 이메일 중복 |
| INTERNAL_ERROR | 500 | 서버 오류 (상세는 로그에만) |
| EXTERNAL_INVALID_RESPONSE | 502 | 외부 응답 형식 오류 (사용자 요청에서 결과가 아예 없을 때만) |
| EXTERNAL_API_UNAVAILABLE | 503 | 모든 소스가 실패하고 보여줄 값이 없을 때 |

검색에서 **일부 소스만 실패한 경우는 오류가 아니라 200** 으로 응답하고 `sources`에 상태를 담는다.

### 6-2. GET /api/products — 검색 (인증 불필요)

| 파라미터 | 설명 |
| --- | --- |
| q | 필수, 1~50자. 띄어쓴 단어는 모두 포함(AND) 의미로 소스에 전달 |
| stores | 쇼핑몰 ID 콤마 구분 (해당 소스만 호출) |
| countries | 출발국 코드 콤마 구분 (결과 필터) |
| minPrice, maxPrice | 예상 구매비용(원) 범위 (결과 필터) |
| freeShipping | `true`면 배송비 0인 것만 |
| onSale | `true`면 할인이 적용된 것만 |
| inStock | `true`면 재고 있는 것만 |
| coupon | `true`면 쿠폰까지 적용해 계산 (기본 false = SALE만) |
| sort | `price_asc`(기본), `discount_desc` |
| page, size | 정렬된 전체 결과를 메모리에서 자른다 |

카테고리 필터는 1차에서 제외한다 (소스별 카테고리 매핑이 없음). 프론트가 필요로 하면 소스별 카테고리 파라미터로 나중에 추가한다.

```json
{
  "items": [
    {
      "productId": 101,
      "productName": "Apple iPhone 17 256GB ...",
      "imageUrl": "https://...",
      "lowestTotalKrw": 1187256,
      "offerCount": 1,
      "offers": [
        {
          "mallId": 1, "mallName": "eBay US", "countryCode": "US",
          "productUrl": "https://...",
          "originalPrice": 799.00, "currencyCode": "USD",
          "stockStatus": "IN_STOCK", "shipDays": null,
          "fetchedAt": "2026-11-13T10:00:00",
          "breakdown": {
            "productPriceKrw": 1100000, "discountAmountKrw": 50000, "shippingFeeKrw": 30000,
            "customsDutyKrw": 0, "vatKrw": 108000, "paymentFeeKrw": 14000, "totalAmountKrw": 1202000,
            "shippingKnown": true, "taxExempt": false
          },
          "appliedEvent": { "storeEventId": 5, "eventName": "...", "eventType": "SALE" },
          "exchangeRate": { "currencyCode": "USD", "rateToKrw": 1380.5, "baseDate": "2026-11-12" }
        }
      ]
    }
  ],
  "sources": [
    { "mallId": 1, "mallName": "eBay US", "status": "ok", "count": 20 },
    { "mallId": 3, "mallName": "AliExpress", "status": "timeout", "count": 0 }
  ],
  "page": 0, "size": 20, "totalElements": 57, "totalPages": 3,
  "fromCache": false,
  "limitedToTopN": 20,
  "suggestions": []
}
```

- `sources[].status`: `ok` / `timeout` / `error` / `skipped`(예산 초과 · 비활성).
- 결과 0건이면 `items: []` + `suggestions`(비슷한 검색어 안내). 모든 소스가 실패했고 결과가 0건이면 503.
- 소스당 상위 N건(기본 20)만 가져오므로 `limitedToTopN`을 내려 화면이 "상위 N건 기준"임을 표시할 수 있게 한다.
- 정렬: 예상 구매비용 오름차순, 같으면 `shipDays` 짧은 순, 그다음 `mallName`. 품절은 가격과 상관없이 목록 맨 아래.

**검색어 처리**
- 앞뒤 공백 제거, 연속 공백 1개로, 길이 검증.
- **한영 별칭 변환**: `src/main/resources/aliases.csv`(`아이폰,iphone` 형식)를 읽어 한글 단어를 영어로 바꾼 검색어를 소스에 보낸다. 사전에 없는 한글은 그대로 보낸다. 초기 항목: 아이폰, 갤럭시, 맥북, 아이패드, 에어팟, 삼성, 애플, 노트북, 태블릿 등.
- **걸러내기 (`ResultFilter`)**: 새 상품이 아닌 것(`used`, `refurbished`, `renewed`, `for parts`, 중고, 리퍼), 액세서리·부품(`case`, `cover`, `protector`, `charger`, `cable`, `screen protector`, `케이스`, `필름` 등 제외 키워드. `for iPhone` 형태 포함). 키워드 목록은 설정 파일로 둔다.

### 6-3. GET /api/products/{id} — 상세

`coupon=true` 쿼리 지원. 인증 불필요.

- 저장된 값으로 응답하되, **쇼핑몰 API로 단건 갱신을 시도**(타임아웃 3초)하고 성공하면 가격을 갱신해 쓴다. 실패하면 저장된 값을 쓰고 `priceStatus: "stale"`과 `fetchedAt`을 내린다 (`ok`: 6시간 이내, `stale`: 초과, `unavailable`: 품절 · 조회 불가).
- 응답: 검색의 `items[]` 한 건과 같은 모양 + `description`, `variants[]`(옵션이 여러 개면 옵션별 `variantName` · 가격 · breakdown).
- 오류: 404 `PRODUCT_NOT_FOUND`.

### 6-4. GET /api/products/{id}/price-history

`period=1m|3m|6m|1y`(기본 1m, 그 외 400). `price_history`를 **날짜별 최저 `total_amount_krw`** 로 묶는다. `total_amount_krw`가 null인 행은 제외.

```json
{ "productId": 101, "period": "3m",
  "points": [ { "date": "2026-10-01", "lowestTotalKrw": 1230000 } ],
  "summary": { "minKrw": 1187256, "avgKrw": 1215000, "maxKrw": 1254855 } }
```

### 6-5. GET /api/exchange-rates

통화별 최신 `base_date` 1건: `{ items: [{ currencyCode, rateToKrw, ttsRate, baseDate, recordedAt }] }`. 대상 통화는 USD · JPY · EUR(+ 소스가 쓰는 통화). 데이터가 없으면 503.

### 6-6. GET /api/home

`{ categories: [...], monthlyEvents: [...] }` — `store_events` 중 `is_active`이고 이번 달과 기간이 겹치는 것. 인기 상품은 1차에서 제외 (프론트 `API_SPEC.md`에 있으면 빈 배열로 응답).

### 6-7. 인증

| 메서드 · 주소 | 요청 | 성공 | 오류 |
| --- | --- | --- | --- |
| POST /api/auth/signup | `email`, `nickname`, `password` | 201 `{userId,email,nickname,createdAt}` | 400, 409 DUPLICATE_EMAIL |
| POST /api/auth/login | `email`, `password` | 200 `{userId,email,nickname}` + 세션 쿠키 | 400, 401 LOGIN_FAILED |
| GET /api/auth/me | — | 200 | 401 |
| POST /api/auth/logout | — | 204 | 401 |

BCrypt, 로그인 성공 시 `request.changeSessionId()`, 세션 30분, 쿠키 HttpOnly · SameSite=Lax. 비밀번호는 응답 · 로그에 절대 포함하지 않는다. 비밀번호 최소 8자 **[결정]**.

### 6-8. 관심 상품 (로그인 필요)

| 메서드 · 주소 | 요청 | 성공 |
| --- | --- | --- |
| GET /api/wishlist | — | 200 `{items:[{wishlistId, productId, productName, imageUrl, lowestTotalKrw, createdAt}]}` |
| POST /api/wishlist | `{productId}` | 201 (이미 있으면 200으로 기존 항목, 멱등) |
| DELETE /api/wishlist/{productId} | — | 204 |

찜 목록의 가격은 저장된 최신값 기준으로 계산한다 (외부 호출 없음).

### 6-9. 가격 알림 (로그인 필요)

| alertBasis | 구분 | productId | storeEventId | targetPriceKrw |
| --- | --- | --- | --- | --- |
| TARGET_PRICE | 필수 | 필수 | 없음 | 필수 (0 초과) |
| PERIOD_LOWEST | 필수 | 필수 | 없음 | 없음 |
| EVENT_START | 선택 | 없음 | 필수 | 없음 |
| COUPON_EXPIRY | 선택 | 없음 | 필수 | 없음 |

(DB `chk_alert_target`과 동일. 위반은 400)

| 메서드 · 주소 | 요청 | 성공 |
| --- | --- | --- |
| GET /api/alerts | — | 200 `{items:[alert]}` |
| POST /api/alerts | `{alertBasis, productId?, storeEventId?, targetPriceKrw?}` | 201 (같은 조건이 있으면 UPDATE 후 200) |
| DELETE /api/alerts/{alertId} | — | 204 (남의 것이면 404) |

### 6-10. 받은 알림 (로그인 필요)

- `GET /api/notifications?unread=true&page=&size=` → `{ unreadCount, items:[{notificationId, alertId, alertBasis, message, productId, storeEventId, sentAt, readAt}], page... }`. `unreadCount`는 페이지와 무관한 전체 안 읽은 수.
- `PATCH /api/notifications/{id}/read` → 200 `{notificationId, readAt}` (이미 읽었으면 `readAt` 유지, 남의 것이면 404).

---

## 7. 스케줄러 세부

### 7-1. 환율 (한국수출입은행)

- `GET https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON?authkey={키}&searchdate={YYYYMMDD}&data=AP01` **[확인: 경로 · 응답 필드]**
- 사용 필드: `cur_unit`, `deal_bas_r`(매매기준율), `tts`(전신환매도율). **숫자 값에 쉼표가 들어 있을 수 있다**(`"1,380.5"`) → 쉼표 제거 후 파싱.
- `JPY(100)`, `IDR(100)` 등은 100으로 나눠 1단위 기준으로 저장 (`rate_to_krw`는 소수 6자리).
- `(currency_code, base_date)`로 upsert. 주말 · 공휴일 · 오전 11시 이전에는 빈 결과가 오므로 이 경우 저장하지 않고 **가장 최근 `base_date` 값을 계속 쓴다**. 11:30에 빈 결과면 15:00에 한 번 더 시도.
- 환율을 한 번도 못 받은 통화가 있으면 그 통화의 offer는 계산에서 제외(`unavailable`).

### 7-2. 관심 상품 가격 갱신 (6시간마다)

1. 찜 또는 활성 알림이 걸린 `product_id`의 `mall_products`를 소스별로 모은다 (`last_fetched_at` 오래된 순).
2. 소스별로 `refresh(externalIds)` 호출 (소스가 지원하는 만큼 배치: Shopify `lookup_catalog` 최대 50, AliExpress `product_ids` 콤마 목록, eBay · Etsy는 단건).
3. 가격 · 재고 upsert → `price_history` 기록 → 5장 계산(쿠폰 제외) → 알림 판정.
4. 한 건 실패는 건너뛰고 나머지를 계속. 소스 단위로 `api_sync_logs` 1행 기록.

### 7-3. 알림 판정

| alertBasis | 조건 |
| --- | --- |
| TARGET_PRICE | 상품의 현재 예상 구매비용 ≤ `target_price_krw` (품절 제외) |
| PERIOD_LOWEST | 현재 예상 구매비용이 최근 30일 `price_history` 최저보다 낮음 |
| EVENT_START | 행사 `start_at` 날짜가 오늘(KST)이고 `is_active` |
| COUPON_EXPIRY | 쿠폰 `end_at` 날짜가 내일(KST) |

- 같은 알림은 하루 1회: `last_notified_at`의 날짜가 오늘이면 건너뜀.
- 조건을 만족하면 `notifications`에 메시지를 저장하고 `last_notified_at`을 갱신 (한 트랜잭션).
- EVENT_START · COUPON_EXPIRY는 선택 기능이므로 구현 순서에서 가장 마지막에 한다.

---

## 8. 설정 (`application.yml` 뼈대)

```yaml
spring:
  threads:
    virtual:
      enabled: true
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
  datasource:
    url: jdbc:mysql://localhost:3306/globalprice?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: ${DB_USER}
    password: ${DB_PASSWORD}
server:
  servlet:
    session:
      timeout: 30m
      cookie:
        http-only: true
        same-site: lax
globalprice:
  search:
    per-source-limit: 20
    cache-ttl: 10m
    connect-timeout: 2s
    read-timeout: 3s
  sources:
    ebay:       { enabled: true,  client-id: ${EBAY_CLIENT_ID}, client-secret: ${EBAY_CLIENT_SECRET}, sandbox: true, daily-budget: 3000 }
    shopify:    { enabled: true,  agent-profile: https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json, daily-budget: 3000 }
    aliexpress: { enabled: false, app-key: ${ALI_APP_KEY}, app-secret: ${ALI_APP_SECRET}, tracking-id: ${ALI_TRACKING_ID}, daily-budget: 3000 }
    etsy:       { enabled: false, api-key: ${ETSY_API_KEY}, daily-budget: 3000 }
    koreaexim:  { auth-key: ${KOREAEXIM_KEY} }
```

- `enabled: false`인 소스는 키가 없어도 앱이 기동돼야 한다 (키는 `enabled=true`일 때만 필수).
- `daily-budget` 숫자는 시작값이다. 실제 한도는 각 공식 문서로 **[확인]** 후 조정.
- 시각은 모두 `Asia/Seoul`. `Clock` 빈을 주입해 시간에 의존하는 코드를 테스트할 수 있게 한다.

---

## 9. 보안

- HTTPS는 Nginx(:443)가 처리, Spring Boot(:8080)는 내부에서만. MySQL(:3306)은 외부 차단.
- 비밀번호 BCrypt, 로그인 시 세션 ID 재발급, 쿠키 HttpOnly · SameSite=Lax.
- 남의 찜 · 알림 · 알림 내역에 접근하면 404 (존재 여부를 숨김).
- 외부 응답의 문자열(제목 등)은 그대로 응답에 담되 서버에서 HTML로 렌더링하지 않는다 (프론트가 이스케이프).
- 로그에 API 키 · 서명 · Authorization · 세션 ID를 남기지 않는다.

---

## 10. 테스트 계획

| 구분 | 내용 |
| --- | --- |
| PriceCalculator | 5장의 단위 테스트 전부 |
| 소스 어댑터 | MockWebServer로 정상 JSON(실제 샘플 응답을 파일로 저장), 429, 503, 5초 지연, 401, 깨진 JSON, 값 누락. Shopify는 JSON · SSE 둘 다. AliExpress는 서명 단위 테스트 |
| 검색 서비스 | 가짜 소스 3개로: 일부 타임아웃 시 200 + `sources`, 전부 실패 시 503, 캐시 히트 시 외부 호출 0회, 걸러내기 · 정렬 · 필터 |
| 저장 | `(mall_id, external_product_id)` upsert 멱등, `price_history` 중복 방지 |
| API 통합 | `@SpringBootTest` + Testcontainers MySQL 8 (CHECK · 함수형 UNIQUE가 있어서 H2 불가) |
| 알림 | 시드 알림 + 가짜 가격으로 판정 · 하루 1회 제한 |
| 부하 | k6 (선택): 캐시 히트 검색 p95 1초 이내 |

---

## 11. DB 변경 · 시드

### 마이그레이션 `03_migration_001.sql` (기존 스크립트는 건드리지 않는다)

```sql
-- 전신환매도율 (결제 금액 계산용)
ALTER TABLE exchange_rates
  ADD COLUMN tts_rate DECIMAL(18,6) NULL AFTER rate_to_krw;
```

- 이미 컬럼이 있는지 확인하는 방식으로 재실행해도 안전하게 만든다. 적용 전에 기존 DB를 임의로 지우거나 재생성하지 않는다.

### 시드 (개발용, 별도 `04_seed_*.sql`)

| 테이블 | 값 |
| --- | --- |
| api_sources | `KOREAEXIM_RATE`, `EBAY_BROWSE`, `SHOPIFY_CATALOG`, `ALIEXPRESS_AFFILIATE`, `ETSY_OPEN_API`, `SEED` |
| shopping_malls | eBay US(US), Shopify(US), AliExpress(CN), Etsy(US), 국내 직구관 시드(KR) |
| shipping_rules | 쇼핑몰당 활성 1건 (배송비를 못 받는 AliExpress · Etsy · Shopify는 필수) |
| tax_rules | 기본 규칙 1건(관세 0%, 부가세 10%, 면세 $150) + 미국 규칙 1건(면세 $200). 전자기기 가정이므로 팀 확인 필요 |
| fee_rules | 해외결제 수수료 1건 (1.25~1.35% 안에서 팀 확정) |
| store_events | 테스트용 SALE · COUPON 몇 건 |

---

## 12. 구현 순서 (단계별 완료 기준)

| 단계 | 내용 | 끝났다는 기준 |
| --- | --- | --- |
| 1 | 프로젝트 골격: Boot 4.1.1 · Java 25, DB 연결, `ErrorCode` · 전역 예외 처리, 세션 인터셉터, 설정 프로퍼티 | 앱 기동, 빈 `GET /api/exchange-rates`가 503 JSON 오류 형식으로 응답 |
| 2 | 환율 수집 · 조회 (`03` 마이그레이션 포함) | 목 서버로 수집 테스트 통과, `GET /api/exchange-rates` 응답 |
| 3 | `PriceCalculator` + 단위 테스트 | 5장 테스트 전부 통과 |
| 4 | `MallSource` 인터페이스 + eBay 어댑터 + 저장 규칙 (4-3) | 샌드박스(또는 목)로 검색 → DB 저장 → 재실행 시 중복 없음 |
| 5 | 검색 API (`GET /api/products`): 별칭 · 걸러내기 · 동시 호출 · 캐시 · 계산 · 정렬 · `sources` | 일부 소스 실패 시에도 200, 캐시 히트 시 외부 호출 0회 |
| 6 | 상세 · 가격 이력 API, 단건 갱신 | stale 표시 동작 |
| 7 | 회원 · 관심 상품 | 가입 → 로그인 → 찜 → 목록 |
| 8 | 관심 상품 갱신 스케줄러 + 알림 조건 · 판정 · 받은 알림 (TARGET_PRICE · PERIOD_LOWEST) | 시드 알림이 가짜 가격 하락으로 알림 생성, 하루 1회 제한 |
| 9 | Shopify 어댑터 | JSON · SSE 파싱 테스트, 가격 최소 단위 환산 |
| 10 | AliExpress 어댑터 | 서명 단위 테스트, 승인 후 실호출 |
| 11 | Etsy 어댑터 (기본 `enabled=false`) | 목 테스트 통과 |
| 12 | EVENT_START · COUPON_EXPIRY 알림 (선택) | 판정 테스트 통과 |

각 단계가 끝나면 무엇을 만들었고 무엇이 막혔는지 짧게 보고한다.

---

## 13. 팀 확정이 필요한 것 (Claude Code가 임의로 정하지 말고 질문 · 보고)

| # | 항목 | 현재 가정 |
| --- | --- | --- |
| 1 | 검색 응답 시간 목표 | 캐시 미스는 소스 타임아웃 때문에 최대 약 5초, 캐시 히트는 p95 1초. 기존 요구사항의 "검색 p95 1초"는 캐시 미스에는 적용 불가 |
| 2 | 카테고리를 모르는 상품의 세금 | 국가 > 기본 규칙. 기본 규칙 값(관세율)을 전자기기 기준 0%로 둘지 |
| 3 | 결제 수수료율 | 1.25~1.35% 중 선택 |
| 4 | Shopify 에이전트 프로필 URL | 예시 URL 사용 중. 운영 허용 여부 확인 |
| 5 | Etsy 포함 여부 | 구현은 하되 비활성으로 시작 |
| 6 | 각 API 약관 | 가격 저장 · 재표시 · 비교 허용 여부, 제휴 링크 표시 의무 |
| 7 | 소스당 가져올 개수 N | 20 |
| 8 | 호출 한도 | `daily-budget` 시작값 3,000은 임시 |
| 9 | 배송 기간 컬럼 | 1차는 DB에 저장하지 않음 (응답에만 `shipDays`) |
| 10 | 한영 별칭 사전 관리 | 1차는 `aliases.csv` 파일 |
