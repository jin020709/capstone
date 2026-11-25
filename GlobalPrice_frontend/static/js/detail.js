/* 상품 상세: 판매처 비교표 · 쿠폰 스위치 · 가격 변동 그래프 · 찜 · 목표가 알림 */
(function () {
  var U = GP.ui, P = GP.price;
  var id = U.param('id') || '1';
  var useCoupon = true, period = '6m', chart = null, product = null, history = null;

  function bind(name, html, attr) {
    var el = U.$('[data-bind="' + name + '"]');
    if (!el) return;
    if (attr) el.setAttribute(attr, html); else el.innerHTML = html;
  }
  function cssVar(n) { return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }

  var MOCK = GP.config.USE_MOCK;

  function eventPeriod(e) {
    if (!e.start) return '[일정 확인]';
    return U.fmtDay(e.start) + '~' + U.fmtDay(e.end);
  }

  function available() { return product.offers.filter(P.comparable); }

  function eventText(o) {
    var e = o.appliedEvent;
    if (!useCoupon || !e) return '—';
    var rate = e.discountType === 'RATE' ? ' (' + Math.round(e.discountValue * 100) + '%' + (e.maxDiscountAmount != null ? ', 최대 ' + P.money(e.maxDiscountAmount, o.currency) : '') + ')' : '';
    return U.esc(e.eventName) + ' −' + P.money(e.discountAmount, o.currency) + '<div class="small muted">' + rate + '</div>';
  }
  function stockBadge(o) {
    if (o.stockStatus === 'OUT_OF_STOCK') return '<div><span class="badge badge-soldout">품절</span></div>';
    if (o.stockStatus === 'UNKNOWN') return '<div><span class="badge">재고 확인 필요</span></div>';
    return '';
  }
  function subLine(o) {
    var bits = [o.seller, U.originLabel(o)];
    if (o.variantName && o.variantName !== 'DEFAULT') bits.push(o.variantName);
    var d = o.deliveryDays;
    if (d && d[0] != null) bits.push('배송 ' + (d[0] === d[1] ? '약 ' + d[0] : d[0] + '~' + d[1]) + '일'); // 서버는 [n, n] 으로 줌
    return U.esc(bits.filter(Boolean).join(' · '));
  }

  // 같은 상품으로 묶인 판매처마다 쇼핑몰에 올라온 원래 상품명 (색상 · 통신사 등은 여기서 확인)
  function listingTitle(o) {
    return o.title && o.title !== product.name ? '<div class="listing-title" title="' + U.esc(o.title) + '">' + U.esc(o.title) + '</div>' : '';
  }

  // 대체값 3단계: stale → "N시간 전 가격" 배지, unavailable → 조회 불가 줄
  function staleBadge(o) {
    if (o.status === 'suspicious') return '<div><span class="badge badge-soldout" title="할부 · 계약금 표기이거나 같은 상품 다른 판매처보다 터무니없이 싸서 비교에서 뺐어요. 실제 총액은 판매처에서 확인하세요.">이상 가격 · 비교 제외</span></div>';
    return o.status === 'stale' ? '<div><span class="badge badge-stale" title="판매처 갱신 실패 — 마지막으로 저장된 가격">' + U.esc(U.ago(o.updatedAt)) + ' 가격</span></div>' : '';
  }
  function unavailableRow(o) {
    return '<tr class="unavailable">' +
      '<td class="store"><div class="store-name">' + U.esc(o.storeName) + '</div><div class="small muted">' + U.esc([o.seller, U.originLabel(o)].filter(Boolean).join(' · ')) + '</div></td>' +
      '<td colspan="3" class="unavailable-msg">일시적으로 가격을 불러오지 못했어요. 다음 갱신 때 다시 확인해요.</td>' +
      '<td class="buy"><a class="btn btn-line btn-sm" href="' + U.esc(o.url) + '" target="_blank" rel="noopener">판매처 보기</a></td>' +
    '</tr>';
  }

  // 세부: 계산식 한 줄 (원래 통화는 옆에 작게, ₩0 · 없는 항목은 뺌) + 기준 한 줄
  function breakdownLine(o, c, pt, shipUnknown, e) {
    var sub = function (t) { return t ? ' <small>' + t + '</small>' : ''; };
    var item = function (label, won, extra) { return '<span class="bd-item"><small class="bd-label">' + label + '</small> ' + won + sub(extra) + '</span>'; };
    var foreign = o.currency !== 'KRW';
    var parts = [item('판매가', P.won(c.productPriceKrw), foreign ? U.esc(P.money(o.price, o.currency)) : '')];
    if (c.discountAmountKrw) parts.push('<span class="bd-op">−</span>' + item('할인', P.won(c.discountAmountKrw), e ? U.esc(e.eventName) : ''));
    parts.push('<span class="bd-op">+</span>' + (shipUnknown ? item('배송비', '확인 필요', '합계 미포함')
      : item('배송비', c.shippingFeeKrw ? P.won(c.shippingFeeKrw) : '무료', c.shippingFeeKrw && foreign && o.shipping ? U.esc(P.money(o.shipping, o.currency)) : '')));
    if (c.customsDutyKrw) parts.push('<span class="bd-op">+</span>' + item('관세', P.won(c.customsDutyKrw)));
    if (c.vatKrw) parts.push('<span class="bd-op">+</span>' + item('부가세', P.won(c.vatKrw)));
    if (c.paymentFeeKrw) parts.push('<span class="bd-op">+</span>' + item('수수료', P.won(c.paymentFeeKrw)));
    parts.push('<span class="bd-op">=</span><span class="bd-item bd-total">' + P.won(o.total) + '</span>');
    var basis = [o.taxIncluded ? '관부가세 판매가 포함' : '면세 기준 $' + Number(c.exemptionUsd) + (c.taxed ? ' 초과 → 과세' + (pt.tax ? '' : ' (세액 1만원 미만 ₩0)') : ' 이하 → 면세')];
    if (o.exchangeRate && foreign) basis.push(U.esc(o.exchangeRate.currencyCode) + ' 1 = ' + P.won2(o.exchangeRate.rateToKrw) + (o.exchangeRate.baseDate ? ' (' + U.esc(o.exchangeRate.baseDate.slice(5).replace('-', '.')) + ' 고시)' : ''));
    return '<div class="bd-line">' + parts.join('') + '</div><div class="bd-basis">' + basis.join(' · ') + '</div>';
  }

  // 다나와식: 한 줄에는 판매처 · 예상 구매비용 · 배송비 · 혜택만, 나머지는 "세부"를 펼쳐서
  function offerRow(o, i) {
    if (o.status === 'unavailable') return unavailableRow(o);
    var c = o.calc, pt = P.parts(c);
    // 배송비: 쇼핑몰이 준 값 > 배송비 규칙(추정) > 모름. 모르면 합계에 빠져 있으니 "무료"로 보이면 안 됨
    var shipUnknown = c.shippingKnown === false;
    var ship = shipUnknown ? '<span style="color:#8A5A00">확인 필요</span>' : (c.shippingFeeKrw ? P.won(c.shippingFeeKrw) : '무료');
    var e = useCoupon ? o.appliedEvent : null;
    var perk = c.discountAmountKrw && e ? '<span class="perk" title="' + U.esc(e.eventName) + '">−' + P.won(c.discountAmountKrw) + '</span>' : '<span class="muted">—</span>';
    var best = i === 0 && P.comparable(o) && product.offers.length > 1 ? ' <span class="badge" style="background:var(--accent);color:var(--on-accent)">최저</span>' : '';
    var rowId = 'bd-' + i; // 같은 쇼핑몰 판매처가 여러 건일 수 있어 순번으로
    var priceKrw = o.currency === 'KRW' ? (o.taxIncluded ? '관부가세 포함가' : '원화 표시가') : '≈ ' + P.won(c.productPriceKrw);
    var badges = staleBadge(o) + stockBadge(o);
    return '<tr class="offer-main' + (i === 0 ? ' best' : '') + '">' +
      '<td class="store"><div class="store-name">' + U.esc(o.storeName) + best + '</div><div class="small muted">' + subLine(o) + '</div>' + P.notesHTML(o, product.offers) + badges + '</td>' +
      '<td data-label="예상 구매비용"><span class="total">' + P.won(o.total) + '</span></td>' +
      '<td data-label="배송비">' + ship + '</td>' +
      '<td data-label="혜택"' + (c.discountAmountKrw && e ? '' : ' class="no-perk"') + '>' + perk + '</td>' +
      '<td class="buy"><button type="button" class="breakdown-btn" aria-expanded="false" aria-controls="' + rowId + '">세부</button>' +
        '<a class="btn ' + (i === 0 ? 'btn-primary' : 'btn-line') + ' btn-sm" href="' + U.esc(o.url) + '" target="_blank" rel="noopener">구매</a></td>' +
    '</tr>' +
    '<tr class="hidden offer-detail" id="' + rowId + '"><td colspan="5">' +
      (o.title ? '<div class="listing-title full">' + U.esc(o.title) + '</div>' : '') +
      breakdownLine(o, c, pt, shipUnknown, e) +
    '</td></tr>';
  }

  function renderProduct() {
    var p = product;
    var av = available();
    if (!av.length) {
      U.$('#detailRoot').innerHTML = U.errorHTML({ code: 'EXTERNAL_API_UNAVAILABLE' }, 'retryBtn');
      U.$('#retryBtn').addEventListener('click', function () { location.reload(); });
      return;
    }
    // 실제 서버는 카테고리 · 스펙 · 모델명이 비어 있을 수 있음 (쇼핑몰 API 가 안 줌) → 있는 것만 표시
    U.$('#breadcrumb').innerHTML = '<a href="index.html">홈</a> › ' +
      (p.categoryName ? '<a href="search.html?q=' + encodeURIComponent(p.categoryName.split(' · ')[0]) + '">' + U.esc(p.categoryName) + '</a> › ' : '') + '<span>' + U.esc(p.name) + '</span>';
    document.title = p.name + ' — GlobalPrice';
    bind('badges', p.badges.map(function (b) { return '<span class="badge' + (b.indexOf('행사') > -1 ? ' badge-event' : '') + '">' + U.esc(b) + '</span>'; }).join('') +
      '<span class="badge badge-primary">판매처 ' + av.length + '곳</span>');
    bind('name', U.esc(p.name));
    var specBits = [];
    if (p.brand) specBits.push(U.esc(p.brand));
    if (p.spec) specBits.push(U.esc(p.spec));
    if (p.model) specBits.push('모델명 ' + U.esc(p.model));
    if (p.rating != null) specBits.push('평점 ' + p.rating + ' (' + (p.reviewCount || 0).toLocaleString('ko-KR') + ')');
    bind('spec', specBits.join(' · '));
    if (p.imageUrl) U.$('.product-summary .thumb').innerHTML = '<img src="' + U.esc(p.imageUrl) + '" alt="' + U.esc(p.name) + '" referrerpolicy="no-referrer">';
    bind('desc', p.description ? U.esc(p.description) : '');
    var one = p.offers.length === 1; // 1차는 상품 1건 = 판매처 1곳이 대부분
    bind('bestLabel', (useCoupon ? '쿠폰 적용 ' : '') + (one ? '' : '최저 ') + '예상 구매비용' + (useCoupon ? '' : ' (쿠폰 미적용)'));
    bind('best', P.won(p.bestTotal));
    bind('bestNormal', useCoupon && p.bestNormalTotal > p.bestTotal ? P.won(p.bestNormalTotal) : '');
    var top = av[0];
    bind('bestStore', U.esc(top.storeName) + (useCoupon && top.appliedEvent ? ' · ' + U.esc(top.appliedEvent.eventName) : ''));
    bind('buyLink', top.url, 'href');
    bind('count', String(av.length) + (av.length < p.offers.length ? ' / ' + p.offers.length : ''));
    var nStale = av.filter(function (o) { return o.status === 'stale'; }).length;
    var nOff = p.offers.filter(function (o) { return o.status === 'unavailable'; }).length;
    var nSold = p.offers.filter(function (o) { return o.status !== 'unavailable' && o.stockStatus === 'OUT_OF_STOCK'; }).length;
    var note = U.$('#offerStatusNote'), parts = [];
    var nShip = av.filter(function (o) { return o.calc && o.calc.shippingKnown === false; }).length;
    if (nStale) parts.push(nStale + '곳은 판매처 갱신이 늦어 마지막으로 저장된 가격을 보여줘요');
    if (nShip) parts.push(nShip + '곳은 배송비를 알 수 없어 합계에 배송비가 빠져 있어요');
    if (nSold) parts.push(nSold + '곳은 품절이라 최저가에서 뺐어요');
    var nSus = p.offers.filter(function (o) { return o.status === 'suspicious'; }).length;
    if (nSus) parts.push(nSus + '곳은 할부 · 계약금 표기이거나 다른 판매처보다 터무니없이 싸서(이상 가격) 비교에서 뺐어요');
    if (nOff) parts.push(nOff + '곳은 일시적으로 조회할 수 없어 비교에서 뺐어요');
    note.textContent = parts.join(' · ') + (parts.length ? '.' : '');
    note.classList.toggle('hidden', !parts.length);
    renderColors();
    renderOffers();
    renderConfigs();
    U.$('#eventRows').innerHTML = (p.events || []).length ? p.events.map(function (e) {
      return '<div class="event-row"><div class="event-row-head"><span><b>' + U.esc(e.name) + '</b> <span class="muted">· ' + U.esc(e.storeName) + '</span></span>' +
        '<span style="color:var(--primary);font-weight:700;flex:none">' + eventPeriod(e) + '</span></div>' + U.eventAlerts.buttonsHTML(e) + '</div>';
    }).join('') : '<p class="small muted" style="margin:0">현재 적용 가능한 행사가 없어요.</p>';
    var rt = p.rates || {}, rmap = rt.rates || {};
    U.$('#rateBasis').textContent = ['환율 ' + (U.rateBasis(rt) || '—'), rt.source, rmap.USD != null ? 'USD ' + Number(rmap.USD).toLocaleString('ko-KR') : null].filter(Boolean).join(' · ');
    renderWish();
  }

  // ---------- 색상: 같은 상품으로 묶인 판매처를 색상으로 거르기 ----------
  // 색상 판단(offers[].color)과 색상별 최저가(colors)는 서버가 한다 → 검색 결과의 색상 줄과 항상 같은 값
  var colorFilter = U.param('color') || '';   // 검색 결과의 색상 줄에서 들어오면 그 색상을 골라 둠
  function colorOf(o) { return o.color || ''; }
  function renderColors() {
    var box = U.$('#colorChips');
    var colors = product.colors || [];   // 싼 색상부터, 색상이 2개 이상일 때만 옴
    if (colorFilter && !colors.some(function (c) { return c.name === colorFilter; })) colorFilter = '';
    box.classList.toggle('hidden', colors.length < 2);
    if (colors.length < 2) { colorFilter = ''; return; }
    var chip = function (value, label) {
      return '<button type="button" class="chip" data-color="' + U.esc(value) + '" aria-pressed="' + String(colorFilter === value) + '">' + U.esc(label) + '</button>';
    };
    box.innerHTML = '<span class="small muted" style="align-self:center">색상</span>' + chip('', '전체') +
      colors.map(function (c) { return chip(c.name, c.name + (c.bestTotal != null ? ' ' + P.won(c.bestTotal) : '') + ' · ' + c.offerCount + '곳'); }).join('');
  }
  function renderOffers() {
    var list = colorFilter ? product.offers.filter(function (o) { return colorOf(o) === colorFilter; }) : product.offers;
    U.$('#offerRows').innerHTML = list.map(offerRow).join('');
  }

  // ---------- 다른 구성 (다나와식): 같은 모델의 다른 용량 · 구성 상품으로 이동 ----------
  function renderConfigs() {
    var cs = product.configs || [];
    U.$('#configBox').classList.toggle('hidden', cs.length < 2);
    if (cs.length < 2) return;
    var cheapest = Math.min.apply(null, cs.map(function (c) { return c.bestTotal == null ? Infinity : c.bestTotal; }));
    U.$('#configGrid').innerHTML = cs.map(function (c) {
      var perGb = c.bestTotal != null && c.storageGb ? Math.round(c.bestTotal / c.storageGb).toLocaleString('ko-KR') + '원/1GB' : '';
      var inner = '<div class="config-label">' + U.esc(c.label) + (c.current ? ' <span class="badge badge-primary">보는 중</span>' : '') +
          (c.bestTotal === cheapest && cs.length > 1 ? ' <span class="badge">최저</span>' : '') + '</div>' +
        '<div class="config-price">' + (c.bestTotal != null ? P.won(c.bestTotal) : '가격 정보 없음') + '</div>' +
        '<div class="small muted">' + [perGb, '판매처 ' + c.offerCount + '곳'].filter(Boolean).join(' · ') + '</div>';
      return c.current ? '<div class="config-card current" aria-current="page">' + inner + '</div>'
        : '<a class="config-card" href="detail.html?id=' + encodeURIComponent(c.productId) + '">' + inner + '</a>';
    }).join('');
  }

  function renderWish() {
    var b = U.$('#wishBtn');
    var on = GP.api.isWished(id);
    b.setAttribute('aria-pressed', String(on));
    b.textContent = on ? '♥ 관심 상품 해제' : '♡ 관심 상품';
  }


  /**
   * 가격 변동 그래프 (다나와식): 선 하나 · 점 표시 · 최저가만 라벨 · 세로축 · 격자 없음
   *  - 기록이 많으면 구간별 최저가로 묶어 점을 14개 안팎으로 (날마다 그리면 들쭉날쭉해 보임)
   *  - 마지막 점은 묶지 않고 "현재" 로 표시
   *  - 쿠폰 스위치가 켜져 있으면 쿠폰 적용가, 꺼져 있으면 미적용가 한 줄만
   */
  var MAX_POINTS = 14;
  function bucketize(rows) {
    if (rows.length <= MAX_POINTS) return rows;
    var last = rows[rows.length - 1], body = rows.slice(0, -1);
    var size = Math.ceil(body.length / (MAX_POINTS - 1)), out = [];
    for (var i = 0; i < body.length; i += size) {
      var grp = body.slice(i, i + size), best = grp[0];
      grp.forEach(function (r) { if (r.v < best.v) best = r; });
      out.push({ date: grp[grp.length - 1].date, v: best.v });
    }
    out.push(last);
    return out;
  }
  // 실제 기록이 3개월 안쪽이면 "11.03"(월.일), 그보다 길면 "26.03"(연.월). 탭이 아니라 실제 기록 기간 기준
  function tickLabel(date, i, n, shortSpan) {
    if (i === n - 1) return '현재';
    return shortSpan ? Number(date.slice(5, 7)) + '.' + date.slice(8, 10) : date.slice(2, 4) + '.' + date.slice(5, 7);
  }

  // 최저가 점 위에 금액 라벨 (Chart.js 플러그인)
  var minLabelPlugin = {
    id: 'gpMinLabel',
    afterDatasetsDraw: function (ch, args, opts) {
      var meta = ch.getDatasetMeta(0), idx = opts.index;
      if (!meta || idx == null || !meta.data[idx]) return;
      var pt = meta.data[idx], c = ch.ctx, text = P.won(opts.value).replace('₩', '') + '원';
      c.save();
      c.font = '700 13px ' + opts.font;
      c.fillStyle = opts.color;
      var n = meta.data.length;
      c.textAlign = n > 2 && idx === 0 ? 'left' : n > 2 && idx === n - 1 ? 'right' : 'center';
      // 최저점 아래에는 선이 지나갈 수 없으므로 항상 점 아래에 씀 (위에 쓰면 내려오는 선과 겹침)
      c.textBaseline = 'top';
      c.fillText(text, pt.x, pt.y + 10);
      c.restore();
    }
  };

  function drawChart() {
    if (!history) return;
    var raw = (history.points || []).filter(function (p) { return (p.lowest != null ? p.lowest : p.lowestTotalKrw) != null; });
    var box = U.$('.chart-box');
    if (!U.$('#priceChart')) { box.innerHTML = '<canvas id="priceChart" role="img" aria-label="기간별 최저 예상 구매비용 그래프"></canvas>'; chart = null; }
    // 이력은 검색되거나 갱신된 날에만 쌓임 → 새로 조회된 상품은 비어 있을 수 있음
    if (!raw.length) {
      if (chart) { chart.destroy(); chart = null; }
      box.innerHTML = U.stateHTML('empty', '선택한 기간에 쌓인 가격 기록이 아직 없어요.');
      U.$('#historyStats').innerHTML = '';
      U.$('#historyNote').textContent = '관심 상품에 담으면 6시간마다 가격을 기록해요.';
      return;
    }
    var rows = raw.map(function (p) {
      var normal = p.lowest != null ? p.lowest : p.lowestTotalKrw;
      var withC = p.lowestWithCoupon != null ? p.lowestWithCoupon : normal;
      return { date: p.date, v: useCoupon ? withC : normal };
    });
    // 통계는 묶기 전 전체 기록 기준 (서버 summary 가 있으면 그 값)
    var vals = rows.map(function (r) { return r.v; }), sm = history.summary || {};
    var sameAsServer = !useCoupon || raw.every(function (p) { return p.lowestWithCoupon == null || p.lowestWithCoupon === p.lowest; });
    var min = sameAsServer && sm.minKrw != null ? sm.minKrw : Math.min.apply(null, vals);
    var max = sameAsServer && sm.maxKrw != null ? sm.maxKrw : Math.max.apply(null, vals);
    var avg = sameAsServer && sm.avgKrw != null ? sm.avgKrw : Math.round(vals.reduce(function (a, b) { return a + b; }, 0) / vals.length);
    U.$('#historyStats').innerHTML =
      '<div><span class="small muted">기간 최저</span><b style="color:var(--discount)">' + P.won(min) + '</b></div>' +
      '<div><span class="small muted">기간 평균</span><b>' + P.won(avg) + '</b></div>' +
      '<div><span class="small muted">기간 최고</span><b>' + P.won(max) + '</b></div>';
    var note = [];
    if (rows.length > 1 && vals[vals.length - 1] <= min) note.push('지금이 선택한 기간 중 가장 낮은 가격이에요.');
    if (rows.length === 1) note.push('기록이 하루치뿐이에요. 조회 · 갱신될 때마다 쌓여요.');
    if (MOCK) note.push('예시 데이터');
    else if (useCoupon && sameAsServer) note.push('저장된 이력은 쿠폰 미적용 기준이에요.');
    U.$('#historyNote').textContent = note.join(' ');

    if (typeof Chart === 'undefined') {
      U.$('.chart-box').innerHTML = U.stateHTML('error', '그래프 라이브러리를 불러오지 못했어요 (인터넷 연결 확인).');
      return;
    }
    var pts = bucketize(rows);
    var data = pts.map(function (r) { return r.v; });
    var spanDays = (new Date(pts[pts.length - 1].date) - new Date(pts[0].date)) / 864e5;
    var labels = pts.map(function (r, i) { return tickLabel(r.date, i, pts.length, spanDays <= 100); }); // 3개월까지는 "8.17", 그 이상은 "26.08"
    var minIdx = 0;
    data.forEach(function (v, i) { if (v <= data[minIdx]) minIdx = i; }); // 같은 최저가면 최근 점에 라벨
    // x축 글자: 처음 · 마지막("현재") 포함 5개 안팎만 고르게 (자동 생략에 맡기면 "현재"가 빠짐)
    var tickN = Math.min(5, labels.length), shown = {};
    for (var t = 0; t < tickN; t++) shown[Math.round(t * (labels.length - 1) / Math.max(tickN - 1, 1))] = true;
    // 값이 거의 일정하면 위아래 여백을 금액의 5% 로 (선이 바닥에 붙거나 과하게 출렁여 보이지 않게)
    var lo = Math.min.apply(null, data), hi = Math.max.apply(null, data), pad = Math.max((hi - lo) * 0.35, hi * 0.05);

    var line = cssVar('--chart-1'), dot = cssVar('--chart-2'), hot = cssVar('--discount'), sub = cssVar('--text-sub'), border = cssVar('--border'),
      surface = cssVar('--surface'), font = cssVar('--font');
    var radius = data.map(function (v, i) { return i === minIdx ? 5 : 3.5; });
    if (chart) chart.destroy();
    chart = new Chart(U.$('#priceChart'), {
      type: 'line',
      data: {
        labels: labels,
        datasets: [{
          label: useCoupon ? '쿠폰 적용 최저가' : '최저 예상 구매비용', data: data,
          borderColor: line, borderWidth: 2.5, cubicInterpolationMode: 'monotone', // 점 사이를 부드럽게 잇되 넘치지 않게
          pointRadius: radius, pointHoverRadius: 6, pointBorderWidth: 2,
          pointBackgroundColor: data.map(function (v, i) { return i === minIdx ? surface : dot; }),
          pointBorderColor: data.map(function (v, i) { return i === minIdx ? hot : dot; })
        }]
      },
      options: {
        responsive: true, maintainAspectRatio: false, interaction: { mode: 'index', intersect: false },
        layout: { padding: { top: 12, bottom: 4, left: 8, right: 8 } },
        plugins: {
          legend: { display: false },
          gpMinLabel: { index: minIdx, value: data[minIdx], color: hot, font: font },
          tooltip: {
            displayColors: false,
            callbacks: {
              title: function (items) { var r = pts[items[0].dataIndex]; return items[0].dataIndex === pts.length - 1 ? '현재' : r.date.replace(/-/g, '.'); },
              label: function (ctx) { return P.won(ctx.parsed.y); }
            }
          }
        },
        scales: {
          x: {
            grid: { display: false }, border: { color: border }, offset: data.length < 3, // 점이 1~2개면 가운데로
            ticks: { color: sub, autoSkip: false, maxRotation: 0, font: { family: font, size: 12 },
              callback: function (v, i) { return shown[i] ? labels[i] : ''; } }
          },
          y: { display: false, suggestedMin: lo - pad * 1.4, suggestedMax: hi + pad * 0.5 }
        }
      },
      plugins: [minLabelPlugin]
    });
  }

  function loadHistory() {
    if (!product) return;
    U.$all('.period-tabs button').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.period === period)); });
    GP.api.getPriceHistory(id, period).then(function (h) { history = h; drawChart(); })
      .catch(function (e) {
        var box = U.$('.chart-box');
        if (chart) { chart.destroy(); chart = null; }
        box.innerHTML = U.errorHTML(e, 'historyRetry');
        var r = U.$('#historyRetry');
        if (r) r.addEventListener('click', function () { box.innerHTML = '<canvas id="priceChart" role="img" aria-label="가격 변동 그래프"></canvas>'; loadHistory(); });
      });
  }

  function loadProduct(first) {
    return GP.api.getProduct(id, useCoupon).then(function (p) {
      product = p;
      if (first) {
        var root = U.$('#detailRoot');
        root.innerHTML = '';
        root.appendChild(U.$('#detailTpl').content.cloneNode(true));
        U.applyMode(root);
        wire();
      }
      U.$('#couponToggle').setAttribute('aria-checked', String(useCoupon));
      renderProduct();
    }).catch(function (e) {
      if (first) {
        U.$('#detailRoot').innerHTML = U.errorHTML(e, 'retryBtn');
        var r = U.$('#retryBtn');
        if (r) r.addEventListener('click', function () { U.$('#detailRoot').innerHTML = U.stateHTML('loading', '다시 불러오는 중…'); loadProduct(true).then(loadHistory); });
      } else {
        U.toast(U.errorInfo(e).title + ' — 잠시 후 다시 시도해 주세요.');
      }
    });
  }

  function wire() {
    U.$('#colorChips').addEventListener('click', function (e) {
      var b = e.target.closest('[data-color]');
      if (!b) return;
      colorFilter = b.getAttribute('data-color');
      renderColors();
      renderOffers();
    });
    U.$('#couponToggle').addEventListener('click', function () { useCoupon = !useCoupon; loadProduct(false).then(drawChart); });
    U.$('#offerRows').addEventListener('click', function (e) {
      var btn = e.target.closest('.breakdown-btn');
      if (!btn) return;
      var row = document.getElementById(btn.getAttribute('aria-controls'));
      var open = btn.getAttribute('aria-expanded') === 'true';
      btn.setAttribute('aria-expanded', String(!open));
      row.classList.toggle('hidden', open);
      btn.closest('tr').classList.toggle('open', !open);
    });
    U.$all('.period-tabs button').forEach(function (b) {
      b.addEventListener('click', function () { period = b.dataset.period; loadHistory(); });
    });
    U.$('#wishBtn').addEventListener('click', function () {
      GP.api.toggleWish(id).then(function () { renderWish(); U.toast(GP.api.isWished(id) ? '관심 상품에 담았어요.' : '관심 상품에서 뺐어요.'); })
        .catch(function (e) { if (e.status === 401) location.href = 'login.html?next=' + encodeURIComponent(location.pathname.split('/').pop() + location.search); else U.toast(e.message); });
    });
    if (!GP.config.FEATURES.alerts) {
      // 2차 확장 기능: 자리만 보여주고 입력은 막음
      var box = U.$('#alertBox');
      box.classList.add('is-disabled');
      U.$all('#alertForm input, #alertForm button').forEach(function (el) { el.disabled = true; });
      U.$('#alertForm button').textContent = '준비 중';
      U.$('#alertMsg').textContent = '목표 가격 알림은 2차 확장 기능이에요. 지금은 관심 상품(찜)으로 저장해 두세요.';
    } else U.$('#alertForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var v = Number(U.$('#targetPrice').value);
      var msg = U.$('#alertMsg');
      if (!v || v < 1000) { msg.textContent = '목표 가격을 1,000원 이상으로 입력해 주세요.'; return; }
      if (v >= product.bestTotal) { msg.textContent = '현재 최저가(' + P.won(product.bestTotal) + ')보다 낮게 입력해 주세요.'; return; }
      GP.api.createAlert(id, v).then(function () { msg.textContent = P.won(v) + ' 이하가 되면 알려드릴게요. 마이페이지에서 확인할 수 있어요.'; })
        .catch(function (err) { msg.textContent = err.status === 401 ? '로그인 후 알림을 설정할 수 있어요.' : err.message; });
    });
    // 최근 30일 최저가 알림 (alertBasis: PERIOD_LOWEST) — 목표가 없이 상품만으로 설정
    var lowBtn = U.$('#lowestAlertBtn'), lowAlertId = null;
    var paintLow = function () {
      lowBtn.setAttribute('aria-pressed', String(!!lowAlertId));
      lowBtn.textContent = lowAlertId ? '✓ 최근 30일 최저가 알림 받는 중 (끄기)' : '최근 30일 최저가보다 내려가면 알림';
    };
    var findLow = function () {
      if (!GP.api.me()) { lowAlertId = null; paintLow(); return Promise.resolve(); }
      return GP.api.getAlerts().then(function (list) {
        var a = (list || []).filter(function (x) { return x.alertBasis === 'PERIOD_LOWEST' && String(x.productId) === String(id); })[0];
        lowAlertId = a ? a.id : null; paintLow();
      }).catch(function () { /* 상태를 몰라도 버튼은 동작 */ });
    };
    if (lowBtn) {
      if (!GP.config.FEATURES.alerts) lowBtn.disabled = true;
      else {
        GP.api.ready.then(findLow);
        lowBtn.addEventListener('click', function () {
          var msg = U.$('#alertMsg');
          lowBtn.disabled = true;
          var job = lowAlertId
            ? GP.api.deleteAlert(lowAlertId).then(function () { lowAlertId = null; msg.textContent = '최근 30일 최저가 알림을 껐어요.'; })
            : GP.api.createAlert(id, null, 'PERIOD_LOWEST').then(function () { msg.textContent = '최근 30일 최저가보다 내려가면 알려드릴게요.'; return findLow(); });
          job.catch(function (err) { msg.textContent = err.status === 401 ? '로그인 후 알림을 설정할 수 있어요.' : err.message; })
            .then(function () { lowBtn.disabled = false; paintLow(); });
        });
      }
    }
    new MutationObserver(function () { drawChart(); }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  }

  document.addEventListener('DOMContentLoaded', function () {
    U.eventAlerts.bind();
    // 이미 건 행사 알림을 먼저 불러와 두면 상품을 그릴 때 버튼 상태가 바로 맞음
    GP.api.ready.then(U.eventAlerts.load).then(function () { U.$all('.ev-alert-btn').forEach(U.eventAlerts.paint); });
    loadProduct(true).then(loadHistory);
  });
})();
