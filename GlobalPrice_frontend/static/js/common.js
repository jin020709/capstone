/* =========================================================
   공통: 테마, 헤더 · 푸터, 환율 바, 로그인 상태, 유틸
   각 페이지 <body data-page="home|search|detail|login|signup|mypage">
   ========================================================= */
window.GP = window.GP || {};

GP.ui = (function () {
  var THEMES = [
    { id: 'green', label: 'A안 · 그린' },
    { id: 'blue', label: 'B안 · 블루' },
    { id: 'lavender', label: 'D안 · 라벤더' }
  ];

  var ICON = {
    globe: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9.5"/><path d="M2.5 12h19M12 2.5c2.8 3 2.8 16 0 19M12 2.5c-2.8 3-2.8 16 0 19"/></svg>',
    search: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/></svg>',
    heart: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/></svg>',
    bell: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/></svg>',
    user: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/></svg>'
  };

  /* ---------- 유틸 ---------- */
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function param(name) { return new URLSearchParams(location.search).get(name); }
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $all(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function fmtTime(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d)) return iso || '';
    var p = function (n) { return String(n).padStart(2, '0'); };
    return d.getFullYear() + '.' + p(d.getMonth() + 1) + '.' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }
  function toast(msg) {
    var el = document.createElement('div');
    el.className = 'toast';
    el.setAttribute('role', 'status');
    el.textContent = msg;
    document.body.appendChild(el);
    setTimeout(function () { el.remove(); }, 2200);
  }
  function stateHTML(type, msg) {
    return '<div class="state ' + (type === 'error' ? 'error' : '') + '" role="' + (type === 'error' ? 'alert' : 'status') + '">' + esc(msg) + '</div>';
  }

  // 판매처 옆에 붙는 출발지 표시: "eBay US · 중국" 은 쇼핑몰 국가처럼 읽혀서 "중국 발송" 으로 표시
  //   국내 직구관(원화 결제)은 그대로
  function originLabel(o) {
    var n = o && o.countryName;
    if (!n) return '';
    return n === '원화 결제' ? n : n + ' 발송';
  }
  // 날짜만 "11.15" (서버가 2026-11-15 또는 2026-11-15T10:00:00 으로 줌)
  function fmtDay(s) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s || '');
    return m ? Number(m[2]) + '.' + Number(m[3]) : '';
  }

  // "3시간 전" 형태
  function ago(iso) {
    if (!iso) return '';
    var m = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
    if (isNaN(m)) return '';
    if (m < 1) return '방금';
    if (m < 60) return m + '분 전';
    if (m < 60 * 24) return Math.round(m / 60) + '시간 전';
    return Math.round(m / 1440) + '일 전';
  }

  /* ---------- 오류 코드별 안내 ----------
     백엔드 ErrorCode enum 이름 그대로 사용 (globalprice-backend common/exception/ErrorCode.java).
     예전 이름(UNAUTHORIZED, EXTERNAL_UNAVAILABLE)도 같은 안내로 받아 둠 */
  var ERRORS = {
    INVALID_INPUT: { title: '입력값을 확인해 주세요', retry: false },
    LOGIN_REQUIRED: { title: '로그인이 필요해요', retry: false, login: true, msg: '로그인하면 이어서 볼 수 있어요.' },
    LOGIN_FAILED: { title: '로그인에 실패했어요', retry: false, msg: '이메일 또는 비밀번호를 확인해 주세요.' },
    FORBIDDEN: { title: '접근 권한이 없어요', retry: false, home: true },
    NOT_FOUND: { title: '페이지를 찾을 수 없어요', retry: false, home: true, msg: '주소가 잘못됐거나 없어진 페이지예요.' },
    PRODUCT_NOT_FOUND: { title: '상품을 찾을 수 없어요', retry: false, home: true, msg: '삭제됐거나 주소가 잘못된 상품이에요.' },
    METHOD_NOT_ALLOWED: { title: '지원하지 않는 요청이에요', retry: false },
    DUPLICATE_EMAIL: { title: '이미 가입된 이메일이에요', retry: false },
    EXTERNAL_INVALID_RESPONSE: { title: '판매처 정보가 올바르지 않아요', retry: true, msg: '판매처 응답을 확인하는 중이에요. 잠시 후 다시 시도해 주세요.' },
    EXTERNAL_API_UNAVAILABLE: { title: '판매처 정보를 불러올 수 없어요', retry: true, msg: '외부 쇼핑몰 연결이 잠시 원활하지 않아요. 저장된 가격은 곧 다시 보여드릴게요.' },
    INTERNAL_ERROR: { title: '일시적인 오류가 생겼어요', retry: true, msg: '잠시 후 다시 시도해 주세요.' },
    TIMEOUT: { title: '응답이 늦어요', retry: true },
    NETWORK: { title: '서버에 연결하지 못했어요', retry: true }
  };
  ERRORS.UNAUTHORIZED = ERRORS.LOGIN_REQUIRED;
  ERRORS.EXTERNAL_UNAVAILABLE = ERRORS.EXTERNAL_API_UNAVAILABLE;
  var BY_STATUS = { 400: 'INVALID_INPUT', 401: 'LOGIN_REQUIRED', 403: 'FORBIDDEN', 404: 'NOT_FOUND', 405: 'METHOD_NOT_ALLOWED', 409: 'DUPLICATE_EMAIL', 502: 'EXTERNAL_INVALID_RESPONSE', 503: 'EXTERNAL_API_UNAVAILABLE' };
  function errorInfo(err) {
    var code = err && err.code;
    if (!code && err && err.status) code = BY_STATUS[err.status] || (err.status >= 500 ? 'INTERNAL_ERROR' : null);
    var e = ERRORS[code] || { title: '문제가 생겼어요', retry: true };
    return { code: code, title: e.title, msg: e.msg || (err && err.message) || '', retry: e.retry, login: e.login, home: e.home };
  }
  // 오류 화면: 제목 + 설명 + (다시 시도 / 검색 / 로그인) 버튼. retryId 버튼은 화면 코드에서 연결
  function errorHTML(err, retryId) {
    var i = errorInfo(err);
    var next = location.pathname.split('/').pop() + location.search;
    var act = i.retry && retryId ? '<button type="button" class="btn btn-primary btn-sm" id="' + retryId + '">다시 시도</button>' : '';
    if (i.home) act += '<a class="btn btn-line btn-sm" href="search.html">상품 검색하기</a>';
    if (i.login) act += '<a class="btn btn-primary btn-sm" href="login.html?next=' + encodeURIComponent(next) + '">로그인</a>';
    return '<div class="state error" role="alert"><b class="state-title">' + esc(i.title) + '</b>' +
      (i.msg ? '<p>' + esc(i.msg) + '</p>' : '') + (act ? '<div class="state-actions">' + act + '</div>' : '') +
      (i.code ? '<span class="state-code">' + esc(i.code) + '</span>' : '') + '</div>';
  }

  // 예시 데이터 안내 문구(.mock-only)는 실제 서버 모드에서 숨기고, .live-only 는 목업에서 숨김
  //   <template> 으로 나중에 붙이는 화면(상세)은 붙인 뒤에 한 번 더 호출
  function applyMode(root) {
    $all(GP.config.USE_MOCK ? '.live-only' : '.mock-only', root || document).forEach(function (el) { el.classList.add('hidden'); });
  }

  /* ---------- 행사 알림 버튼 (홈 행사 카드 · 상세 "적용 가능한 행사") ----------
     - 시작 알림: 아직 시작 전인 행사만 (서버가 시작일 아침에 알림)
     - 마감 알림: 쿠폰(COUPON) 행사이고 마감이 내일 이후일 때만 (서버가 마감 하루 전에 알림)
     - 이미 건 알림은 "✓ … 알림 받는 중" 으로 표시하고 다시 누르면 끔 */
  var eventAlerts = {
    map: {},   // "EVENT_START|12" → alertId
    key: function (basis, id) { return basis + '|' + id; },
    today: function () { var d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); },
    options: function (e) {
      if (!GP.config.FEATURES.alerts || !e) return [];
      var t = eventAlerts.today(), out = [], s = (e.start || '').slice(0, 10), en = (e.end || '').slice(0, 10);
      if (s && s > t) out.push({ basis: 'EVENT_START', on: '시작 알림 받는 중', off: '시작하면 알림' });
      if (e.eventType === 'COUPON' && en && en > t) out.push({ basis: 'COUPON_EXPIRY', on: '마감 알림 받는 중', off: '마감 하루 전 알림' });
      return out;
    },
    buttonsHTML: function (e) {
      var id = e.storeEventId != null ? e.storeEventId : e.id;
      var opts = eventAlerts.options(e);
      if (!opts.length) return '';
      return '<div class="ev-alerts">' + opts.map(function (o) {
        var on = !!eventAlerts.map[eventAlerts.key(o.basis, id)];
        return '<button type="button" class="ev-alert-btn" data-ev-basis="' + o.basis + '" data-ev-id="' + esc(String(id)) + '" aria-pressed="' + on + '"' +
          ' data-on="' + esc(o.on) + '" data-off="' + esc(o.off) + '">' + ICON.bell + '<span>' + esc(on ? o.on : o.off) + '</span></button>';
      }).join('') + '</div>';
    },
    // 로그인 상태면 이미 건 행사 알림을 불러와 버튼 상태에 반영
    load: function () {
      eventAlerts.map = {};
      if (!GP.config.FEATURES.alerts || !GP.api.me()) return Promise.resolve();
      return GP.api.getAlerts().then(function (list) {
        (list || []).forEach(function (a) { if (a.storeEventId != null && a.alertBasis !== 'TARGET_PRICE' && a.alertBasis !== 'PERIOD_LOWEST') eventAlerts.map[eventAlerts.key(a.alertBasis, a.storeEventId)] = a.id; });
      }).catch(function () { /* 알림 목록을 못 받아도 버튼은 "끔" 상태로 표시 */ });
    },
    paint: function (btn) {
      var on = !!eventAlerts.map[eventAlerts.key(btn.dataset.evBasis, btn.dataset.evId)];
      btn.setAttribute('aria-pressed', String(on));
      $('span', btn).textContent = on ? btn.dataset.on : btn.dataset.off;
    },
    // 한 번만 걸어 두면 페이지 어디서 그린 버튼이든 동작 (이벤트 위임)
    bind: function () {
      if (eventAlerts.bound) return;
      eventAlerts.bound = true;
      document.addEventListener('click', function (ev) {
        var btn = ev.target.closest('.ev-alert-btn');
        if (!btn || btn.disabled) return;
        ev.preventDefault();
        if (!GP.api.me()) { toast('로그인 후 행사 알림을 받을 수 있어요.'); return; }
        var basis = btn.dataset.evBasis, id = btn.dataset.evId, k = eventAlerts.key(basis, id), cur = eventAlerts.map[k];
        btn.disabled = true;
        var job = cur ? GP.api.deleteAlert(cur).then(function () { delete eventAlerts.map[k]; toast('행사 알림을 껐어요.'); })
          : GP.api.createEventAlert(id, basis).then(function () { return eventAlerts.load(); }).then(function () {
              if (!eventAlerts.map[k]) eventAlerts.map[k] = -1; // 목록 응답이 늦어도 켜진 걸로 표시
              toast(basis === 'EVENT_START' ? '행사가 시작하는 날 알려드릴게요.' : '쿠폰 마감 하루 전에 알려드릴게요.');
            });
        job.catch(function (e) { toast(e && e.status === 401 ? '로그인 후 행사 알림을 받을 수 있어요.' : errorInfo(e).title); })
          .then(function () {
            btn.disabled = false;
            $all('.ev-alert-btn[data-ev-basis="' + basis + '"][data-ev-id="' + id + '"]').forEach(eventAlerts.paint);
          });
      });
    }
  };

  /* ---------- 테마 ---------- */
  function getTheme() { return GP.api.LS.get('gp_theme', 'green'); }
  function setTheme(id) {
    document.documentElement.setAttribute('data-theme', id);
    GP.api.LS.set('gp_theme', id);
  }
  // 색 테마 선택칸은 없애고 기본(그린)으로 고정. 예전에 블루 · 라벤더를 골랐던 사람도 그린으로 맞춤
  setTheme('green');

  /* ---------- 기본 ↔ 다크 (헤더의 해 · 달 아이콘) ----------
     처음엔 기기 설정(다크/라이트)을 따르고, 아이콘을 누르면 그 선택을 기억한다 */
  function getColorMode() {
    var saved = GP.api.LS.get('gp_mode', null);
    if (saved === 'dark' || saved === 'light') return saved;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  function applyColorMode(m) { document.documentElement.setAttribute('data-mode', m); }
  function setColorMode(m) {
    GP.api.LS.set('gp_mode', m);
    applyColorMode(m);
    var b = document.getElementById('modeToggle');
    if (b) paintColorModeBtn(b, m);
  }
  var ICON_MOON = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>';
  var ICON_SUN = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4.5" fill="currentColor" stroke="none"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"/></svg>';
  function paintColorModeBtn(b, m) {
    var dark = m === 'dark';
    b.setAttribute('aria-pressed', String(dark));
    // 지금이 기본이면 달(누르면 다크), 다크면 해(누르면 기본)
    b.innerHTML = dark ? ICON_SUN : ICON_MOON;
    b.title = dark ? '기본 모드로 바꾸기' : '다크 모드로 바꾸기';
    b.setAttribute('aria-label', b.title);
  }
  applyColorMode(getColorMode());
  // 저장한 선택이 없으면 기기 설정이 바뀔 때 따라감
  if (window.matchMedia) {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    var follow = function () {
      if (GP.api.LS.get('gp_mode', null)) return;
      applyColorMode(mq.matches ? 'dark' : 'light');
      var b = document.getElementById('modeToggle');
      if (b) paintColorModeBtn(b, getColorMode());
    };
    if (mq.addEventListener) mq.addEventListener('change', follow); else if (mq.addListener) mq.addListener(follow);
  }

  /* ---------- 헤더 · 푸터 ---------- */
  function renderHeader(active) {
    var user = GP.api.me();
    var q = param('q') || '';
    var authLinks = user
      ? '<span>' + esc(user.nickname) + '님</span><a href="mypage.html">마이페이지</a><button type="button" id="logoutBtn">로그아웃</button>'
      : '<a href="login.html">로그인</a><a href="signup.html">회원가입</a>';
    var nav = [
      ['home', 'index.html', '홈'], ['events', 'index.html#events', '이번 달 직구 행사'],
      ['search', 'search.html', GP.config.USE_MOCK ? '쿠폰 적용 최저가' : '상품 검색'], ['calc', 'index.html#calc', '예상 구매비용 계산기'],
      ['mypage', 'mypage.html', GP.config.FEATURES.alerts ? '관심 상품 · 알림' : '관심 상품']
    ].map(function (n) {
      return '<a href="' + n[1] + '"' + (n[0] === active ? ' aria-current="page"' : '') + '>' + n[2] + '</a>';
    }).join('');

    var html =
      '<div class="topbar"><div class="container">' +
        '<div class="rates" id="ratesBar"><b style="color:var(--primary)">오늘의 환율</b><span class="muted">불러오는 중…</span></div>' +
        '<div class="links">' +
          '<button type="button" class="mode-toggle" id="modeToggle" aria-pressed="false"></button>' +
          authLinks +
        '</div>' +
      '</div></div>' +
      '<header class="site-header"><div class="container">' +
        '<div class="bar">' +
          '<a class="logo" href="index.html">' + ICON.globe + 'Global<span>Price</span></a>' +
          '<form class="search-form" role="search" action="search.html" method="get">' +
            '<label for="headerQ" class="sr-only">해외 쇼핑몰 통합 검색</label>' +
            '<input id="headerQ" name="q" type="search" value="' + esc(q) + '" placeholder="상품명 · 브랜드 · 모델명으로 해외직구 최저가 검색" autocomplete="off">' +
            '<button type="submit">' + ICON.search + '검색</button>' +
          '</form>' +
          '<nav class="header-menu" aria-label="내 메뉴">' +
            '<a href="mypage.html#wish">' + ICON.heart + '관심상품</a>' +
            (GP.config.FEATURES.alerts ? '<a href="mypage.html#notis" id="notiLink">' + ICON.bell + '가격알림</a>' : '') +
            '<a href="' + (user ? 'mypage.html' : 'login.html') + '">' + ICON.user + 'MY</a>' +
          '</nav>' +
        '</div>' +
        '<nav class="main-nav" aria-label="주 메뉴">' + nav + '</nav>' +
      '</div></header>';

    var mount = document.getElementById('site-header');
    if (!mount) return;
    mount.innerHTML = html;

    var mt = $('#modeToggle');
    paintColorModeBtn(mt, getColorMode());
    mt.addEventListener('click', function () { setColorMode(getColorMode() === 'dark' ? 'light' : 'dark'); });
    var lo = $('#logoutBtn');
    if (lo) lo.addEventListener('click', function () { GP.api.logout().then(function () { location.href = 'index.html'; }); });
    $('#headerQ').setAttribute('maxlength', '50'); // 백엔드: 검색어 1~50자
    $('.search-form', mount).addEventListener('submit', function (e) {
      var v = $('#headerQ').value.trim();
      if (!v) { e.preventDefault(); $('#headerQ').focus(); toast('검색어를 입력해 주세요.'); }
      else if (v.length > 50) { e.preventDefault(); toast('검색어는 50자 이내로 입력해 주세요.'); }
    });
    loadRates();
    if (user) loadUnread();
  }

  // 안 읽은 받은 알림 수 → 헤더 종 아이콘에 표시 (실패해도 화면에는 영향 없음)
  function loadUnread() {
    if (!GP.config.FEATURES.alerts || !$('#notiLink')) return;
    GP.api.getNotifications({ unread: true, size: 1 }).then(function (r) {
      var link = $('#notiLink'), n = r && r.unreadCount;
      if (!link || !n) return;
      var old = $('.noti-count', link); if (old) old.remove();
      var b = document.createElement('span');
      b.className = 'noti-count';
      b.textContent = n > 99 ? '99+' : String(n);
      b.setAttribute('aria-label', '안 읽은 알림 ' + n + '개');
      link.appendChild(b);
    }).catch(function () { /* 로그인 만료 등 → 표시 안 함 */ });
  }

  // 환율 "기준" 표시: 서버 updatedAt 은 우리 서버가 받아 온 시각이라, 수출입은행 고시일(baseDate)을 우선 보여줌
  //   (주말에 서버를 켜면 "11.15 17:15 기준"이 아니라 "11.13 고시"가 맞음)
  function rateBasis(r) {
    if (r && r.baseDate) return fmtDay(r.baseDate) + ' 고시';
    return r && r.updatedAt ? fmtTime(r.updatedAt) + ' 기준' : '';
  }

  function loadRates() {
    GP.api.getExchangeRates().then(function (r) {
      // 수출입은행은 위안화를 CNH(역외 위안)로만 줌 → 계산기 · 표시에서는 CNY 로도 쓸 수 있게
      if (r.rates && r.rates.CNY == null && r.rates.CNH != null) r.rates.CNY = r.rates.CNH;
      GP.rates = r;
      var bar = $('#ratesBar');
      if (!bar) return;
      // 서버에 있는 통화만 보여줌 (수출입은행 키가 없으면 USD · JPY 만 있음 → EUR 을 고정으로 읽으면 오류)
      var map = r.rates || {}, fmt = function (v) { return Number(v).toLocaleString('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); };
      var order = ['USD', 'JPY', 'EUR', 'CNY', 'GBP'].filter(function (c) { return map[c] != null; });
      var cells = order.map(function (c) {
        return c === 'JPY' ? '<span>JPY(100) <b>' + fmt(map.JPY * 100) + '</b></span>' : '<span>' + c + ' <b>' + fmt(map[c]) + '</b></span>';
      }).join('');
      var basis = rateBasis(r);
      bar.innerHTML = '<b style="color:var(--primary)">오늘의 환율</b>' + cells +
        '<span>' + (basis ? esc(basis) + ' · ' : '') + esc(r.source || '') + '</span>';
      document.dispatchEvent(new CustomEvent('gp:rates', { detail: r })); // 홈 계산기가 실제 환율로 다시 계산
    }).catch(function () {
      var bar = $('#ratesBar');
      if (bar) bar.innerHTML = '<span class="muted">환율 정보를 불러오지 못했어요. 마지막 저장값으로 계산돼요.</span>';
    });
  }

  function renderFooter() {
    var mount = document.getElementById('site-footer');
    if (!mount) return;
    mount.innerHTML =
      '<footer class="site-footer"><div class="container">' +
        '<div><strong>GlobalPrice</strong><div>해외직구 가격비교 웹서비스 · TEAM SODA · 대진대학교 캡스톤디자인</div></div>' +
        '<div style="max-width:560px">GlobalPrice는 가격 정보를 비교해 보여주며 상품 판매 · 결제에 관여하지 않아요. ' +
        '쿠폰 · 행사가는 쇼핑몰 조건에 따라 달라질 수 있고, 관부가세는 추정치로 실제 통관 세액과 다를 수 있어요.</div>' +
      '</div></footer>';
  }

  document.addEventListener('DOMContentLoaded', function () {
    var page = document.body.getAttribute('data-page');
    var before = JSON.stringify(GP.api.me());
    applyMode(document);
    renderHeader(page); // 캐시로 먼저 그리고
    renderFooter();
    GP.api.ready.then(function (u) { // 서버 세션 확인 결과가 다르면 다시 그림 (세션 만료 등)
      if (JSON.stringify(u) !== before) renderHeader(page);
    });
  });

  return { esc: esc, param: param, $: $, $all: $all, toast: toast, stateHTML: stateHTML, errorHTML: errorHTML, errorInfo: errorInfo, ago: ago, fmtTime: fmtTime, fmtDay: fmtDay, originLabel: originLabel, applyMode: applyMode, eventAlerts: eventAlerts, rateBasis: rateBasis, ICON: ICON, setTheme: setTheme };
})();
