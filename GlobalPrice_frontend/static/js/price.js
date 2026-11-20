/* =========================================================
   예상 구매비용 계산 (가격 계산 · 환율 계산 모듈)
   DB 02_create_tables.sql 의 price_calculations 계산 기준과 같은 식
     과세가격   = 상품가 + 배송비 − 할인            (원화 환산, 항목마다 먼저 반올림)
     면세 판정  = 과세가격(USD 환산) ≤ 면세 한도면 관세 · 부가세 0  (미국 $200, 그 외 $150)
     관세       = 과세가격 × 관세율
     부가세     = (과세가격 + 관세) × 부가세율
     결제수수료 = (상품가 + 배송비 − 할인) × 수수료율 + 고정 수수료   (외화 결제만)
     합계       = 상품가 + 배송비 − 할인 + 관세 + 부가세 + 결제수수료
   할인: 행사(store_events) 1건만. RATE 는 최대 할인금액으로 상한, 최소 구매금액 미만이면 미적용

   ※ 실제 서비스에서는 백엔드 PriceCalculator 가 계산해 내려주고, 화면은 그 값을 그대로 표시.
     이 파일은 목업 모드와 홈 계산기에서만 씀.
   ========================================================= */
window.GP = window.GP || {};

GP.price = (function () {
  var R = GP.config.PRICE_RULES;

  function toKRW(amount, currency, rates) {
    if (!amount) return 0;
    if (currency === 'KRW') return Math.round(amount);
    var rate = rates[currency];
    if (!rate) throw new Error('환율 정보 없음: ' + currency);
    return Math.round(amount * rate);
  }

  /** 행사 할인액 (쇼핑몰 통화) — store_events 할인 정책 */
  function discountOf(price, d) {
    if (!d || !d.discountType) return 0;
    if (d.minPurchaseAmount != null && price < d.minPurchaseAmount) return 0;
    var v = d.discountType === 'RATE' ? price * d.discountValue : d.discountValue;
    if (d.discountType === 'RATE' && d.maxDiscountAmount != null) v = Math.min(v, d.maxDiscountAmount);
    return Math.min(v, price); // 할인은 상품가까지만
  }

  /**
   * 판매처 1곳의 예상 구매비용 계산
   * @param {object} offer    { currency, price, shipping, discount, taxIncluded, originCountry }
   *                          discount = { discountType: 'RATE'|'AMOUNT', discountValue, minPurchaseAmount, maxDiscountAmount }
   * @param {string} category 품목 (관세율 키)
   * @param {object} rates    1 단위당 원화 { USD: 1400, JPY: 9.5, EUR: 1510 }
   * @param {boolean} useCoupon 행사 · 쿠폰 적용 여부
   * @return 필드 이름은 price_calculations 컬럼과 같음 (camelCase)
   */
  function calcOffer(offer, category, rates, useCoupon) {
    var cur = offer.currency;
    var discCur = useCoupon ? discountOf(offer.price, offer.discount) : 0;
    var productPriceKrw = toKRW(offer.price, cur, rates);
    var shippingFeeKrw = toKRW(offer.shipping || 0, cur, rates);
    var discountAmountKrw = Math.min(toKRW(discCur, cur, rates), productPriceKrw);
    // 과세가격 = 물품가격 + 국제배송비 (관세청: 과세 시 운임 포함)
    var taxable = productPriceKrw + shippingFeeKrw - discountAmountKrw;
    // 면세 판정은 물품가격(실제 결제한 상품값)으로만 함 — 국제배송비는 빼고 봄 (관세청 안내)
    var itemKrw = productPriceKrw - discountAmountKrw;
    var exemptionUsd = R.dutyFreeUSD[offer.originCountry] || R.dutyFreeUSD.DEFAULT;

    var duty = 0, vat = 0, taxed = false;
    if (!offer.taxIncluded && itemKrw / rates.USD > exemptionUsd) {
      taxed = true;
      var dutyRate = R.dutyRates[category] != null ? R.dutyRates[category] : R.dutyRates.general;
      duty = Math.round(taxable * dutyRate);
      vat = Math.round((taxable + duty) * R.vatRate);
      // 징수금액 최저한: 세관이 걷을 세액 합계가 1만원 미만이면 걷지 않음 (관세법 제40조)
      if (duty + vat < (R.minTaxKrw || 0)) { duty = 0; vat = 0; }
    }
    var fee = cur !== 'KRW' ? Math.round(taxable * R.cardFeeRate) + (R.fixedFeeKrw || 0) : 0;

    return {
      productPriceKrw: productPriceKrw,
      shippingFeeKrw: shippingFeeKrw,
      discountAmountKrw: discountAmountKrw,
      customsDutyKrw: duty,
      vatKrw: vat,
      paymentFeeKrw: fee,
      totalAmountKrw: taxable + duty + vat + fee,
      // 화면 표시용 (DB 컬럼 아님)
      taxed: taxed,
      taxIncluded: !!offer.taxIncluded,
      exemptionUsd: exemptionUsd,
      discountApplied: discountAmountKrw > 0,
      discountAmount: discCur          // 쇼핑몰 통화 기준 할인액
    };
  }

  /** 홈 계산기용: 단일 상품 */
  function calcQuick(input, rates) {
    var offer = {
      currency: input.currency,
      price: Number(input.price) || 0,
      shipping: Number(input.shipping) || 0,
      discount: Number(input.coupon) ? { discountType: 'AMOUNT', discountValue: Number(input.coupon) } : null,
      taxIncluded: false,
      originCountry: input.currency === 'USD' ? 'US' : 'OTHER'
    };
    return calcOffer(offer, input.category, rates, true);
  }

  function won(n) { return '₩' + Math.round(n).toLocaleString('ko-KR'); }
  function won2(n) { return '₩' + Number(n).toLocaleString('ko-KR', { maximumFractionDigits: 2 }); } // 환율 표시용 (1,400.5)

  function money(amount, currency) {
    if (currency === 'KRW') return won(amount);
    var sym = { USD: '$', JPY: '¥', EUR: '€', GBP: '£', CNY: 'CN¥' }[currency] || (currency || '') + ' ';
    var digits = currency === 'JPY' ? 0 : 2;
    return sym + Number(amount).toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }

  // 비교 · 최저가 대상: 가격이 있고 품절이 아닌 판매처
  // suspicious = 서버가 판정한 이상 가격 (할부 · 계약금 표기, 같은 상품 중간값의 1/3 미만) → 비교 · 최저가에서 뺌
  function comparable(o) { return o.status !== 'unavailable' && o.status !== 'suspicious' && o.total != null && o.stockStatus !== 'OUT_OF_STOCK'; }

  // 순서: 비교 대상(예상 구매비용순) → 품절 → 조회 불가
  function sortOffers(list) {
    var rank = function (o) { return o.total == null || o.status === 'unavailable' ? 2 : (o.stockStatus === 'OUT_OF_STOCK' ? 1 : 0); };
    return list.slice().sort(function (a, b) {
      var ra = rank(a), rb = rank(b);
      if (ra !== rb) return ra - rb;
      return ra === 2 ? 0 : a.total - b.total;
    });
  }

  // 화면에서 자주 쓰는 묶음 값
  function parts(c) {
    return { item: c.productPriceKrw - c.discountAmountKrw, ship: c.shippingFeeKrw, tax: c.customsDutyKrw + c.vatKrw, fee: c.paymentFeeKrw };
  }

  // 판매처 참고 정보 (경고가 아니라 사실만): 눈에 띄게 싼 가격 · 후기 정보 없음 · 한국 배송 미확인
  // 같은 상품의 비교 가능한 판매처가 3곳 이상일 때 중간값보다 20% 이상 싸면 표시 (이상 가격 1/3 기준까지는 아님)
  var CHEAP_PCT = 20;
  function sellerNotes(o, all) {
    var notes = [];
    var t = (all || []).filter(comparable).map(function (x) { return x.total; }).sort(function (a, b) { return a - b; });
    if (comparable(o) && t.length >= 3) {
      var n = t.length, med = n % 2 ? t[(n - 1) / 2] : (t[n / 2 - 1] + t[n / 2]) / 2;
      var pct = Math.round((1 - o.total / med) * 100);
      if (pct >= CHEAP_PCT) {
        notes.push('다른 판매처보다 ' + pct + '% 저렴');
        // eBay 는 판매자 평점이 있는 마켓, 나머지(개별 Shopify 상점 등)는 후기 데이터를 받을 수 없음
        if (!/ebay/i.test(o.storeName || '')) notes.push('판매자 후기 정보 없음');
      }
    }
    // eBay(deliveryCountry:KR) · Shopify(ships_to KR) · AliExpress(ship_to_country=KR)는 한국 배송 가능 상품만 받음. Etsy 는 확인 안 됨
    if (/etsy/i.test(o.storeName || '')) notes.push('한국 배송 미확인');
    return notes;
  }
  function notesHTML(o, all) {
    var n = sellerNotes(o, all);
    return n.length ? '<span class="seller-notes">' + n.map(function (x) { return '<span>' + x + '</span>'; }).join('') + '</span>' : '';
  }

  return { sellerNotes: sellerNotes, notesHTML: notesHTML, calcOffer: calcOffer, calcQuick: calcQuick, discountOf: discountOf, parts: parts, comparable: comparable, toKRW: toKRW, won: won, won2: won2, money: money, sortOffers: sortOffers };
})();
