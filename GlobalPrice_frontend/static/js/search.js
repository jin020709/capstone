/* 검색 결과: 필터 · 정렬 · 쿠폰 적용가 스위치 · 페이지 · URL 동기화
   실제 서버 모드 기준은 백엔드 명세 v1.1 6-2 (GET /api/products) */
(function () {
  var U = GP.ui, P = GP.price, cfg = GP.config;
  var state = { q: '', sort: 'total', coupon: true, stores: [], countries: [], benefits: [], inStock: false, minPrice: null, maxPrice: null, page: 0,
    brands: [], models: [], storages: [], colors: [] };
  var FACETS = [['brands', '제조사'], ['models', '주요 모델'], ['storages', '용량'], ['colors', '색상']];
  var reqId = 0;
  var SORTS = cfg.USE_MOCK ? ['total', 'popular', 'rating', 'discount'] : ['total', 'discount']; // 인기 · 평점순은 1차 미지원

  function num(v) { var n = Number(v); return v !== null && v !== '' && isFinite(n) && n >= 0 ? Math.floor(n) : null; }

  function readURL() {
    var sp = new URLSearchParams(location.search);
    state.q = (sp.get('q') || '').trim();
    state.sort = SORTS.indexOf(sp.get('sort')) > -1 ? sp.get('sort') : 'total';
    state.coupon = sp.get('coupon') !== 'false';
    ['stores', 'countries', 'benefits', 'brands', 'models', 'storages', 'colors'].forEach(function (k) { state[k] = sp.get(k) ? sp.get(k).split(',') : []; });
    state.inStock = sp.get('inStock') === 'true';
    state.minPrice = num(sp.get('minPrice'));
    state.maxPrice = num(sp.get('maxPrice'));
    state.page = Math.max(0, (parseInt(sp.get('page'), 10) || 1) - 1); // 주소는 1부터, 서버는 0부터
  }
  function writeURL() {
    var sp = new URLSearchParams();
    if (state.q) sp.set('q', state.q);
    if (state.sort !== 'total') sp.set('sort', state.sort);
    if (!state.coupon) sp.set('coupon', 'false');
    ['stores', 'countries', 'benefits', 'brands', 'models', 'storages', 'colors'].forEach(function (k) { if (state[k].length) sp.set(k, state[k].join(',')); });
    if (state.inStock) sp.set('inStock', 'true');
    if (state.minPrice != null) sp.set('minPrice', state.minPrice);
    if (state.maxPrice != null) sp.set('maxPrice', state.maxPrice);
    if (state.page > 0) sp.set('page', state.page + 1);
    history.replaceState(null, '', 'search.html' + (sp.toString() ? '?' + sp : ''));
  }

  // 판매처 필터: 목업은 예시 쇼핑몰, 실제 서버는 연동된 쇼핑몰(config.js STORES)
  function renderStoreFilter() {
    var list = cfg.USE_MOCK
      ? Object.keys(GP.mock.stores).map(function (id) { return { id: id, name: GP.mock.stores[id].name }; })
      : cfg.STORES;
    U.$('#storeFilter').innerHTML = list.map(function (s) {
      return '<label><input type="checkbox" name="stores" value="' + U.esc(s.id) + '">' + U.esc(s.name) + '<span class="small muted" data-store-note="' + U.esc(s.id) + '"></span></label>';
    }).join('');
  }
  function syncControls() {
    U.$all('#filterForm input[type=checkbox]').forEach(function (cb) {
      cb.checked = cb.name === 'inStock' ? state.inStock : (state[cb.name] || []).indexOf(cb.value) > -1;
    });
    U.$('#minPrice').value = state.minPrice != null ? state.minPrice : '';
    U.$('#maxPrice').value = state.maxPrice != null ? state.maxPrice : '';
    U.$all('.sort-group button').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.sort === state.sort)); });
    U.$('#couponToggle').setAttribute('aria-checked', String(state.coupon));
  }

  function itemHTML(it) {
    var offers = (it.offers || []).map(function (o) {
      var ev = state.coupon && o.appliedEvent ? '<span class="badge badge-event">' + U.esc(o.appliedEvent.eventName) + '</span>' : '';
      var st = o.status === 'stale' ? '<span class="badge badge-stale">' + U.esc(U.ago(o.updatedAt)) + ' 기준</span>' : '';
      var sold = o.stockStatus === 'OUT_OF_STOCK' ? '<span class="badge badge-soldout" style="margin-top:0">품절</span>' : '';
      if (o.status === 'suspicious') sold += '<span class="badge badge-soldout" style="margin-top:0" title="할부 · 계약금 표기이거나 터무니없이 싼 가격이라 비교에서 뺐어요">이상 가격</span>';
      // 배송비를 쇼핑몰도 규칙도 모르는 경우: 합계에 배송비가 빠져 있음 (calc.shippingKnown = false)
      var ship = o.calc && o.calc.shippingKnown === false ? '<span class="badge badge-stale">배송비 미포함</span>' : '';
      var origin = U.originLabel(o);
      // 판매처 줄을 누르면 그 판매처 상품 페이지로 바로 이동 (다나와식). 원래 상품명은 마우스를 올리면 보임
      var who = '<b>' + U.esc(o.storeName) + '</b>' + (o.seller ? ' <span class="seller">' + U.esc(o.seller) + '</span>' : '') + (origin ? ' <span class="muted">· ' + U.esc(origin) + '</span>' : '') + P.notesHTML(o, it.offers);
      var inner = '<span class="store">' + who + '</span>' + ev + st + sold + ship +
        '<span class="val">' + (o.total != null ? P.won(o.total) : '조회 불가') + '</span>';
      return o.url ? '<a class="offer-line" href="' + U.esc(o.url) + '" target="_blank" rel="noopener"' + (o.title ? ' title="' + U.esc(o.title) + '"' : '') + ' aria-label="' + U.esc(o.storeName + (o.seller ? ' ' + o.seller : '') + ' 상품 페이지로 이동') + '">' + inner + '<span class="go" aria-hidden="true">›</span></a>'
        : '<div class="offer-line">' + inner + '</div>';
    }).join('');
    var badges = (it.badges || []).map(function (b) { return '<span class="badge' + (b.indexOf('행사') > -1 ? ' badge-event' : '') + '">' + U.esc(b) + '</span>'; }).join(' ');
    var strike = state.coupon && it.bestNormalTotal > it.bestTotal ? ' <span class="price-strike">' + P.won(it.bestNormalTotal) + '</span>' : '';
    var meta = [it.brand, it.categoryName].filter(Boolean).join(' · ') + (it.rating != null ? ' · 평점 ' + it.rating + ' · 리뷰 ' + (it.reviewCount || 0).toLocaleString('ko-KR') : '');
    // 같은 상품은 서버가 1줄로 묶어 줌 (offerCount = 묶인 판매처 수). 1곳이면 "상세 보기"
    var cta = it.offerCount > 1 ? '판매처 ' + it.offerCount + '곳 비교' : '계산 내역 · 상세 보기';
    var more = it.offerCount > (it.offers || []).length ? '<a class="offer-more" href="detail.html?id=' + it.id + '">+ 판매처 ' + (it.offerCount - it.offers.length) + '곳 더 보기</a>' : '';
    // 색상이 2개 이상이면 다나와처럼 색상별 최저가 줄 (누르면 그 색상을 고른 상세로)
    var colors = it.colors || [];
    if (colors.length >= 2) {
      var MAXC = 4;
      offers = colors.slice(0, MAXC).map(function (c, i) {
        return '<a class="offer-line color-line" href="detail.html?id=' + it.id + '&color=' + encodeURIComponent(c.name) + '">' +
          '<span class="store">' + (i === 0 ? '<span class="badge badge-primary" style="margin:0 4px 0 0">최저</span>' : '') + '<b>' + U.esc(c.name) + '</b> <span class="muted">· 판매처 ' + c.offerCount + '곳</span></span>' +
          '<span class="val">' + P.won(c.bestTotal) + '</span><span class="go" aria-hidden="true">›</span></a>';
      }).join('');
      more = colors.length > MAXC ? '<a class="offer-more" href="detail.html?id=' + it.id + '">+ 색상 ' + (colors.length - MAXC) + '개 더 보기</a>' : '';
    }
    return '<article class="card result-item">' +
      '<a class="thumb" href="detail.html?id=' + it.id + '" aria-label="' + U.esc(it.name) + ' 상세 보기">' + (it.imageUrl ? '<img src="' + U.esc(it.imageUrl) + '" alt="" loading="lazy" referrerpolicy="no-referrer">' : '[상품 이미지]') + '</a>' +
      '<div class="info">' + (badges ? '<div style="display:flex;gap:6px;flex-wrap:wrap">' + badges + '</div>' : '') +
        '<a class="name" href="detail.html?id=' + it.id + '">' + U.esc(it.name) + '</a>' +
        (it.spec ? '<span class="muted" style="font-size:13px">' + U.esc(it.spec) + '</span>' : '') +
        (meta ? '<span class="small muted">' + U.esc(meta) + '</span>' : '') + '</div>' +
      '<div class="offers">' + offers + more +
        '<div class="bottom"><div><div class="small muted">' + (it.offerCount > 1 ? '최저 ' : '') + '예상 구매비용</div><span class="best-price">' + (it.bestTotal != null ? P.won(it.bestTotal) : '—') + '</span>' + strike + '</div>' +
        '<a class="btn btn-primary btn-sm" href="detail.html?id=' + it.id + '">' + cta + '</a></div>' +
      '</div></article>';
  }

  // 쇼핑몰별 조회 상태 (ok · timeout · error · skipped). 일부만 실패해도 서버는 200 으로 나머지 결과를 줌
  function renderSources(r) {
    var note = U.$('#sourceNote'), src = r.sources || [];
    U.$all('[data-store-note]').forEach(function (el) { el.textContent = ''; });
    var late = [], failed = [];
    src.forEach(function (s) {
      var el = U.$('[data-store-note="' + s.storeId + '"]');
      if (el) el.textContent = s.status === 'ok' ? ' ' + s.count + '건' : s.status === 'skipped' ? ' 준비 중' : s.status === 'timeout' ? ' 응답 지연' : ' 조회 실패';
      if (s.status === 'timeout') late.push(s.mallName); else if (s.status === 'error') failed.push(s.mallName);
    });
    var parts = [];
    if (late.length) parts.push(late.join(' · ') + ' 응답이 늦어 이번 결과에서 뺐어요');
    if (failed.length) parts.push(failed.join(' · ') + ' 가격을 불러오지 못해 뺐어요');
    note.textContent = parts.length ? parts.join(' · ') + '. 잠시 후 다시 검색하면 포함될 수 있어요.' : '';
    note.classList.toggle('hidden', !parts.length);
  }

  function renderPager(r) {
    var nav = U.$('#pager'), total = r.totalPages || 1, cur = r.page || 0;
    nav.classList.toggle('hidden', total <= 1);
    if (total <= 1) { nav.innerHTML = ''; return; }
    var from = Math.max(0, Math.min(cur - 2, total - 5)), to = Math.min(total, from + 5), html = '';
    var btn = function (p, label, opt) {
      opt = opt || {};
      return '<button type="button" data-page="' + p + '"' + (opt.disabled ? ' disabled' : '') + (opt.current ? ' aria-current="page"' : '') + (opt.aria ? ' aria-label="' + opt.aria + '"' : '') + '>' + label + '</button>';
    };
    html += btn(cur - 1, '‹', { disabled: cur === 0, aria: '이전 페이지' });
    for (var i = from; i < to; i++) html += btn(i, String(i + 1), { current: i === cur });
    html += btn(cur + 1, '›', { disabled: cur >= total - 1, aria: '다음 페이지' });
    nav.innerHTML = html;
  }

  // 검색어 없이 들어온 경우 (실제 서버는 q 가 필수라 400) → 호출하지 않고 안내
  function renderPrompt(msg) {
    U.$('#resultTitle').textContent = '상품 검색';
    U.$('#ratesNote').textContent = '';
    U.$('#sourceNote').classList.add('hidden');
    U.$('#pager').classList.add('hidden');
    var list = U.$('#resultList');
    list.innerHTML = '<div class="state" role="status"><b class="state-title">' + U.esc(msg) + '</b><p>상품명 · 브랜드 · 모델명으로 검색하면 쇼핑몰별 예상 구매비용을 비교해 드려요. 한글로 입력해도 돼요.</p><div class="state-actions" id="promptKeywords"></div></div>';
    GP.api.getHome().then(function (d) {
      var box = U.$('#promptKeywords');
      if (box) box.innerHTML = (d.hotKeywords || []).slice(0, 6).map(function (k) { return '<a class="chip" href="search.html?q=' + encodeURIComponent(k) + '">' + U.esc(k) + '</a>'; }).join('');
    }).catch(function () { /* 추천 검색어는 없어도 됨 */ });
  }

  function load() {
    var my = ++reqId;
    var list = U.$('#resultList');
    if (!cfg.USE_MOCK && !state.q) { renderPrompt('검색어를 입력해 주세요'); return; }
    if (state.q.length > 50) { renderPrompt('검색어는 50자 이내로 입력해 주세요'); return; }
    list.innerHTML = U.stateHTML('loading', cfg.USE_MOCK ? '최저가를 찾는 중…' : '해외 쇼핑몰에서 가격을 불러오는 중… (처음 검색은 몇 초 걸려요)');
    U.$('#resultTitle').textContent = state.q ? "'" + state.q + "' 검색 결과" : '전체 상품';
    U.$('#pager').classList.add('hidden');
    GP.api.searchProducts(state).then(function (r) {
      if (my !== reqId) return; // 이전 요청 응답 무시
      var total = r.totalElements != null ? r.totalElements : r.total;
      U.$('#resultTitle').innerHTML = (state.q ? "'" + U.esc(state.q) + "' 검색 결과 " : '전체 상품 ') + '<span style="color:var(--primary)">' + total + '</span>개';
      var notes = [];
      // 검색어에 모델코드가 있으면 서버가 그 코드가 상품명에 있는 리스팅만 보내 줌 (박스 · 다른 모델 제외)
      var codes = (r.modelCodes || []).map(function (c) { return c.toUpperCase(); });
      if (codes.length) notes.push('모델코드 ' + codes.join(', ') + ' 일치 상품만' + (r.modelCodeExcluded ? ' (다른 상품 ' + r.modelCodeExcluded + '건 제외)' : ''));
      // 한글 검색어는 서버가 영어로 바꿔 쇼핑몰에 보냄 (아이폰 17 → iphone 17)
      if (r.sourceQuery && r.sourceQuery.toLowerCase() !== state.q.toLowerCase()) notes.push("'" + r.sourceQuery + "'(으)로 검색");
      if (r.limitedToTopN) notes.push('쇼핑몰별 상위 ' + r.limitedToTopN + '건 기준');
      if (GP.rates && GP.rates.baseDate) notes.push('환율 ' + U.rateBasis(GP.rates));
      else if (r.ratesUpdatedAt) notes.push('환율 기준 ' + U.fmtTime(r.ratesUpdatedAt));
      U.$('#ratesNote').textContent = notes.join(' · ');
      renderSources(r);
      renderFacets(r.facets);
      if (!r.items.length) {
        var sug = (r.suggestions || []).filter(function (s) { return s && s !== state.q; });
        list.innerHTML = '<div class="state" role="status"><b class="state-title">' + (codes.length ? '모델코드 ' + U.esc(codes.join(', ')) + '와 일치하는 상품이 없어요' : '조건에 맞는 상품이 없어요') + '</b><p>' + (codes.length ? '모델코드를 다시 확인하거나 제품 이름으로 검색해 보세요.' : '검색어나 필터를 바꿔 보세요.') + '</p>' +
          (sug.length ? '<div class="state-actions">' + sug.map(function (s) { return '<a class="chip" href="search.html?q=' + encodeURIComponent(s) + '">' + U.esc(s) + '</a>'; }).join('') + '</div>' : '') + '</div>';
        return;
      }
      var html = r.items.map(itemHTML);
      if (html.length > 2) html.splice(2, 0, '<div class="ad-slot" data-ad-slot="search-inline">자체 맞춤형 광고 영역</div>');
      list.innerHTML = html.join('');
      renderPager(r);
    }).catch(function (e) {
      if (my !== reqId) return;
      U.$('#sourceNote').classList.add('hidden');
      list.innerHTML = U.errorHTML(e, 'searchRetry');
      var r = U.$('#searchRetry');
      if (r) r.addEventListener('click', load);
    });
  }

  // 다나와식 체크표: 값은 소문자로 보내고 비교 (서버도 소문자)
  function renderFacets(f) {
    var box = U.$('#facetBox');
    var rows = FACETS.filter(function (d) { return f && f[d[0]] && (f[d[0]].length > 1 || state[d[0]].length); });
    if (!rows.length) { box.classList.add('hidden'); box.innerHTML = ''; return; }
    var any = FACETS.some(function (d) { return state[d[0]].length; });
    box.innerHTML = rows.map(function (d) {
      var key = d[0], picked = state[key];
      return '<div class="facet-row"><b class="facet-name">' + d[1] + '</b><div class="facet-opts">' +
        f[key].map(function (o) {
          var v = String(o.value).toLowerCase();
          return '<label class="facet-opt"><input type="checkbox" data-facet="' + key + '" value="' + U.esc(v) + '"' +
            (picked.indexOf(v) > -1 ? ' checked' : '') + '> ' + U.esc(o.label) + ' <span class="facet-n">' + o.count + '</span></label>';
        }).join('') + '</div></div>';
    }).join('') + (any ? '<div class="facet-foot"><button type="button" class="btn btn-line btn-sm" id="facetClear">선택 초기화</button></div>' : '');
    box.classList.remove('hidden');
  }

  // 좁은 화면 필터 버튼에 "켜진 필터 개수" 표시
  function paintFilterCount() {
    var n = state.stores.length + state.countries.length + state.benefits.length + (state.inStock ? 1 : 0) + (state.minPrice != null || state.maxPrice != null ? 1 : 0);
    U.$('#filterCount').textContent = n ? ' ' + n : '';
  }
  // 필터 · 정렬이 바뀌면 1페이지부터
  function refresh() { state.page = 0; syncControls(); paintFilterCount(); writeURL(); load(); }

  function readFilters() {
    ['stores', 'countries', 'benefits'].forEach(function (k) {
      state[k] = U.$all('#filterForm input[name=' + k + ']:checked').map(function (c) { return c.value; });
    });
    state.inStock = !!U.$('#filterForm input[name=inStock]:checked');
  }
  function applyPrice() {
    var lo = num(U.$('#minPrice').value), hi = num(U.$('#maxPrice').value);
    if (lo != null && hi != null && lo > hi) { var t = lo; lo = hi; hi = t; } // 거꾸로 넣으면 바꿔 줌
    state.minPrice = lo; state.maxPrice = hi;
    refresh();
  }

  document.addEventListener('DOMContentLoaded', function () {
    renderStoreFilter();
    readURL();
    syncControls();
    paintFilterCount();
    load();

    // 좁은 화면(900px 이하): 필터는 접어 두고 버튼으로 열고 닫음. 넓은 화면에선 버튼이 숨겨지고 필터가 항상 보임
    var panel = U.$('#filterPanel'), tog = U.$('#filterToggle');
    tog.addEventListener('click', function () {
      var open = !panel.classList.contains('is-open');
      panel.classList.toggle('is-open', open);
      tog.setAttribute('aria-expanded', String(open));
      if (open) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    U.$('#filterClose').addEventListener('click', function () {
      panel.classList.remove('is-open');
      tog.setAttribute('aria-expanded', 'false');
      U.$('.results').scrollIntoView({ behavior: 'smooth', block: 'start' });
    });

    U.$('#facetBox').addEventListener('change', function (e) {
      var t = e.target, key = t.getAttribute('data-facet');
      if (!key) return;
      var arr = state[key].filter(function (v) { return v !== t.value; });
      if (t.checked) arr.push(t.value);
      state[key] = arr;
      refresh();
    });
    U.$('#facetBox').addEventListener('click', function (e) {
      if (e.target.id !== 'facetClear') return;
      FACETS.forEach(function (d) { state[d[0]] = []; });
      refresh();
    });
    U.$('#filterForm').addEventListener('change', function (e) {
      if (e.target.type !== 'checkbox') return; // 금액은 "금액 적용" · Enter 로만 반영 (입력할 때마다 외부 조회하지 않게)
      readFilters(); refresh();
    });
    U.$('#filterForm').addEventListener('submit', function (e) { e.preventDefault(); applyPrice(); });
    U.$('#priceApply').addEventListener('click', applyPrice);
    U.$('#filterForm').addEventListener('reset', function () {
      setTimeout(function () { state.stores = []; state.countries = []; state.benefits = []; state.inStock = false; state.minPrice = null; state.maxPrice = null; refresh(); }, 0);
    });
    U.$all('.sort-group button').forEach(function (b) {
      b.addEventListener('click', function () { state.sort = b.dataset.sort; refresh(); });
    });
    U.$('#couponToggle').addEventListener('click', function () { state.coupon = !state.coupon; refresh(); });
    U.$('#pager').addEventListener('click', function (e) {
      var b = e.target.closest('button[data-page]');
      if (!b || b.disabled) return;
      state.page = Number(b.dataset.page);
      writeURL(); load();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  });
})();
