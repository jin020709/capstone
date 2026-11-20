/* 홈: 카테고리 · 인기 검색어 · 행사 · 인기 상품 · 계산기 */
(function () {
  var U = GP.ui, P = GP.price;

  function periodText(e) {
    if (!e.start) return '[일정 확인]';
    return U.fmtDay(e.start).replace('.', '. ') + ' ~ ' + U.fmtDay(e.end).replace('.', '. ');
  }

  function renderLoginBox() {
    var user = GP.api.me();
    U.$('#loginBox').innerHTML = user
      ? '<p style="margin:0 0 10px;font-size:14px"><b>' + U.esc(user.nickname) + '</b>님, 관심 상품 가격을 확인해 보세요.</p><a class="btn btn-primary" style="width:100%" href="mypage.html">마이페이지</a>'
      : '<p style="margin:0 0 10px;font-size:14px;line-height:1.6">로그인하고 관심 상품 가격 하락 알림을 받아보세요.</p><a class="btn btn-primary" style="width:100%" href="login.html">로그인</a>' +
        '<p class="small" style="text-align:center;margin:10px 0 0"><a href="signup.html">회원가입</a></p>';
  }

  function productCard(p) {
    var strike = p.bestNormalTotal > p.bestTotal ? '<span class="price-strike">' + P.won(p.bestNormalTotal) + '</span>' : '';
    var badge = (p.badges || []).length ? '<span class="badge badge-event" style="align-self:flex-start">' + U.esc(p.badges[0]) + '</span>' : '';
    return '<a class="card product-card" href="detail.html?id=' + p.id + '">' +
      '<div class="thumb">' + (p.imageUrl ? '<img src="' + U.esc(p.imageUrl) + '" alt="">' : '[상품 이미지]') + '</div>' +
      '<div class="body">' + badge +
        '<span class="name">' + U.esc(p.name) + '</span>' +
        '<span class="small muted">판매처 ' + p.offerCount + '곳 · 최저 ' + U.esc(p.bestStore) + '</span>' +
        '<span style="display:flex;align-items:baseline;gap:8px;margin-top:4px"><span class="price" style="font-size:20px">' + P.won(p.bestTotal) + '</span>' + strike + '</span>' +
      '</div></a>';
  }

  // config.js STORES 에 있는 (= 실제로 검색되는) 쇼핑몰인지
  function known(storeId) {
    return (GP.config.STORES || []).some(function (s) { return s.id === storeId; });
  }

  function loadHome() {
    GP.api.getHome().then(function (d) {
      U.$('#catMenu').innerHTML = d.categories.map(function (c) {
        return '<a href="search.html?q=' + encodeURIComponent(c.split(' · ')[0]) + '">' + U.esc(c) + '<span aria-hidden="true">›</span></a>';
      }).join('');
      U.$('#hotList').innerHTML = d.hotKeywords.map(function (k, i) {
        return '<li><a href="search.html?q=' + encodeURIComponent(k) + '"><b>' + (i + 1) + '</b>' + U.esc(k) + '</a></li>';
      }).join('');
      var hot = d.hotKeywords || [], first = hot[0] || '';
      // 히어로 버튼: 예시 검색어(러닝화) 대신 서버가 준 첫 인기 검색어로
      var heroBtn = U.$('#heroSearch');
      if (heroBtn && first) { heroBtn.href = 'search.html?q=' + encodeURIComponent(first); if (!GP.config.USE_MOCK) heroBtn.textContent = "'" + first + "' 최저가 보기"; }
      var events = d.events || [];
      U.$('#eventList').innerHTML = events.length ? events.map(function (e) {
        // 행사 카드: 목업은 예시 검색으로, 실제 서버는 쇼핑몰 행사 페이지(url)가 있으면 새 탭으로, 없으면 그 쇼핑몰 행사 상품 검색
        var live = !GP.config.USE_MOCK;
        var href = !live ? 'search.html?q=' + encodeURIComponent(first || '러닝화') + '&benefits=event'
          : (e.url && /^https?:\/\//.test(e.url) ? e.url : 'search.html?q=' + encodeURIComponent(first) + '&benefits=event' + (known(e.storeId) ? '&stores=' + encodeURIComponent(e.storeId) : ''));
        var ext = live && href.indexOf('http') === 0 ? ' target="_blank" rel="noopener"' : '';
        return '<div class="card event-card"><a class="event-link" href="' + U.esc(href) + '"' + ext + '>' +
          '<div style="display:flex;gap:10px;align-items:center">' + (e.mark ? '<span class="mark">' + U.esc(e.mark) + '</span>' : '') +
          '<div><div class="small muted">' + U.esc(e.storeName) + '</div><div style="font-size:17px;font-weight:900">' + U.esc(e.name) + '</div></div></div>' +
          '<span class="period">' + periodText(e) + '</span>' +
          (e.benefit ? '<span style="font-size:13px;line-height:1.6">' + U.esc(e.benefit) + '</span>' : '') +
          (e.tag ? '<span class="chip" style="align-self:flex-start;font-size:12px">' + U.esc(e.tag) + '</span>' : '') + '</a>' +
          U.eventAlerts.buttonsHTML(e) + '</div>';
      }).join('') : U.stateHTML('empty', '이번 달에 등록된 행사가 아직 없어요.');
      // 인기 상품은 1차 범위 밖이라 서버가 빈 배열을 줌 → 빈 제목만 남지 않게 영역을 숨김
      var popular = d.popular || [];
      U.$('#popularSection').classList.toggle('hidden', !popular.length);
      U.$('#popularList').innerHTML = popular.map(productCard).join('');
    }).catch(function (e) {
      U.$('#popularList').innerHTML = U.errorHTML(e, 'homeRetry');
      var r = U.$('#homeRetry'); if (r) r.addEventListener('click', loadHome);
    });
  }

  // 계산기 통화: 서버에 환율이 있는 통화만 (없는 통화를 고르면 계산이 안 되므로)
  function syncCurrencies() {
    var rates = GP.rates && GP.rates.rates;
    if (!rates) return;
    var sel = U.$('#calcForm').currency, ok = false;
    U.$all('option', sel).forEach(function (o) { var has = rates[o.value] != null; o.disabled = !has; o.hidden = !has; if (has && o.value === sel.value) ok = true; });
    if (!ok) { var firstOk = U.$all('option', sel).filter(function (o) { return !o.disabled; })[0]; if (firstOk) sel.value = firstOk.value; }
  }

  function runCalc() {
    var f = U.$('#calcForm');
    var rates = (GP.rates || GP.mock.exchangeRates).rates;
    if (rates[f.currency.value] == null || rates.USD == null) {
      U.$('#calcResult').innerHTML = U.stateHTML('empty', '이 통화의 환율 정보가 아직 없어 계산할 수 없어요.');
      return;
    }
    var r = P.calcQuick({
      price: f.price.value, currency: f.currency.value, shipping: f.shipping.value,
      coupon: f.coupon.value, category: f.category.value
    }, rates);
    var limit = '$' + r.exemptionUsd, pt = P.parts(r);
    U.$('#calcResult').innerHTML =
      '<div class="row"><span class="muted">상품가 − 쿠폰 (원화)</span><b>' + P.won(pt.item) + '</b></div>' +
      '<div class="row"><span class="muted">배송비</span><b>' + P.won(pt.ship) + '</b></div>' +
      '<div class="row"><span class="muted">관세 · 부가세</span><b>' + P.won(pt.tax) + '</b></div>' +
      '<div class="row"><span class="muted">해외결제 수수료</span><b>' + P.won(pt.fee) + '</b></div>' +
      '<div class="total"><span style="font-weight:700">예상 구매비용</span><strong>' + P.won(r.totalAmountKrw) + '</strong></div>' +
      '<div class="small" style="font-weight:700;color:' + (r.taxed ? 'var(--discount)' : 'var(--primary)') + '">' +
      (r.taxed ? '면세 기준(' + limit + ')을 넘어 관세 · 부가세가 붙어요' : '면세 기준(' + limit + ') 이하라 관세 · 부가세가 없어요') + '</div>';
  }

  document.addEventListener('DOMContentLoaded', function () {
    renderLoginBox();
    GP.api.ready.then(renderLoginBox); // 서버 세션 확인 후 다시 그림
    loadHome();
    // 행사 알림 버튼: 로그인 확인 → 이미 건 알림 불러와서 버튼 상태 맞춤
    U.eventAlerts.bind();
    GP.api.ready.then(U.eventAlerts.load).then(function () { U.$all('.ev-alert-btn').forEach(U.eventAlerts.paint); });
    U.$('#calcForm').addEventListener('input', runCalc);
    runCalc();
    document.addEventListener('gp:rates', function () { syncCurrencies(); runCalc(); }); // 실제 환율이 오면 다시 계산
  });
})();
