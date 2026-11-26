# GlobalPrice 명세서

TEAM SODA · 해외직구 가격비교 서비스

- **이 문서 하나가 기준입니다.** 백엔드 동작 · 외부 API 연동 · 가격 계산 · 프론트와 주고받는 API 필드를 모두 여기에 적습니다.
  - 예전 판(v1.0 `BACKEND_SPEC.md`, v1.1 `BACKEND_SPEC_v1.1.docx`, 프론트 `API_SPEC.md`)은 이 문서로 합쳤고 파일은 지웠습니다. 옛 내용은 git 기록에서 볼 수 있습니다.
  - 코드를 바꿔서 동작이 달라지면 **이 문서를 같은 커밋에서 고치고**, 맨 아래 변경 이력에 한 줄 남깁니다.
- 마지막 갱신: 2026-11-22 (같은 상품 묶기 · 검색 체크표 · 이상 가격 · AliExpress 출발국 반영)
- 스택: Java 25 · Spring Boot 4.1.1 · Gradle 9.2 · MySQL 8.0.16 이상
- 코드: `globalprice-backend` (패키지 `com.teamsoda.globalprice`), 화면: `GlobalPrice_frontend`, DB: `GlobalPrice_DB`

---

## 0. 한눈에 보기

| 소스 | 상태 | 비고 |
| --- | --- | --- |
| eBay (Browse API) | 켜짐 · 운영 키로 실제 호출 확인 | 샌드박스에는 한국 배송 상품이 없어 0건 → `EBAY_SANDBOX=false` |
| Shopify (Global Catalog MCP) | 켜짐 · 실제 호출 확인 | 키 없음. 예시 에이전트 프로필은 운영용 아님 (13장) |
| Etsy (Open API v3) | 켜짐 · 실제 호출 확인 | 키 필요. 수공예 · 빈티지 위주라 전자기기 비교에 약함 |
| AliExpress (Affiliate) | 켜짐 · 실제 호출 확인 | 키 필요. tracking_id 없으면 제휴 링크 대신 상품 페이지 |
| 한국수출입은행 환율 | 실제 호출 확인 | `KOREAEXIM_KEY` 필요. 서버 시작 시 최근 영업일 값 자동 수집 |
| Google Play 지원 기기 목록 (공개 CSV) | 실제 내려받기 확인 | 키 없음. 휴대폰 · 태블릿 모델 목록(같은 상품 묶기, 4-5). DB 에 넣어 두고 7일마다 갱신, 검색 중에는 호출 안 함 |

키가 없거나 `*_ENABLED=false`인 소스는 `skipped`로 표시되고, 앱 기동에는 영향이 없습니다.

---

## 1. 제품 개요와 범위

다나와와 비슷한 해외직구 가격 비교 사이트. 사용자가 검색하면 해외 쇼핑몰 API를 **실시간으로** 조회해, 배송비 · 할인 · 환율 · 관세 · 부가세 · 결제 수수료를 반영한 **한국 도착 예상 구매비용(원)** 으로 비교해 보여 준다.

**하는 것 (구현 완료)**
- 검색어 → 활성 쇼핑몰 동시 조회 → 예상 구매비용 계산 → 정렬
- **같은 상품 묶기** (다나와식): 쇼핑몰 · 판매처가 달라도 같은 모델 + 같은 용량이면 상품 1개 아래 판매처 여러 줄 (4-5)
- 검색 상단 체크표(제조사 · 모델 · 용량 · 색상), 색상별 최저가, 상세의 "다른 구성"(용량별)
- 이상 가격(할부 · 계약금 표기, 터무니없이 싼 가격) 표시 후 비교에서 제외
- 상품 상세(단건 갱신 · stale 표시), 가격 변동 그래프, 환율 조회, 홈(행사 목록)
- 회원(세션 로그인), 관심 상품, 가격 알림(목표가 · 30일 최저 · 행사 시작 · 쿠폰 마감), 받은 알림

**하지 않는 것**
- AI 사용, 이메일 · 푸시 알림, 평점 · 인기순 정렬(가격순으로 대체), 카테고리 필터, 홈 인기 상품(빈 배열)
- 합산 과세(같은 날 여러 상품), 확정 세액 보장 — 화면 금액은 예상값

---

## 2. 전체 흐름

### 2-1. 검색 (사용자 요청 안에서 외부 API 호출)

```
GET /api/products?q=아이폰 17
 1. 검색어 검증(1~50자) · 공백 정리 · 한영 별칭 변환 (아이폰17 → iphone 17)
 2. 활성 소스 동시 호출 (가상 스레드, 소스당 연결 2초 + 읽기 3초 + 1초)
      └ 소스별 캐시 (검색어 기준 10분, 최대 500건) → 있으면 외부 호출 없음
      └ 하루 호출 예산 · 429 이후 10분 쉬기 확인
 3. 걸러내기: 새 상품만, 액세서리 · 부품 · 디지털 · 빈티지 · 통신사 잠김 · 박스만 제외 (6-2)
 4. DB 저장(upsert) + 묶기 키로 같은 상품에 연결 + 가격 이력 → productId 부여 (캐시 미스일 때만)
 5. 검색어에 모델코드가 있으면 그 코드가 상품명에 있는 리스팅만 남김
 6. 계산: 배송비 → 할인 → 환율 → 관세 · 부가세 → 수수료 (환율 없는 통화는 제외)
 7. productId 로 묶기 → 이상 가격 표시 → 필터 → 정렬 → 체크표 → 페이지 → 응답 (소스별 상태 포함)
```

### 2-2. 스케줄러 (Asia/Seoul)

| 작업 | 시각 | 내용 |
| --- | --- | --- |
| 환율 수집 | 평일 11:30, 빈 결과면 15:00 재시도 | 수출입은행 → `exchange_rates`. 서버 시작 시 오늘 값이 없으면 하루씩 거슬러(최대 7일) 최근 영업일 값 수집 |
| 관심 상품 가격 갱신 | 6시간마다 (0 · 6 · 12 · 18시) | 찜 · 활성 알림이 걸린 상품만 다시 조회 → 저장 → 이력 → 알림 판정 |
| 행사 알림 | 매일 09:00 (+ 6시간 작업 때도) | EVENT_START · COUPON_EXPIRY 판정 |
| 모델 목록 다시 읽기 | 서버 시작 직후 · 10분마다 | DB `model_codes` · `device_models` → 메모리 (`ModelCodes`). DB 만 읽음 |
| 기기 목록 갱신 | 서버 시작 15초 뒤 · 6시간마다 확인 | 마지막으로 받은 지 7일이 지났을 때만 Google Play 기기 목록을 내려받아 GPLAY 행을 새로 채움 (`DeviceCatalogImport`). 실패하면 기존 행 유지 |

관심 상품이 아닌 상품은 갱신하지 않는다. 가격 이력도 검색됐거나 갱신된 상품만 쌓인다.

---

## 3. 프로젝트 구조와 기술

| 패키지 | 역할 |
| --- | --- |
| config | 설정(record) · RestClient · Caffeine 캐시 · Clock · 로그인 인터셉터 등록 |
| exception | ErrorCode · BusinessException · 전역 예외 처리 |
| common | ErrorResponse, api_sync_logs 기록 |
| auth | 회원가입 · 로그인 · 로그인 인터셉터 (JPA) |
| source | `MallSource` 인터페이스, ebay / shopify / aliexpress / etsy 구현, 호출 예산 · 쿨다운(SourceGuard) |
| rate | 수출입은행 클라이언트 · 환율 서비스 |
| pricing | `PriceCalculator` (순수 함수) · 규칙 조회 |
| catalog | 상품 저장 흐름(`CatalogService`) · SQL(`CatalogRepository`) · 묶기 판단(`ProductGrouping` · `ProductMatcher` · `ModelCode`) · 모델 목록(`ModelCodes` · `DeviceCatalog` · `DeviceCatalogImport`) · 이상 가격(`OutlierService` · `PriceOutliers`) · 출발국(`Origin`) · 색상(`ColorName`) · 계산(`OfferPricer`) · 상세 · 가격 이력 · 응답 모양(Views) |
| search | 검색 흐름(`SearchService`) · 관련도 정렬(`SearchRanker`) · 체크표(`FacetBuilder`) · 별칭(`QueryNormalizer` · `AliasRepository`) · 걸러내기(`ResultFilter`) · `SameProductGrouper` · 소스 호출(SourceCaller) |
| wishlist / alert | 관심 상품, 알림 조건 · 판정 · 받은 알림 (JPA) |
| scheduler / web | 스케줄 작업, `@RestController` |

| 항목 | 선택 |
| --- | --- |
| 웹 | Spring Web MVC + Bean Validation |
| 외부 호출 | `RestClient`(JDK HttpClient), 가상 스레드 + `Future.get(timeout)` (`StructuredTaskScope`는 미리보기라 안 씀) |
| 캐시 | Spring Cache + Caffeine (10분, 500건) |
| 영속성 | JPA(회원 · 찜 · 알림) + JdbcTemplate(상품 · 가격 · 규칙 · 환율), `ddl-auto: none`, `open-in-view: false` |
| 로그인 | `HttpSession` + BCrypt(`spring-security-crypto`). Spring Security 전체는 안 씀 |
| JSON | Boot 4 기본 Jackson 3 (`tools.jackson`) |
| 테스트 | JUnit 5, JDK 내장 HttpServer 가짜 서버, Testcontainers MySQL 8 |
| JDK | Gradle foojay resolver 로 JDK 25 자동 다운로드 |

계층: Controller → Service → Repository. 외부 HTTP는 `source` · `rate`와 기기 목록 내려받기(`catalog.DeviceCatalogImport`, 7일마다)만 호출한다. `PriceCalculator`는 DB · 시간 · 네트워크에 의존하지 않는다.
offer 의 출발국 · 색상 · 묶기 키는 `OfferData`를 만들 때 한 번만 정하고 계산 · 화면 · 필터가 그 값을 쓴다 (곳곳에서 따로 계산하지 않음).

---

## 4. 외부 쇼핑몰 연동

### 4-1. 공통 인터페이스

```java
interface MallSource {
  String sourceName();    // api_sources.source_name (EBAY_BROWSE 등)
  String storeKey();      // 검색 stores 파라미터 값 (ebay, shopify, etsy, aliexpress)
  boolean enabled();      // 설정 enabled + 키가 있을 때만 true
  int dailyBudget();
  List<MallOffer> search(String query, SearchOptions options);
  List<MallOffer> refresh(List<String> externalProductIds);
  int refreshBatchSize(); // eBay 1, Shopify 50, AliExpress 20, Etsy 100
}
```

`MallOffer`: externalProductId, externalVariantId(`"DEFAULT"`), title, variantName, price, currencyCode, shippingFee(모르면 null), condition, originCountry(모르면 null), productUrl, imageUrl, stock, shipDays, gtin

- 실패는 sealed `SourceException` (`RateLimited` · `AuthFailed` · `Timeout` · `BadResponse`). 한 소스가 실패해도 다른 소스 결과는 그대로 쓴다.
- 새 쇼핑몰 = `MallSource` 구현 1개 + `api_sources` 1행 + `shopping_malls` 1행.

### 4-2. 소스별 명세 (실제 호출로 확인한 내용)

#### eBay Browse API

| 항목 | 내용 |
| --- | --- |
| 검색 | `GET /buy/browse/v1/item_summary/search?q=&filter=deliveryCountry:KR,conditions:{NEW}&limit=20`. `sort=price` 안 씀 (최저가순 상위가 전부 $0.99 케이스 · 필름) → Best Match 로 받아 우리 쪽에서 정렬 |
| 헤더 | `Authorization: Bearer {앱 토큰}`, `X-EBAY-C-MARKETPLACE-ID: EBAY_US`, `X-EBAY-C-ENDUSERCTX: contextualLocation=country=KR`, `Accept-Language: en-US` (없으면 제목이 한국어 기계번역) |
| 인증 | client credentials. 토큰 URL `/identity/v1/oauth2/token`, scope `https://api.ebay.com/oauth/api_scope`, 7200초 → 메모리 보관, 만료 60초 전 갱신 |
| 가격 | contextualLocation=KR 이면 `value`가 KRW 로 옴 → `convertedFromValue` · `convertedFromCurrency`(원래 통화)를 저장하고 우리 환율로 계산. 배송비도 같은 방식 |
| 상태 | conditionId 1000(New) · 1500(New other)을 새 상품으로 |
| 출발국 | `itemLocation.country` (eBay US 안에서도 CN · TW · DE 등) |
| 갱신 | `GET /buy/browse/v1/item/{itemId}` 단건. `estimatedAvailabilities`로 재고 판단 |
| 한도 | 하루 5,000회 |
| 남은 일 | 제휴 링크(`itemAffiliateWebUrl`)는 eBay Partner Network 캠페인 ID 필요 |

#### Shopify Global Catalog (UCP MCP)

| 항목 | 내용 |
| --- | --- |
| 호출 | `POST https://catalog.shopify.com/api/ucp/mcp`, JSON-RPC `tools/call`. 키 없음. `meta["ucp-agent"].profile` 필수 |
| 검색 | `search_catalog`: `catalog.query`, `filters { ships_to: {country: KR}, condition: [new] }`, `context { address_country: KR, currency: USD }`, `pagination.limit`(최대 50) |
| 갱신 | `lookup_catalog`: `catalog.ids` (1~50개, ProductVariant GID) |
| 응답 | `result.structuredContent.products[] → variants[]`. 가격은 최소 통화 단위 정수 (3199 USD = 31.99). JSON · SSE 둘 다 파싱 |
| 펼치기 | 상품 아래 판매자(seller)별 최저가 옵션 1개 = offer 1건. `external_product_id` = ProductVariant GID |
| 구매 링크 | 옵션 상품 페이지 url > 상품 url > checkout_url > 판매자 url (utm 등 attribution 유지) |
| 출발국 | 응답에 없음 → 결제 통화로 추정 (5장 규칙 선택). USD 를 요청해도 일부 상점은 현지 통화(AED · KWD · 원화 등)를 줌 |
| 주의 | 키 없는 요청은 IP 공유 한도라 운영 비권장. 프로필은 팀이 직접 호스팅해야 함. 옛 Global Catalog REST API는 2026-11-02 종료 |

#### Etsy Open API v3

| 항목 | 내용 |
| --- | --- |
| 인증 | `x-api-key: {keystring}:{shared_secret}`. 한도 초당 5 · 하루 5,000 (`x-limit-*` 헤더) |
| 검색 | `GET /v3/application/listings/active?keywords=&limit=&sort_on=score` (가격순은 SVG 도안 · 공예 재료만 나옴) |
| 가격 | `price = {amount, divisor, currency_code}` → amount ÷ divisor. 판매자마다 통화가 다름 |
| 이미지 | 검색 응답에 없음 → `/listings/batch?listing_ids=&includes=Images` 1회 추가 호출 (실패해도 결과는 그대로) |
| 걸러내기 | `listing_type=download` → DIGITAL, `is_supply` → SUPPLY, `when_made` 2007년 이전 → VINTAGE 로 표시해 제외 |
| 갱신 | 같은 batch API (최대 100) |

#### AliExpress Affiliate API

| 항목 | 내용 |
| --- | --- |
| 호출 | `https://api-sg.aliexpress.com/sync` + `method`. 공통 파라미터 `app_key` · `method` · 밀리초 `timestamp` · `sign_method=sha256` · `format=json` · `v=2.0` |
| 서명 | 파라미터 key 오름차순 `key+value` 연결 → HMAC-SHA256(app_secret) → 대문자 hex |
| 검색 | `aliexpress.affiliate.product.query`: keywords, page_no, page_size, `target_currency=USD`, `target_language=EN`, `ship_to_country=KR`, [tracking_id]. sort 안 씀 (`SALE_PRICE_ASC`는 상위가 전부 $0.03~0.14 소품) |
| 갱신 | `aliexpress.affiliate.productdetail.get`: product_ids 콤마 구분 (배치 20) |
| 응답 | `resp_result.resp_code` 200 → `result.products.product[]`. `target_sale_price`(문자열) · `target_sale_price_currency`, `product_detail_url`, `promotion_link`, `product_main_image_url`. `ship_to_days`는 오지 않음 |
| tracking_id | 선택. 없으면 `promotion_link`가 상품과 무관한 같은 링크로 옴 → 없으면 `product_detail_url`, 있으면 `promotion_link` |
| 출발국 | **응답에 발송지 필드가 없어 `CN`으로 고정.** 해외 창고 상품도 구분 불가. 면세 한도는 더 엄격한 $150 쪽 |
| 배송비 | 응답에 없음 → `shipping_rules` ($3, $10 이상 무료). 옵션 없음 → `DEFAULT` |
| 한계 | 브랜드명 검색이 약함 (airpods → 원예용 pods 등 무관 상품) |

### 4-3. 저장 규칙 (검색 결과 → DB)

흐름은 `CatalogService`, 리스팅을 어느 상품에 붙일지는 `ProductGrouping`, SQL은 `CatalogRepository`(`ProductGrouping.Store` 구현)가 맡는다.

1. `mall_products`를 `(mall_id, external_product_id)`로 조회.
   - 없으면 묶기 키(4-5)로 `products`를 찾고, 없으면 새로 만든 뒤 `mall_products` 1행을 붙인다. `listing_title`에 쇼핑몰 원래 상품명을 저장.
   - 있으면 `listing_title` · URL · 통화 · 배송비 · `last_fetched_at` 갱신. 지금 상품명으로 만든 묶기 키가 상품의 키와 다르면 맞는 상품으로 옮긴다 (예전 1 리스팅 = 1 상품 데이터도 다시 검색되면 합쳐짐).
     - 혼자인 상품이고 맞는 키 상품이 없으면 → 그 상품에 키를 달고 대표 이름으로 바꿈
     - 그 밖에는 → 리스팅을 옮기고, 빈 상품은 찜을 옮긴 뒤 삭제. 단 혼자인 상품에 가격 알림이 있으면 알림이 끊기지 않게 그대로 둠
2. `mall_product_variants`를 `(mall_product_id, external_variant_id)`로 upsert (`current_price`, `stock_status`).
3. `price_history`: 마지막 기록과 가격이 같고 6시간이 안 지났으면 기록하지 않음. `total_amount_krw`는 쿠폰을 뺀 기본 계산값(SALE은 적용), 계산 불가면 null.
4. offer마다 트랜잭션을 따로 둔다. 저장에 실패한 offer는 응답에서 빼고 로그만 남긴다. 같은 상품 · 같은 묶기 키를 동시에 처음 저장하면(중복 키) 한 번 다시 시도.

`price_calculations` 테이블은 쓰지 않는다 (계산은 요청마다 메모리에서). `mall_product_variants.normalized_option_key`와 `search_aliases`의 모델번호 행은 DB에만 있고 백엔드는 아직 쓰지 않는다 (13장).

### 4-4. 소스 호출 정책

| 상황 | 동작 |
| --- | --- |
| 타임아웃 | 연결 2초 · 읽기 3초. 검색은 소스당 최대 6초 기다리고 넘으면 `timeout` (작업은 뒤에서 끝나 캐시에 남음) |
| 5xx | 검색: 재시도 없이 `error`. 스케줄러 갱신: 1 · 2 · 4초 간격 최대 3회 |
| 429 | 재시도 없음, `error`. 이후 10분 동안 그 소스 호출 안 함 |
| 401 · 403 | 재시도 없음. `api_sync_logs` 기록, 로그에 키 · 토큰 · 서명 미노출 |
| 형식 오류 | 그 건만 건너뜀. 전체를 못 읽으면 `BadResponse` |
| 하루 예산 | 소스별 메모리 카운터 (`daily-budget`). 넘으면 `skipped` |

`api_sync_logs`에는 스케줄러 회차(소스당 1행)와 검색 중 실패 건만 남긴다.

### 4-5. 같은 상품 묶기 (`ProductMatcher` → `products.match_key`)

원칙: **잘못 묶는 것(다른 상품이 한 줄에 섞임)보다 덜 묶는 것(같은 상품이 두 줄)이 낫다.** 상품명 + 옵션 이름으로 키를 만든다.

| 순서 | 대상 | 키 형식 | 대표 이름 |
| --- | --- | --- | --- |
| 1 | iPhone | `iphone\|모델\|용량` | `Apple iPhone 17 Pro 256GB` |
| 1 | Galaxy | `galaxy\|시리즈\|번호\|접미사\|용량` | `Samsung Galaxy ...` |
| 1 | Pixel | `pixel\|세대\|접미사\|용량` | `Google Pixel ...` |
| 1 | MacBook | `macbook\|라인\|크기\|칩\|RAM\|SSD` | `Apple MacBook ...` |
| 1 | iPad | `ipad\|라인\|크기\|칩·세대\|용량\|wifi·cell` | `Apple iPad ...` |
| 1 | 그 밖의 휴대폰 · 태블릿 (모델 목록에 있음) | `dev\|제조사\|모델 이름\|용량` | `POCO X6 Pro 5G 512GB` |
| 2 | 상품명에 모델코드가 하나뿐 (위 5개 품목 · 모델 목록에 있는 폰 제외, 갤럭시 SM- 코드는 허용) | `code:{코드}` | 리스팅 원래 이름 |
| 3 | 그 밖 | `t:{소문자 · 공백 정리한 상품명}` (6자 이상) | 리스팅 원래 이름 |

- 색상 · 통신사 · 언락 · 배송 문구는 무시한다 (판매처 줄에서 원래 상품명 `title`로 보여 줌).
- 묶지 않는 경우: 한 상품명에 용량이나 모델이 여러 개, 용량 없음, 세트 · 묶음(`bundle`, `combo`, `set of`, `with case`, `세트`, `사은품` 등) → 다음 순서로 넘어감.
- **모델코드** (`ModelCode`): 영문 + 숫자 5~20자, 숫자 3개 이상 (또는 6자 이상 · 숫자 2개 · 영문 3개). `256GB` · `240Hz` · `13-inches` · `40oz` 같은 단위, `iPhone17` 같은 제품 이름, `Dimensity7300` 같은 칩 이름은 제외. 비교 시 대소문자 · 하이픈 · 공백 무시 (`SM-S938B` = `sms938b`).
- **갤럭시 모델번호** (`ModelCodes`, DB `model_codes` · 09 마이그레이션): 번호 → 모델 (`s938` → S25 Ultra, `f946` → Z Fold5). 모델 하나에 번호 여러 개 가능 (A15 ← `a155` · `a156`). 지역 글자는 무시 (`SM-S938B` = `SM-S938N` = `SM-S938U1`). `SM-` 없이 쓴 번호(`(S938B)`)는 상품명에 samsung · galaxy 가 있을 때만 알아본다. 상품명 속 모델명과 번호가 다른 모델이거나 번호 두 개가 서로 다른 모델이면 갤럭시 규칙으로 묶지 않는다. 서버 시작 직후와 10분마다 다시 읽고, 테이블이 없거나 비면 내장 목록(S24 · S25 · Fold/Flip 6 · 7, 11개)을 쓴다. 번호를 더할 때는 DB 에 행만 넣으면 된다.
- **휴대폰 · 태블릿 모델 목록** (`DeviceCatalog`, DB `device_models` · `model_codes`, 10 마이그레이션): Google Play 지원 기기 목록(공개 CSV, 안드로이드 기기 약 5.5만 행)을 `DeviceCatalogImport`가 받아 채운다 (서버 시작 15초 뒤 · 6시간마다 확인, 마지막으로 받은 지 7일 지나면 다시 받음. 실패하면 기존 목록 유지). 11/27 기준 모델 약 2.7만 개 · 모델번호 약 1.2만 개 · 갤럭시 번호 앞 4자리 372개.
  - 상품명에서 가장 긴 모델 이름을 찾는다 (`POCO X6 Pro`가 `POCO X6`보다 먼저). 제조사 이름(별칭: Xiaomi ↔ Redmi · POCO, Motorola ↔ moto, LGE → LG)이 상품명에 있어야 한다. 5G · 4G · LTE 표기와 `+`/`plus` 차이는 무시.
  - 모델번호(영숫자 5자 이상, 하이픈 무시: `CPH2649`, `2311DRK48G`, `XT2415-1`)로도 찾는다. 한 번호가 여러 모델이면 쓰지 않는다.
  - 묶지 않음: 모델 이름이 둘 이상(호환 목록), 이름과 번호가 다른 모델, 용량이 없거나 둘 이상(`256/512GB` 포함), 세트 상품.
  - 삼성 행은 갤럭시 규칙으로 읽히면 `SM-` 번호 앞 4자리를 갤럭시 번호로 넣고(M · Tab 등 시드에 없던 시리즈 포함), 구글 Pixel 은 픽셀 규칙을 쓰므로 넣지 않는다.
  - 사람이 넣은 `MANUAL` 행이 자동(`GPLAY`) 행보다 우선. 자동 갱신은 GPLAY 행만 지우고 다시 넣는다. 끄려면 `globalprice.device-catalog.enabled=false`.
  - 같은 시리즈 비교(4-6 3단계)는 이 기기들에는 같은 모델끼리만 (제조사 전체와 비교하지 않음).
- **용량 목록 표기**: `256/512GB`처럼 단위를 마지막에만 쓴 목록도 용량 여러 개로 본다 (모든 품목 공통, 묶지 않음). `12/256GB`의 12 는 RAM 이라 용량에서 빠진다.
- **다른 구성**: 키에서 용량을 뺀 부분(family, 예 `iphone|17 pro`)이 같은 상품들. 상세 `configs`로 내려준다.

### 4-6. 이상 가격 (`OutlierService`)

걸린 판매처는 지우지 않고 `status: "suspicious"`로 표시해 **최저가 · 정렬 · 비교에서만 뺀다** (품절과 같은 방식).
**검색 · 상세 · 찜 목록 · 가격 알림 판정이 모두 같은 규칙**을 쓴다 (목표가 알림이 할부 표기 · 터무니없이 싼 리스팅 때문에 잘못 가지 않음).

1. 상품 안
   - 할부 · 계약금 표기: 상품명에 `application.yml`의 `globalprice.outliers.installment-keywords`(installment, monthly, /mo, deposit, 할부, 계약금, 分期 …)가 있으면 일시불 총액이 아님.
   - 비교 가능한 판매처가 3곳 이상이면, 중간값의 1/3보다 싼 판매처.
2. 같은 모델 다른 구성(용량만 다름, 묶기 키의 family 가 같음)이 2개 이상이면 그 최저가들의 중간값 1/3보다 싼 판매처. 비교 대상은 **DB 에 저장된 같은 모델 상품 + 지금 판정하는 상품들** (저장값은 쿠폰 제외 · 1단계 판정 후 최저가).
3. 다른 구성이 없으면 같은 시리즈(세대만 다름, 예 Z Fold2~7, 모델이 확실한 상품 3개 이상)의 중간값 1/5보다 싼 판매처. 비교 대상은 **지금 판정하는 상품들끼리만** (같은 검색 결과 · 같은 찜 목록). DB 전체와 비교하면 "아이폰 17 케이스" 검색의 케이스가 저장된 아이폰 본품 가격과 비교돼 전부 이상 가격이 된다.

---

## 5. 가격 계산 (PriceCalculator)

DB 규칙 · 환율 · offer만 입력으로 받는 순수 함수. 모든 금액은 `BigDecimal`, **항목마다 원 단위 HALF_UP 반올림 후 합산** (환율은 소수 6자리).

| 순서 | 계산 |
| --- | --- |
| 1. 환율 | 통화별 최신 `base_date`의 `tts_rate`(전신환매도율), 없으면 `rate_to_krw`. KRW는 1. 환율이 없는 통화는 계산 불가(`unavailable`) |
| 2. 상품가 | `round(price × 환율)` |
| 3. 할인 | 같은 쇼핑몰 · 활성 · 기간 안 · 최소 구매금액(쇼핑몰 통화) 충족 행사 중 할인액이 가장 큰 1건. SALE은 항상, COUPON은 `coupon=true`일 때만. RATE는 `max_discount_amount`로 상한, AMOUNT는 `discount_value`, 할인 정보 없는 SALE은 0. 원화 환산 후 `min(할인, 상품가)` |
| 4. 배송비 | offer 배송비 > 활성 `shipping_rules`(무료배송 기준 이상이면 0) > 모름(0, `shippingKnown=false`, 화면 "배송비 미포함") |
| 5. 면세 판정 | (상품가 − 할인)을 USD로 환산해 면세 한도 이하면 관세 · 부가세 0 (배송비 제외). 출발국 미국 $200, 그 외 $150 |
| 6. 관세 · 부가세 | 과세가격 = 상품가 − 할인 + 배송비. 관세 = `round(과세가격 × 관세율)`, 부가세 = `round((과세가격 + 관세) × 부가세율)`. 둘의 합이 10,000원 미만이면 둘 다 0 (관세법 제40조) |
| 7. 수수료 | `round((상품가 − 할인 + 배송비) × fee_rate + fixed_fee_krw)`. KRW 이면서 쇼핑몰 국가가 KR(국내 직구관)일 때만 0 |
| 8. 합계 | 상품가 − 할인 + 배송비 + 관세 + 부가세 + 수수료 |

### 규칙 선택 · 예외 처리

- **출발국** (`Origin`, 면세 한도 · 세금 규칙 · 화면 국가 표시에 공통):
  1. 쇼핑몰 API가 준 값 (eBay `itemLocation.country`, AliExpress는 `CN` 고정)
  2. 쇼핑몰 나라가 CN(AliExpress)이면 CN — USD로 바꿔 받으므로 통화로 추정하면 안 됨. DB에서 다시 읽은 리스팅(출발국 미저장)도 이 규칙으로 CN
  3. 결제 통화가 쇼핑몰 기본 통화와 같으면 쇼핑몰 나라
  4. 원화면 쇼핑몰 나라, 유로면 알 수 없음(null → 쇼핑몰 나라로 계산, 화면 "유럽(유로)")
  5. 그 밖에는 통화의 나라 (Shopify AED 상점 → 아랍에미리트, eBay CAD 판매자 → 캐나다)
- 세금: 활성 `tax_rules` 중 `카테고리+국가 > 카테고리 > 국가 > 기본`. 카테고리를 모르므로 실제로는 `국가 > 기본`.
- 수수료: 활성 규칙이 여러 개면 `globalprice.pricing.fee-rule-name`(기본 "해외 결제 수수료") 우선, 없으면 ID가 가장 작은 것.
- 무료배송 기준은 할인 전 상품가와 비교. 규칙 통화가 offer 통화와 다르면 원화로 바꿔 비교.
- USD 환율이 없으면 면세 여부를 알 수 없으므로 과세로 계산하고 `ruleMissing` 경고.
- 세금 · 수수료 규칙이 없으면 오류 없이 0으로 계산하고 `ruleMissing` 경고 로그.

### 검증된 기준 금액 (`03_seed_data.sql`)

| 상품 · 판매처 | 조건 | 합계 |
| --- | --- | --- |
| iPhone 17 · Amazon US | $799, 배송 $15, 쿠폰 10%(최대 $50), 환율 1,400 | 1,187,256원 (쿠폰 미적용 1,264,956원) |
| iPhone 17 · Rakuten Japan | ¥119,800, 배송 ¥1,200, 쿠폰 5%(최대 ¥2,000), 환율 9.5 | 1,254,855원 |
| Galaxy S25 · Amazon US | $749, 배송 $12, 쿠폰 10%(최대 $50) | 1,104,894원 |

---

## 6. API (프론트 ↔ 백엔드)

프론트 `static/js/api.js`가 호출하는 형식이다. **필드 이름 · 모양은 이 장이 기준**이다.

### 6-0. 공통

- JSON · UTF-8 · camelCase. 성공 응답은 감싸지 않고 데이터를 그대로 보낸다.
- 원화 금액은 정수(원), 쇼핑몰 통화 금액은 소수 (통화 코드와 함께).
- 시각은 KST `yyyy-MM-dd'T'HH:mm:ss` (오프셋 없음).
- 인증은 세션 쿠키 `JSESSIONID`. 프론트는 `credentials: 'same-origin'`으로 요청하고 토큰 · `Authorization` 헤더는 쓰지 않는다. 로그인 상태는 페이지가 열릴 때 `GET /api/auth/me`로 확인 (브라우저의 `gp_user`는 표시용 캐시).
- 페이지: `page`(0부터) · `size`(1~50, 기본 20).
- 프론트 요청 제한 시간 8초 (`TIMEOUT_MS`). 넘으면 "응답이 늦어요" 안내.
- 프론트 목업: `js/config.js`의 `USE_MOCK: true`면 `js/mock-data.js`로 동작 (백엔드 없이). 서버는 빌드할 때 Gradle(`processResources`)이 `GlobalPrice_frontend/static`을 `static/`으로 복사하면서 `USE_MOCK`만 `false`로 바꿔 서비스한다 (복사본을 손으로 맞추지 않음).

### 6-1. 오류 응답

```json
{ "status": 404, "code": "PRODUCT_NOT_FOUND", "message": "상품을 찾을 수 없어요.", "timestamp": "2026-11-15T10:00:00" }
```

프론트는 `code`로 화면을 나누고 `message`를 보여 준다 (`js/common.js`의 `ERRORS`). `code`가 없으면 HTTP 상태로 판단. 예전 이름(`UNAUTHORIZED`, `EXTERNAL_UNAVAILABLE`)도 같은 화면.

| code | HTTP | 상황 | 화면 |
| --- | --- | --- | --- |
| INVALID_INPUT | 400 | 입력 검증 실패 (검색어 없음 · 50자 초과 · 잘못된 period · 정렬 · 알림 조합 오류 등) | 입력값 확인 안내 |
| LOGIN_REQUIRED | 401 | 로그인 필요 | "로그인이 필요해요" + 로그인 버튼 |
| LOGIN_FAILED | 401 | 이메일 · 비밀번호 불일치 (구분하지 않음) | 로그인 폼 아래 message |
| FORBIDDEN | 403 | 권한 없음 (현재 사용처 없음) | 검색 버튼 |
| NOT_FOUND / PRODUCT_NOT_FOUND | 404 | 없음, 또는 남의 리소스 (존재 여부를 숨김) | 검색 버튼 |
| METHOD_NOT_ALLOWED | 405 | 지원하지 않는 메서드 | 안내만 |
| DUPLICATE_EMAIL | 409 | 이메일 중복 | 가입 폼 안내 |
| INTERNAL_ERROR | 500 | 서버 오류 (상세는 로그에만) | 다시 시도 |
| EXTERNAL_INVALID_RESPONSE | 502 | 호출한 소스가 모두 형식 오류이고 결과 0건 | 다시 시도 |
| EXTERNAL_API_UNAVAILABLE | 503 | 호출한 소스가 모두 실패하고 결과 0건, 또는 환율이 하나도 없음 | 다시 시도 |

검색에서 일부 소스만 실패하면 오류가 아니라 200 + `sources[].status`. 필터로 고른 판매처에 연결된 API가 하나도 없으면(예: `amazon-us`) 빈 결과 200.

### 6-2. 검색 — `GET /api/products` (인증 불필요)

| 파라미터 | 설명 |
| --- | --- |
| `q` | 필수, 1~50자 |
| `stores` | 쉼표 구분. 소스 키(`ebay` · `shopify` · `etsy` · `aliexpress`) 또는 mall_id |
| `countries` | 출발국 코드 쉼표 구분 (`US,CN,JP`). `KRW` = 국내 직구관(원화 결제) |
| `minPrice`, `maxPrice` | 예상 구매비용(원) 범위 |
| `freeShipping`, `onSale`, `inStock` | `true`면 무료배송 · 할인 적용 · 재고 있음만 |
| `benefits` | 프론트용 묶음: `event`(=onSale), `freeShip`(=freeShipping), `taxIncluded`(해당 판매처 없음 → 0건) |
| `coupon` | `true`면 쿠폰까지 적용 (기본 false = SALE만) |
| `sort` | `price_asc`(기본) · `discount_desc`. 프론트 값 `total` · `discount`도 받음. `popular` · `rating`은 미지원 → 가격순. 그 외 400 |
| `brands`, `models`, `storages`, `colors` | 상단 체크표에서 고른 값 (쉼표 구분, 대소문자 무시). 값은 응답 `facets[].value` |
| `page`, `size` | 정렬된 전체 결과를 메모리에서 자름 |

```json
{ "query": "아이폰 17", "sourceQuery": "iphone 17", "total": 11, "ratesUpdatedAt": "2026-11-22T16:04:14",
  "items": [ ProductSummary ],
  "sources": [ { "mallId": 1, "mallName": "eBay US", "storeId": "ebay", "status": "ok", "count": 12 } ],
  "page": 0, "size": 20, "totalElements": 11, "totalPages": 1,
  "fromCache": false, "limitedToTopN": 20, "suggestions": [],
  "modelCodes": [], "modelCodeExcluded": 0,
  "facets": { "brands": [ FacetOption ], "models": [ FacetOption ], "storages": [ FacetOption ], "colors": [ FacetOption ] } }
```

| 필드 | 설명 |
| --- | --- |
| `sources[].status` | `ok` · `timeout` · `error` · `skipped`(예산 초과 · 비활성 · 키 없음) |
| `limitedToTopN` | 소스당 상위 N건(기본 20)만 가져옴 → 화면 "상위 N건 기준" |
| `suggestions` | 결과 0건일 때 마지막 단어를 뺀 검색어 · 영어 변환 검색어 |
| `modelCodes` · `modelCodeExcluded` | 검색어에 모델코드(4-5)가 있으면 그 코드가 상품명에 있는 리스팅만 보여 주고, 뺀 건수 |
| `facets` | 이번 결과에 실제로 있는 제조사 · 모델 · 용량 · 색상 (체크하기 전 기준). `FacetOption = { value, label, count }` — `value`는 다시 보낼 값(모델은 `iphone\|17 pro`, 용량은 GB 숫자 `256`), `count`는 그 값을 가진 상품 수. 칸끼리는 AND, 한 칸 안은 OR. 색상을 고르면 그 색상 판매처만 남고 가격도 그 기준 |

**정렬** (`price_asc`)
1. 가격이 하나도 없는 상품(전부 이상 가격 · 조회 불가 · 품절)은 맨 아래.
2. **검색어와 맞는 정도**: 검색어에서 모델을 알아내면(아이폰 · 갤럭시 · 픽셀 · 맥북 · 아이패드) 같은 모델 + 같은 용량 → 같은 모델 다른 용량 순으로 맨 위. 나머지는 단어 점수 순 (포함률 · 군더더기 · 검색어에 없는 모델 구분 말(Pro · Max · Flip · e …) 하나당 −0.3 · 다른 세대 −0.2, 0.1 단위로 끊음).
3. 같은 단계 안에서는 모델 묶음별 최저가 순 → 모델 · 용량 순 (256 → 512 → 1TB).
4. 그다음 예상 구매비용 오름차순 → `shipDays` 짧은 순 → 쇼핑몰 이름.

`discount_desc`는 할인율 높은 순이고 관련도 정렬은 쓰지 않는다.

**검색어 처리**
- 공백 정리 → DB `search_aliases`(05 · 08 마이그레이션)로 한글 단어를 영어로 (사전 단어 뒤에 숫자 · 영문이 붙으면 나눔: 갤럭시s25 → galaxy s25). 사전에 없는 한글은 그대로.
  - 쓰는 행: `is_active` 이고 `model_number`가 NULL 인 행, 단어 하나짜리 별칭만. 같은 말이 여러 행이면 `alias_source` MANUAL(사람이 넣은 값) > AUTO(자동 번역, 아직 없음), 그다음 먼저 넣은 행.
  - 서버가 10분마다 다시 읽는다 (DB 에서 고치면 재시작 없이 반영). 읽기에 실패하면 마지막으로 읽은 사전을 계속 쓴다.
  - 액세서리 단어도 사전에 있어야 한다 (케이스 → case, 필름 → screen protector). 걸러내기는 번역된 검색어로 판단해서, 번역이 안 되면 "케이스" 검색에서 영어 상품명의 case 가 걸러진다.
- **걸러내기** (`ResultFilter`, 키워드는 `application.yml`의 `globalprice.filter`). 검색어에 같은 키워드가 있으면(예: "iphone case") 거르지 않는다. 영문 키워드는 단어 경계 + 복수형(cases, covers)까지.
  - 새 상품 아님: used · pre-owned · refurbished · renewed · for parts · as-is · open box · 중고 · 리퍼 등
  - 액세서리 · 부품: case · cover · protector · charger · cable · stand · mount · pouch · repair · keycap · band 등 약 80개 (영 · 독 · 프 · 스페인어 · 한글)
  - 박스만 파는 리스팅 ("box only", "empty box", "박스만")
  - 시계를 검색했는데 밴드 · 루프 · 스트랩만 파는 리스팅
  - 미국 통신사 잠김 폰 (AT&T · Verizon · T-Mobile 등, "Unlocked"가 같이 있으면 통과). 목록은 `carrier-keywords` · `unlocked-keywords`, 시계 밴드는 `watch-band-keywords` (정규식)
  - "for iPhone" 같은 호환 표기, 검색어 첫 단어(브랜드) 앞에 for · compatible with · fits가 붙은 표기 ("Stand for Sony ...")

**ProductSummary** (검색 · 찜 목록의 상품 1건)
```json
{ "id": 61, "name": "Apple iPhone 17 256GB", "spec": null, "brand": null, "model": null, "imageUrl": "https://...",
  "categoryName": "", "rating": null, "reviewCount": null, "popularity": null,
  "badges": ["행사 진행 중"], "offerCount": 12, "bestTotal": 1230060, "bestNormalTotal": 1265000, "bestStore": "eBay US",
  "offers": [ Offer ],
  "colors": [ { "name": "Lavender", "bestTotal": 1230060, "offerCount": 3 } ] }
```
- `name`: 묶인 상품의 대표 이름 (4-5). 판매처 줄의 원래 이름은 `offers[].title`.
- `offers`: 비교 대상 → 품절 → 이상 가격 → 조회 불가 순, 상위 3개. `offerCount`는 전체 판매처 수.
- `colors`: 색상별 최저가 (비교 가능한 판매처 기준, 싼 색상부터). 색상이 2개 이상일 때만, 아니면 빈 배열.
- `rating` · `reviewCount` · `popularity`는 항상 null (DB에 없음, 화면에서 숨김).

**Offer** (판매처 옵션 1건 = `mall_product_variants` 1행)
```json
{ "mallProductId": 1, "mallVariantId": 1, "mallId": 1, "storeId": "ebay", "storeName": "eBay US",
  "countryName": "미국", "countryCode": "US", "variantName": null, "stockStatus": "IN_STOCK",
  "status": "ok", "calculatedAt": "2026-11-22T16:04:20", "updatedAt": "2026-11-22T16:04:20", "fetchedAt": "2026-11-22T16:04:20",
  "currency": "USD", "price": 799.00, "shipping": 15.00, "shippingNote": null,
  "url": "https://...", "imageUrl": "https://...",
  "title": "Apple iPhone 17 256GB Lavender Unlocked", "seller": null, "color": "Lavender",
  "appliedEvent": { "storeEventId": 1, "eventName": "...", "eventType": "COUPON", "discountType": "RATE",
                    "discountValue": 0.10, "maxDiscountAmount": 50.00, "discountAmount": 50.00 },
  "taxIncluded": false, "shipDays": null, "deliveryDays": null,
  "calc": { "productPriceKrw": 1118600, "shippingFeeKrw": 21000, "discountAmountKrw": 70000,
            "customsDutyKrw": 0, "vatKrw": 106960, "paymentFeeKrw": 10696, "totalAmountKrw": 1187256,
            "taxed": true, "taxExempt": false, "exemptionUsd": 200, "shippingKnown": true },
  "total": 1187256, "totalNormal": 1264956,
  "exchangeRate": { "currencyCode": "USD", "rateToKrw": 1400, "baseDate": "2026-11-20" } }
```

| 필드 | 설명 |
| --- | --- |
| `storeId` | 소스 키 (`ebay` 등). `stores` 파라미터 값과 같음 |
| `countryCode` · `countryName` | 출발국 (5장 규칙). "원화 결제"는 국내 직구관(KR 쇼핑몰 + KRW)만, 유로는 "유럽(유로)" |
| `status` | `ok`(이번 갱신 성공 또는 6시간 이내) · `stale`(갱신 실패, 6시간 초과 → 화면 "N시간 전 가격") · `unavailable`(환율 없음 → `price` · `calc` · `total` null, "일시적으로 조회 불가") · `suspicious`(이상 가격, 4-6 → "이상 가격 · 비교 제외"). `ok` 외에는 최저가 · 비교에서 빠짐 |
| `stockStatus` | `IN_STOCK` · `OUT_OF_STOCK` · `UNKNOWN`. 품절은 "품절" 표시, 최저가에서 빠짐 |
| `price` · `shipping` | 쇼핑몰 통화. 배송비는 상품별 값 > `shipping_rules`, 모르면 null + `calc.shippingKnown=false` |
| `title` | 쇼핑몰 원래 상품명 (`mall_products.listing_title`) |
| `seller` | 상품 링크 도메인 (예 `techdealz.com`). Shopify처럼 한 플랫폼에 상점이 여러 곳일 때만, 쇼핑몰 자체 도메인이면 null |
| `color` | 상품명에서 찾은 색상 (영문 대표 이름). 없거나 여러 색이면 null |
| `appliedEvent` | 적용된 행사. 없으면 null. `discountAmount`는 쇼핑몰 통화 할인액 |
| `calc.taxed` · `taxExempt` · `exemptionUsd` | 과세 여부 · 면세 여부 · 적용한 면세 한도 |
| `total` · `totalNormal` | 예상 구매비용 · 행사 · 쿠폰 없이 계산한 값(취소선) |
| `taxIncluded` · `shipDays` · `deliveryDays` · `shippingNote` | 1차에는 값 없음 (화면에서 숨김) |

### 6-3. 상품 상세 — `GET /api/products/{id}?coupon=true` (인증 불필요)

저장값으로 응답하되, 소스가 있는 판매처는 쇼핑몰 API로 단건 갱신을 시도한다 (3초). 판매처가 많으면 **가장 오래전에 받은 것부터 최대 6곳**만 갱신하고 나머지는 저장값 + stale 표시.

```json
{ "id": 61, "name": "...", "brand": null, "model": null, "spec": null, "description": null, "imageUrl": "...",
  "categoryName": "", "rating": null, "reviewCount": null,
  "badges": ["행사 진행 중", "최근 30일 최저"], "bestTotal": 1230060, "bestNormalTotal": 1265000, "bestStore": "eBay US",
  "offerCount": 14, "offers": [ Offer ], "variants": [ Offer ], "events": [ Event ],
  "rates": { "base": "KRW", "source": "한국수출입은행", "baseDate": "2026-11-20", "updatedAt": "...", "rates": { "USD": 1400 } },
  "priceStatus": "ok", "fetchedAt": "2026-11-22T16:04:20", "wished": false,
  "configs": [ { "productId": 61, "label": "256GB", "bestTotal": 1230060, "offerCount": 14, "storageGb": 256, "current": true } ] }
```
- `offers` · `variants`: 전체 판매처 (같은 내용).
- `priceStatus`: `ok` · `stale` · `unavailable`. `wished`: 로그인 시 찜 여부, 아니면 null.
- `colors`: 색상별 최저가 `[{name, bestTotal, offerCount}]` (비교 가능한 판매처 기준, 싼 색상부터, 색상이 2개 이상일 때만). 검색 결과 `colors`와 같은 계산. 화면은 이 값으로 색상 칩을 그리고, 칩을 고르면 `offers[].color`로 판매처를 거른다.
- `events`: 그 상품 판매처의 활성 · 진행 중 · 예정 행사.
- `configs`: 같은 모델의 다른 구성(용량 · 메모리 · 통신 방식, 4-5). `current`는 지금 보는 상품, `storageGb`는 GB당 가격 계산용. 구성이 하나뿐이거나 모르는 상품이면 빈 배열. 다른 구성 최저가는 판매처 1~2곳 상품의 이상 가격 판정 기준에도 쓴다.
- 상단 "사이트별 가격 한눈에" 그래프는 `offers[].calc`로 그린다 (별도 API 없음).
- 오류: 404 `PRODUCT_NOT_FOUND`.

### 6-4. 가격 변동 이력 — `GET /api/products/{id}/price-history?period=3m`

`period` = `1m` · `3m` · `6m` · `1y` (기본 1m, 그 외 400). 날짜별 최저 `total_amount_krw` (null 제외). 묶인 상품은 모든 판매처 중 최저.

```json
{ "productId": 61, "period": "3m",
  "points": [ { "date": "2026-10-01", "lowest": 1230000, "lowestWithCoupon": 1230000, "lowestTotalKrw": 1230000 } ],
  "summary": { "minKrw": 1187256, "avgKrw": 1215000, "maxKrw": 1254855 } }
```
이력은 쿠폰을 뺀 값만 저장하므로 `lowestWithCoupon`은 `lowest`와 같다 (프론트 그래프 호환용).

### 6-5. 환율 — `GET /api/exchange-rates`

```json
{ "base": "KRW", "source": "한국수출입은행", "baseDate": "2026-11-20", "updatedAt": "2026-11-22T16:04:14",
  "rates": { "USD": 1400, "JPY": 9.5, "EUR": 1474.21 },
  "items": [ { "currencyCode": "USD", "rateToKrw": 1400, "ttsRate": 1413.7, "baseDate": "2026-11-20", "recordedAt": "..." } ] }
```
통화별 최신 `base_date` 1건. JPY · IDR은 1단위 기준 (화면은 100엔 기준으로 표시). 데이터가 하나도 없으면 503.

### 6-6. 홈 — `GET /api/home`

```json
{ "categories": ["스마트폰", "노트북"], "hotKeywords": ["아이폰", "갤럭시", "맥북", "에어팟", "무선 헤드폰"],
  "events": [ Event ], "monthlyEvents": [ Event ], "popular": [] }
```
- 홈 행사 = 활성이고 이번 달과 기간이 겹치는 `store_events`. `hotKeywords`는 설정값(`globalprice.home.hot-keywords`).
- **Event** = `{ id, storeEventId, storeId, mallId, storeName, name, eventType, start, end, startAt, endAt, benefit, discountType, discountValue, minPurchaseAmount, maxDiscountAmount, url, mark, tag }`. `start` · `end`는 날짜(일정 미정이면 null → 화면 "[일정 확인]"), `benefit` ← `description`, `mark` · `tag`는 화면용.

### 6-7. 회원

| 메서드 · 주소 | 요청 | 성공 | 오류 |
| --- | --- | --- | --- |
| POST `/api/auth/signup` | `email`, `nickname`(≤50), `password`(8~72) | 201 `{userId, email, nickname, createdAt}` | 400, 409 |
| POST `/api/auth/login` | `email`, `password` | 200 `{userId, email, nickname, createdAt}` + `Set-Cookie: JSESSIONID` | 400, 401 `LOGIN_FAILED` |
| GET `/api/auth/me` | — | 200 (위와 같은 모양) | 401 `LOGIN_REQUIRED` |
| POST `/api/auth/logout` | — | 204 (세션 무효화) | 401 |

BCrypt, 로그인 성공 시 세션 ID 재발급(`request.changeSessionId()`), 세션 30분, 쿠키 HttpOnly · SameSite=Lax. 이메일은 소문자로 저장. 존재하지 않는 이메일도 BCrypt 비교를 한 번 해서 응답 시간으로 가입 여부가 드러나지 않게 한다.

### 6-8. 관심 상품 (로그인 필요)

| 메서드 · 주소 | 요청 | 성공 |
| --- | --- | --- |
| GET `/api/wishlist` | — | 200 배열 `[ ProductSummary + {wishlistId, productId, productName, lowestTotalKrw, createdAt} ]` |
| POST `/api/wishlist` | `{productId}` | 201 새로 추가 / 200 이미 있음 (멱등) |
| DELETE `/api/wishlist/{productId}` | — | 204 (없어도 204) |

찜 목록 가격은 저장된 최신값으로 계산한다 (외부 호출 없음, 이상 가격은 4-6 같은 규칙). 묶기로 상품이 합쳐지면 찜은 합쳐진 상품으로 옮겨진다.

### 6-9. 가격 알림 (로그인 필요)

| 메서드 · 주소 | 요청 | 성공 |
| --- | --- | --- |
| GET `/api/alerts` | — | 200 배열 `[ {alertId, alertBasis, productId, productName, storeEventId, eventName, targetPriceKrw, isActive, lastNotifiedAt, createdAt} ]` |
| POST `/api/alerts` | `{alertBasis, productId?, storeEventId?, targetPriceKrw?}` | 201 새로 / 200 같은 조건이면 목표가 UPDATE |
| DELETE `/api/alerts/{alertId}` | — | 204, 남의 것이면 404 |

| alertBasis | productId | storeEventId | targetPriceKrw | 프론트에서 만드는 곳 |
| --- | --- | --- | --- | --- |
| TARGET_PRICE | 필수 | 없음 | 필수 (0 초과) | 상세 "목표가 알림" |
| PERIOD_LOWEST | 필수 | 없음 | 없음 | 상세 "최근 30일 최저가 알림" |
| EVENT_START | 없음 | 필수 | 없음 | 홈 · 상세 행사 |
| COUPON_EXPIRY | 없음 | 필수 | 없음 | 홈 · 상세 행사 |

DB `chk_alert_target`과 같은 조합. 어기면 400.

### 6-10. 받은 알림 (로그인 필요)

- `GET /api/notifications?unread=true&page=&size=` → `{ unreadCount, items: [ {notificationId, alertId, alertBasis, message, productId, storeEventId, sentAt, readAt} ], page, size, totalElements, totalPages }`. `unreadCount`는 페이지와 무관한 전체 안 읽은 수.
- `PATCH /api/notifications/{id}/read` → 200 `{notificationId, readAt}` (이미 읽었으면 `readAt` 유지, 남의 것이면 404).

---

## 7. 스케줄러 세부

### 7-1. 환율 (한국수출입은행)

- `GET {url}?authkey=&searchdate=YYYYMMDD&data=AP01`. 사용 필드 `cur_unit` · `deal_bas_r` · `tts`. 숫자의 쉼표 제거, `JPY(100)` · `IDR(100)`은 100으로 나눔(소수 6자리), KRW 제외.
- `result` 1 성공 · 3 인증키 오류 · 4 일일 제한 초과. 빈 배열(주말 · 공휴일 · 11시 이전)이면 저장하지 않고 최근 값을 계속 사용.
- `(currency_code, base_date)` upsert. 환율을 한 번도 못 받은 통화의 상품은 계산에서 제외.
- 서버 시작 시 오늘 값이 없으면 하루씩 거슬러(최대 7일) 최근 영업일 값을 받는다 (주말에 켜도 금요일 값). 이미 있으면 호출 안 함.

### 7-2. 관심 상품 가격 갱신 (6시간마다)

1. 찜 또는 활성 알림이 걸린 상품을 모으고, 갱신 전에 상품별 최근 30일 최저가를 먼저 구한다.
2. 쇼핑몰별로 `refresh` 호출 (배치: Shopify 50 · AliExpress 20 · Etsy 100 · eBay 1). 5xx · 타임아웃은 1 · 2 · 4초 재시도, 401 · 429는 그 소스 중단.
3. 저장 → 가격 이력 → 계산(쿠폰 제외, 품절 제외) → 알림 판정. 한 건 실패는 건너뛰고 소스 단위로 `api_sync_logs` 1행.

저장 시각은 초 단위로 맞춘다 (DB DATETIME 반올림 때문에 방금 쓴 이력이 "갱신 전 30일 최저"에 섞여 PERIOD_LOWEST 알림이 누락되던 문제 해결).

### 7-3. 알림 판정

| alertBasis | 조건 |
| --- | --- |
| TARGET_PRICE | 현재 예상 구매비용(쿠폰 제외 · 품절 · 이상 가격 제외 최저) ≤ `target_price_krw` |
| PERIOD_LOWEST | 현재 예상 구매비용 < 갱신 전 최근 30일 `price_history` 최저 |
| EVENT_START | 행사 `start_at` 날짜가 오늘(KST)이고 활성 |
| COUPON_EXPIRY | COUPON 행사의 `end_at` 날짜가 내일(KST) |

같은 알림은 하루 1회 (`last_notified_at` 날짜가 오늘이면 건너뜀). `notifications` 저장과 `last_notified_at` 갱신은 한 트랜잭션.

---

## 8. 설정과 환경 변수

| 환경 변수 | 용도 | 없으면 |
| --- | --- | --- |
| `DB_URL` · `DB_USER` · `DB_PASSWORD` | MySQL 접속 | `localhost:3306/globalprice` · `root` · 빈 값 |
| `EBAY_CLIENT_ID` · `EBAY_CLIENT_SECRET` | eBay App ID · Cert ID | eBay skipped |
| `EBAY_SANDBOX` | `true`면 샌드박스 (한국 배송 상품 없음) | `true` → 실사용은 `false` |
| `SHOPIFY_AGENT_PROFILE` | Shopify 에이전트 프로필 URL | 예시 프로필 |
| `ETSY_API_KEY` | `keystring:shared_secret` | Etsy skipped |
| `ALI_APP_KEY` · `ALI_APP_SECRET` · `ALI_TRACKING_ID` | AliExpress 제휴 (tracking_id 선택) | AliExpress skipped |
| `KOREAEXIM_KEY` | 수출입은행 인증키 | 환율 수집 안 함 |
| `EBAY_ENABLED` · `SHOPIFY_ENABLED` · `ALI_ENABLED` · `ETSY_ENABLED` | 쇼핑몰 끄기 (`false`) | 모두 켜짐 |
| `DEVICE_CATALOG_ENABLED` | Google Play 기기 목록 자동 내려받기 끄기 (`false`). 끄면 DB 에 있는 행만 씀 | 켜짐 |

키 · DB 비밀번호는 OS 사용자 환경 변수로 한 번 등록한다 (Windows: PowerShell `[Environment]::SetEnvironmentVariable("이름", "값", "User")`, Mac: `~/.zshrc`에 `export`). 등록 후 열려 있던 터미널 · IDE는 다시 켠다.

그 밖의 값은 `application.yml`의 `globalprice.*`: 소스당 가져올 개수 20, 캐시 10분 · 500건, 타임아웃(연결 2초 · 읽기 3초 · 상세 갱신 3초), 429 쉬기 10분, stale 기준 6시간, 하루 예산 3,000, 홈 인기 검색어, 수수료 규칙 이름, 그리고 코드에 두지 않는 목록:
- `globalprice.filter`: 새 상품 아님 · 액세서리 키워드, 통신사 잠김 · 언락 · 시계 밴드 (정규식)
- `globalprice.outliers.installment-keywords`: 할부 · 계약금 표기 (정규식)
- `globalprice.display.platform-domains`: 쇼핑몰 자체 도메인 (상품 링크가 여기 속하면 판매자 도메인 `seller`를 null 로)
- `globalprice.device-catalog`: 기기 목록 `url`(Google Play 공개 CSV) · `refresh-after`(7일) · `enabled`

---

## 9. 보안

- API 키 · 시크릿은 환경 변수로만. 로그 · 오류 메시지에 키 · 토큰 · 서명 · Authorization · 세션 ID를 남기지 않음 (외부 오류 메시지에 URL · 응답 본문도 넣지 않음).
- 키 전달용 파일(`api_key.txt` 등)은 `.gitignore`로 제외돼 있으며, 저장소 · 압축 파일에 넣지 말고 따로 전달한다.
- 비밀번호 BCrypt, 응답 · 로그에 미포함 (요청 객체 `toString`도 가림). 로그인 시 세션 ID 재발급, 쿠키 HttpOnly · SameSite=Lax.
- 남의 찜 · 알림 · 받은 알림에 접근하면 404.
- 외부 응답 문자열(상품명 등)은 그대로 내려주고 서버에서 HTML로 렌더링하지 않음 (프론트가 이스케이프).
- 운영: HTTPS는 Nginx(:443), Spring Boot(:8080)는 내부에서만, MySQL(:3306)은 외부 차단.

---

## 10. 테스트

```
gradlew.bat test                 # 단위 테스트 (통합은 DB 가 없으면 건너뜀)
# 통합 테스트: Docker 가 켜져 있으면 Testcontainers(mysql:8.0) 자동,
# 또는 01~10 스크립트를 적용한 테스트 전용 DB 를 지정 (개발 DB 를 쓰지 말 것)
set GP_TEST_DB_URL=jdbc:mysql://127.0.0.1:3306/globalprice?serverTimezone=Asia/Seoul
gradlew.bat test --rerun         # 환경 변수만 바꿔 다시 돌릴 때는 --rerun
```

| 구분 | 내용 |
| --- | --- |
| PriceCalculator | 5장의 모든 경계 · 시드 기준 금액 (면세 한도 경계, 정률 할인 상한, 최소 구매금액 미달, 정액 할인, 할인 > 상품가, 1만원 미만 세금, 무료배송 경계, 원화 결제, 배송비 모름, JPY · IDR, 규칙 우선순위, 반올림 경계, 전신환매도율) |
| 소스 어댑터 | 가짜 서버로 정상 · 429 · 503 · 지연 · 401 · 깨진 JSON · 값 누락. 실제 응답 샘플(`src/test/resources/samples`), Shopify JSON · SSE, AliExpress 서명 · tracking_id 유무 · 출발국 CN, 수출입은행 파싱 |
| 환율 서비스 | 주말이면 직전 영업일, 이미 최신이면 호출 없음, 키 없음 · 인증 오류면 중단 |
| 묶기 · 출발국 · 이상 가격 · 색상 · 모델코드 | `ProductMatcherTest` · `OriginTest` · `PriceOutliersTest` · `OutlierServiceTest` · `ColorNameTest` · `ModelCodeTest` · `SameProductGrouperTest` · `ProductGroupingTest` |
| 모델 목록 | `GalaxyModelCodeTest` (09 시드 행마다 번호 키 = 모델명 키) · `DeviceCatalogTest` (실제 CSV 행으로 읽기 · 가장 긴 이름 · 모델번호 · 5G/4G 판 · 애매하면 안 묶음). 통합 테스트는 기기 목록을 내려받지 않음 (`device-catalog.enabled=false`) |
| 검색 서비스 | 가짜 소스 3개: 일부 실패 200, 전부 실패 503 · 502, 캐시 히트 시 외부 호출 0회, 걸러내기 · 정렬 · 필터 · 페이지 · 예산 · 쿨다운, 관련도(`RelevanceTest`), 체크표(`FacetTest`) |
| 통합 (실제 MySQL) | API 전체 흐름, 시드 금액, 저장 멱등 · 이력 중복 방지, stale 표시, 관심 상품 갱신 → 알림 · 하루 1회, 행사 알림 |

현재 158개 (통합 테스트는 DB 없으면 건너뜀). 묶기 판단은 메모리 Store 로(`ProductGroupingTest`), 키워드 목록은 실제 `application.yml`을 읽어서(`support/RealConfig`) 확인한다. **실패 1개**: `SearchServiceTest.partialFailure_returns200WithSourceStatuses` — 관련도 정렬 도입 후 순서 기대값을 고치지 않음 (13장 기본 정렬 결정 후 정리).

---

## 11. DB 스크립트 · 실행 순서

`GlobalPrice_DB/GlobalPrice_DB` 폴더. MySQL 8.0.16 이상 (CHECK · 함수형 UNIQUE 때문에 H2 · MariaDB 불가). 모든 마이그레이션은 이미 적용돼 있으면 건너뛰어 여러 번 실행해도 안전하다.

| 순서 | 스크립트 | 내용 |
| --- | --- | --- |
| 1 | `01_create_database.sql` · `02_create_tables.sql` | 기준 스키마 19개 테이블 (수정하지 않음, 변경은 마이그레이션으로) |
| 2 | `03_seed_data.sql` | 테스트 데이터 (Amazon US · Rakuten, 시드 기준 금액). **06보다 먼저** (컬럼 이름 없이 INSERT) |
| 3 | `03_migration_001.sql` | `exchange_rates.tts_rate` 추가 |
| 4 | `04_seed_dev.sql` | 쇼핑몰 5개 · api_sources 6개 · 배송 규칙 · 세금 규칙(기본 $150 · 미국 $200, 관세 0% · 부가세 10%) · 수수료 1% · 테스트 행사 |
| 5 | `05_migration_002.sql` | `search_aliases` 테이블 + 별칭 데이터 |
| 6 | `06_migration_003.sql` | `mall_product_variants.normalized_option_key` (백엔드 미사용) |
| 7 | `07_migration_004.sql` | **필수** — `products.match_key`(UNIQUE) · `mall_products.listing_title`. 없으면 검색 · 상세가 실패 |
| 8 | `08_migration_005.sql` | **필수** — `search_aliases.alias_source`(MANUAL · AUTO) + 한영 별칭 약 180개 (휴대폰 · 액세서리 · 생활가전 · 브랜드 · 색상). 없으면 한글 검색어가 번역되지 않음 |
| 9 | `09_migration_006.sql` | `model_codes` 테이블 + 갤럭시 모델번호 39개 (S22~S25 · Z Fold/Flip 3~7 · A 시리즈). 없으면 내장 11개만 써서 Fold5 · A 시리즈 등 번호만 쓴 상품명이 안 묶임 |
| 10 | `10_migration_007.sql` | `device_models` 테이블 + `model_codes.source` · `brand`. 행은 서버가 Google Play 기기 목록에서 채움. 없으면 아이폰 · 갤럭시 · 픽셀 · 맥북 · 아이패드 밖의 폰이 안 묶임 |
| (선택) | `90_demo_price_history.sql` | 시연용 가짜 가격 이력 1년치. 운영 DB 금지. 지우기: `DELETE FROM price_history WHERE TIME(recorded_at) = '04:44:44'` |

---

## 12. 구현 상태

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| 1 | 골격 · DB 연결 · 오류 처리 · 세션 · 설정 | 완료 |
| 2 | 환율 수집 · 조회 | 완료 (실제 키) |
| 3 | PriceCalculator + 단위 테스트 | 완료 |
| 4 | MallSource + eBay + 저장 규칙 | 완료 (운영 키) |
| 5 | 검색 API (별칭 · 걸러내기 · 동시 호출 · 캐시 · 계산 · 정렬) | 완료 |
| 6 | 상세 · 가격 이력 · 단건 갱신 | 완료 |
| 7 | 회원 · 관심 상품 | 완료 |
| 8 | 관심 상품 갱신 스케줄러 · 알림 판정 · 받은 알림 | 완료 |
| 9 · 10 · 11 | Shopify · AliExpress · Etsy | 완료 (실제 확인) |
| 12 | EVENT_START · COUPON_EXPIRY 알림 | 완료 |
| 13 | 같은 상품 묶기 · 다른 구성 · 체크표 · 색상별 최저가 · 이상 가격 · 관련도 정렬 | 완료 (11/18~) |
| 14 | 검색어 별칭 DB · 갤럭시 모델번호 DB · Google Play 기기 목록으로 휴대폰 · 태블릿 묶기 | 완료 (11/26~27) |
| + | 프론트 연결 (Gradle 이 `GlobalPrice_frontend/static`을 복사하며 `USE_MOCK=false`로 바꿈) | 완료 |

---

## 13. 팀 확정 · 남은 일

| # | 항목 | 현재 상태 · 가정 |
| --- | --- | --- |
| 1 | 기본 정렬 | 관련도 → 가격순 (6-2). 순수 가격순으로 할지 확정 필요. 정해지면 실패 테스트 1개 정리 |
| 2 | 묶기 기준 이원화 | 백엔드는 `match_key`(07). DB 쪽 `search_aliases`(05) · `normalized_option_key`(06)는 `(brand_id, model_number)` 기준으로 설계됐지만 백엔드가 안 씀. 하나로 정할 것. (별칭 관리는 11/26 부터 `search_aliases` 하나로 통일. 모델번호 · 기기 모델은 11/27 부터 `model_codes` · `device_models`(09 · 10)를 `match_key` 기준으로 씀) |
| 3 | AliExpress tracking_id | 없음 → 구매 링크가 제휴 링크 대신 상품 페이지. AliExpress Portals 에서 발급 필요 |
| 4 | 검색 품질 | AliExpress 브랜드명 검색에 무관 상품, 키워드 걸러내기의 한계 (이름에 표시 없는 케이스 · 부품). 판매처 1곳뿐인 상품은 이상 가격을 못 잡을 수 있음 |
| 5 | 결제 수수료율 | 시드 1% (실제는 카드 브랜드 1~1.1% + 카드사 0.25% 정도) |
| 6 | 배송 규칙 값 | `04_seed_dev`의 임시값 (eBay $20, Shopify $15, AliExpress $3 · $10 이상 무료, Etsy $15) |
| 7 | 기본 세금 규칙 | 전자기기 가정 관세 0%. 카테고리를 모르는 상품의 관세율 |
| 8 | Shopify 에이전트 프로필 | 예시 fixture 사용 중. 운영 전 팀 프로필 호스팅 필요 |
| 9 | Etsy 포함 여부 | 켜져 있음. 결과 성격상 끌지는 팀 결정 (`ETSY_ENABLED=false`) |
| 10 | 각 API 약관 | 가격 저장 · 재표시 · 비교 허용, 제휴 링크 표시 의무 (eBay 제휴 링크는 EPN 캠페인 ID 필요). Google Play 기기 목록은 안내 페이지에 사용 조건이 따로 적혀 있지 않음 (공개 CSV) |
| 11 | 호출 한도 | `daily-budget` 3,000 (eBay · Etsy 실제 한도 하루 5,000) |
| 12 | 응답 시간 | 캐시 미스 약 1.3~2.8초(실측), 캐시 히트 약 0.1초. "p95 1초"는 캐시 히트에만 적용 가능 |
| 13 | AliExpress 해외 창고 | 출발국 CN 고정이라 해외 창고(미국 등) 발송 상품도 $150 한도로 계산됨 (비용을 낮게 잡는 쪽은 아님) |
| 14 | Eclipse 실행 | 화면 파일은 Gradle 빌드가 복사하므로 Eclipse 에서 바로 실행하면 화면이 안 나올 수 있음 → `gradlew bootRun` 또는 Gradle Tasks 의 `bootRun` 으로 실행 |

---

## 부록. 변경 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-11-13 | v1.0 작성 (구현 전 명세) |
| 2026-11-15 ~ 17 | v1.1: 실제 API 호출 결과 반영 (eBay · AliExpress 최저가 정렬 제거, eBay 원래 통화, Etsy 관련도순 · 이미지 추가 조회 · `keystring:shared_secret`, Shopify USD 요청 · 구매 링크 우선순위, AliExpress tracking_id 선택), 응답 필드를 프론트 API_SPEC 이름에 맞춤, 원화 수수료 면제는 KR 쇼핑몰만, 무료배송 기준은 할인 전 가격, 환율 서버 시작 시 최근 영업일 수집, PERIOD_LOWEST 누락 수정, 행사 알림 09:00, 4개 쇼핑몰 기본 켬, 키는 사용자 환경 변수 |
| 2026-11-18 ~ 20 | 같은 상품 묶기(`match_key` · `listing_title`, 마이그레이션 002~004), 다른 구성 · 체크표 · 색상별 최저가, 이상 가격(`suspicious`), 모델코드 일치, 관련도 정렬, 출발국 통화 추정(`Origin`), 걸러내기 보강(통신사 잠김 · 박스만 · 시계 밴드 · for 브랜드 · 키워드 확대 · 복수형), 별칭 19개 추가, 상세 갱신 최대 6곳 |
| 2026-11-22 | AliExpress 출발국 CN 고정 (USD라서 미국 $200 한도로 계산되던 문제). 명세 문서를 이 파일 하나로 통합 (v1.1 docx · 프론트 `API_SPEC.md` 두 벌 삭제) |
| 2026-11-26 | 검색어 별칭을 `aliases.csv` 대신 DB `search_aliases`에서 읽음 (10분마다 갱신), `aliases.csv` 삭제. 08 마이그레이션: `alias_source` 컬럼 + 별칭 약 180개. 한글 "핸드폰 케이스" · "아이폰 17 케이스" 검색에 케이스가 안 나오던 문제 해결 |
| 2026-11-26 | 구조 정리: 상세 500(match_key NULL NPE) 수정, 출발국 · 색상 · 묶기 키를 OfferData 에서 한 번만 계산, SearchService 를 SearchRanker · FacetBuilder 로 분리, 묶기 판단을 ProductGrouping 으로, 이상 가격을 OutlierService 하나로 (찜 목록 · 알림 판정도 같은 규칙), 하드코딩 목록을 application.yml 로, 프론트 static 복사본을 Gradle 자동 복사로, 상세 `colors` 추가(프론트 색상 판단 코드 삭제) |
| 2026-11-27 | 갤럭시 모델번호를 코드 대신 DB `model_codes`(09 마이그레이션, 39개)에서 읽음 (10분마다 갱신). 모델 하나에 번호 여러 개, `SM-` 없는 번호도 인식. Fold5 · A 시리즈 등 내장 목록에 없던 모델도 같은 상품으로 묶임 |
| 2026-11-27 | 휴대폰 · 태블릿 모델 목록: Google Play 지원 기기 CSV 를 받아 DB `device_models` · `model_codes`(10 마이그레이션)에 넣고, 상품명 속 모델 이름 · 모델번호로 샤오미 · 원플러스 · 모토로라 등도 같은 상품으로 묶음 (`dev|` 키). 갤럭시 번호도 이 목록에서 채움. `256/512GB` 같은 용량 목록을 용량 여러 개로 읽도록 수정 |
| 2026-11-27 | 모델코드에서 `inches` · `oz` · `lb` 단위와 칩 이름(Dimensity · Snapdragon · Helio 등) 제외. `13-inches` · `16.2oz` · `Dimensity7300` 이 모델코드로 잡혀 다른 상품끼리 묶이던 문제 |
| 2026-11-27 | 문서 동기화: 기기 목록(외부 데이터 · 스케줄 · 구조 · `DEVICE_CATALOG_ENABLED` · `device-catalog` 설정), 테스트 158개 · 01~10 스크립트, 13장 2 · 10번, 12장 상태. ERD 에 마이그레이션 004~007 컬럼 · 테이블 · UNIQUE · CHECK(27개) 반영 |
