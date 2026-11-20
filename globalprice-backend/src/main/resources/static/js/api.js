/* =========================================================
   API 계층 — 화면 코드는 GP.api.* 만 호출합니다.
   USE_MOCK = true  → mock-data.js 로 응답을 흉내냄
   USE_MOCK = false → Spring Boot REST API 호출 (API_SPEC.md)
   ========================================================= */
window.GP = window.GP || {};

GP.api = (function () {
  var cfg = GP.config;
  var LS = {
    get: function (k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* 저장 불가 환경 */ } }
  };

  /* ---------- 공통 요청 (타임아웃 · 오류 처리) ---------- */
  function request(path, opts) {
    opts = opts || {};
    var ctrl = new AbortController();
    var timer = setTimeout(function () { ctrl.abort(); }, cfg.TIMEOUT_MS);
    var headers = { 'Content-Type': 'application/json' };
    // 로그인은 세션 방식: 서버가 준 JSESSIONID 쿠키가 같은 주소(origin)라 자동으로 같이 감 → 토큰 헤더 없음
    return fetch(cfg.API_BASE + path, {
      method: opts.method || 'GET',
      headers: headers,
      credentials: 'same-origin',
      body: opts.body ? JSON.stringify(opts.body) : undefined,
      signal: ctrl.signal
    }).then(function (res) {
      clearTimeout(timer);
      if (!res.ok) {
        return res.json().catch(function () { return {}; }).then(function (b) {
          var err = new Error(b.message || ('요청 실패 (' + res.status + ')'));
          err.status = res.status;
          err.code = b.code || null; // 백엔드 ErrorCode (PRODUCT_NOT_FOUND 등)
          // 세션이 끝났으면 화면에 남은 로그인 표시도 지움
          if (res.status === 401 && err.code !== 'LOGIN_FAILED') setUser(null);
          throw err;
        });
      }
      return res.status === 204 ? null : res.json();
    }).catch(function (e) {
      clearTimeout(timer);
      if (e.name === 'AbortError') { var t = new Error('응답이 늦어요. 잠시 후 다시 시도해 주세요.'); t.code = 'TIMEOUT'; throw t; }
      if (e instanceof TypeError) { var n = new Error('서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.'); n.code = 'NETWORK'; throw n; }
      throw e;
    });
  }

  function delay(data, ms) {
    return new Promise(function (resolve) { setTimeout(function () { resolve(JSON.parse(JSON.stringify(data))); }, ms || 250); });
  }
  function fail(msg, status, code) {
    return new Promise(function (_, reject) { var e = new Error(msg); e.status = status; e.code = code || null; setTimeout(function () { reject(e); }, 200); });
  }
  function qs(params) {
    var parts = [];
    Object.keys(params).forEach(function (k) {
      var v = params[k];
      if (v == null || v === '' || (Array.isArray(v) && !v.length)) return;
      parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(Array.isArray(v) ? v.join(',') : v));
    });
    return parts.length ? '?' + parts.join('&') : '';
  }

  /* ---------- 목업: 계산된 응답 만들기 ---------- */
  var M = GP.mock;
  function eventOf(id) { return M.events.filter(function (e) { return e.id === id; })[0] || null; }

  // 판매처 가격 상태 (백엔드 대체값 3단계와 같음)
  //  ok: 이번 갱신 성공 · stale: 갱신 실패 → 저장된 값(updatedAt 기준) · unavailable: 저장값도 없음
  function hoursAgo(h) { return new Date(Date.now() - h * 3600 * 1000).toISOString(); }

  // 목업 offer → API 응답 Offer 형식 (API_SPEC.md · DB 컬럼과 같은 이름)
  function buildOffers(p, useCoupon) {
    var rates = M.exchangeRates.rates;
    var list = p.offers.map(function (o, idx) {
      var s = M.stores[o.storeId];
      var status = o.status || 'ok';
      var ev = o.eventId ? eventOf(o.eventId) : (o.coupon ? eventOf(o.coupon.eventId) : null);
      // 할인 규칙: 행사(store_events) 기준. 판매처별 정액 쿠폰 예시는 AMOUNT 로 취급
      var discount = o.eventId && ev ? { discountType: ev.discountType, discountValue: ev.discountValue, minPurchaseAmount: ev.minPurchaseAmount, maxDiscountAmount: ev.maxDiscountAmount }
        : (o.coupon ? { discountType: 'AMOUNT', discountValue: o.coupon.amount } : null);
      var base = {
        mallProductId: p.id * 100 + idx, mallVariantId: p.id * 100 + idx,
        storeId: o.storeId, storeName: s.name, countryName: s.countryName, countryCode: s.country,
        variantName: o.variantName || null, stockStatus: o.stockStatus || 'IN_STOCK',
        status: status, calculatedAt: hoursAgo(status === 'stale' ? (o.staleHours || 3) : 0.5),
        currency: o.currency, url: o.url
      };
      base.updatedAt = base.calculatedAt;
      if (status === 'unavailable') return Object.assign(base, { price: null, calc: null, total: null, totalNormal: null, appliedEvent: null });
      var oc = Object.assign({}, o, { discount: discount });
      var c = GP.price.calcOffer(oc, p.category, rates, useCoupon);
      var n = GP.price.calcOffer(oc, p.category, rates, false);
      return Object.assign(base, {
        price: o.price, shipping: o.shipping, shippingNote: o.shippingNote || null,
        appliedEvent: c.discountApplied && ev ? {
          storeEventId: ev.id, eventName: o.coupon ? o.coupon.label : ev.name, eventType: ev.eventType || 'COUPON',
          discountType: discount.discountType, discountValue: discount.discountValue, maxDiscountAmount: discount.maxDiscountAmount != null ? discount.maxDiscountAmount : null,
          discountAmount: c.discountAmount
        } : null,
        taxIncluded: !!o.taxIncluded, deliveryDays: o.deliveryDays || null,
        calc: c, totalNormal: n.totalAmountKrw, total: c.totalAmountKrw
      });
    });
    return GP.price.sortOffers(list);
  }

  function summary(p, useCoupon) {
    var offers = buildOffers(p, useCoupon).filter(GP.price.comparable);
    var normal = buildOffers(p, false).filter(GP.price.comparable);
    var badges = [];
    if (offers.some(function (o) { return useCoupon && o.appliedEvent; })) badges.push('행사 진행 중');
    if (p.lowIn30d) badges.push('최근 30일 최저');
    return {
      id: p.id, name: p.name, spec: p.spec, imageUrl: p.imageUrl || null, categoryName: p.categoryName, rating: p.rating, reviewCount: p.reviewCount,
      popularity: p.popularity, badges: badges, offerCount: p.offers.length,
      bestTotal: offers[0].total, bestNormalTotal: normal[0].total, bestStore: offers[0].storeName,
      offers: offers.slice(0, 3)
    };
  }

  // 목업 가격 이력: 실제 쇼핑몰처럼 "한동안 그대로 → 행사 때 뚝 → 다시 조금 오름" 형태 (날짜별 1건)
  function historyFor(p, period) {
    var cur = summary(p, true), now = new Date();
    var days = { '1m': 30, '3m': 91, '6m': 182, '1y': 365 }[period] || 182;
    var seed = p.id * 9301 + 49297, rnd = function () { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; };
    // 과거로 갈수록 조금 비쌌던 흐름 + 가끔 가격 변경 (평균 12일에 한 번)
    var level = 1 + (period === '1m' ? 0.04 : 0.12), pts = [];
    for (var back = days - 1; back >= 0; back--) {
      var d = new Date(now); d.setDate(d.getDate() - back);
      if (back > 0 && rnd() < 1 / 12) level = Math.max(1, level + (rnd() < 0.65 ? -1 : 1) * (0.01 + rnd() * 0.03));
      var k = back === 0 ? 1 : level;
      pts.push({
        date: d.toISOString().slice(0, 10),
        lowestWithCoupon: Math.round(cur.bestTotal * k / 100) * 100,
        lowest: Math.round(cur.bestNormalTotal * k / 100) * 100
      });
    }
    return { productId: p.id, period: period, points: pts };
  }

  // 실제 서버 응답 보정: status 가 없으면 calculatedAt(= price_calculations.calculated_at)으로 판단
  function normalizeProduct(p) {
    (p.offers || []).forEach(function (o) {
      if (!o.updatedAt) o.updatedAt = o.fetchedAt || o.calculatedAt || null; // "N시간 전 가격" 표시 기준
      if (!o.status) {
        if (o.total == null) o.status = 'unavailable';
        else o.status = (Date.now() - new Date(o.calculatedAt).getTime()) / 3600000 > cfg.STALE_HOURS ? 'stale' : 'ok';
      }
    });
    if (p.offers) p.offers = GP.price.sortOffers(p.offers);
    if (!p.badges) p.badges = [];
    return p;
  }

  function findProduct(id) { return M.products.filter(function (p) { return String(p.id) === String(id); })[0]; }

  /* ---------- 로그인 상태 ----------
     gp_user 는 화면 표시용 캐시일 뿐 인증 정보가 아님 (인증은 서버 세션 쿠키).
     페이지가 열릴 때 GP.api.ready 가 서버(/api/auth/me)에 한 번 물어보고 캐시를 맞춤. */
  function currentUser() { return LS.get('gp_user', null); }
  function setUser(u) { LS.set('gp_user', u || null); if (!u) LS.set('gp_wish', []); }
  function requireLogin() { return currentUser() ? null : fail('로그인이 필요해요.', 401, 'LOGIN_REQUIRED'); }
  function pickUser(r) { return r && r.user ? r.user : r; } // { email, nickname } 또는 { user: {...} } 둘 다 받음
  function refreshMe() {
    if (cfg.USE_MOCK) return Promise.resolve(currentUser());
    return request('/api/auth/me')
      .then(function (r) { var u = pickUser(r); setUser(u); return u; })
      .catch(function (e) { if (e.status === 401) { setUser(null); return null; } return currentUser(); }); // 서버 장애면 캐시 유지
  }
  LS.set('gp_token', null); // 예전 JWT 방식에서 남은 값 정리

  /* ---------- 공개 함수 ---------- */
  var api = {
    LS: LS,

    getExchangeRates: function () {
      return cfg.USE_MOCK ? delay(M.exchangeRates, 80) : request('/api/exchange-rates');
    },

    getHome: function () {
      if (!cfg.USE_MOCK) return request('/api/home');
      var popular = M.products.slice().sort(function (a, b) { return b.popularity - a.popularity; }).slice(0, 4)
        .map(function (p) { return summary(p, true); });
      return delay({ categories: M.categories, hotKeywords: M.hotKeywords, events: M.events, popular: popular });
    },

    /**
     * params: { q, sort: total|popular|rating|discount, coupon: true|false, stores:[], countries:[], benefits:[] }
     */
    searchProducts: function (params) {
      var size = params.size || cfg.PAGE_SIZE || 20, page = params.page || 0;
      if (!cfg.USE_MOCK) {
        // 백엔드 명세 v1.1 6-2: 화면에서 쓰는 값만 보냄 (빈 값 · false 는 qs 가 빼거나 아래에서 뺌)
        var query = {
          q: (params.q || '').trim(), sort: params.sort, coupon: params.coupon !== false,
          stores: params.stores, countries: params.countries, benefits: params.benefits,
          minPrice: params.minPrice, maxPrice: params.maxPrice, inStock: params.inStock ? true : null,
          brands: params.brands, models: params.models, storages: params.storages, colors: params.colors,
          page: page, size: size
        };
        return request('/api/products' + qs(query)).then(function (r) {
          r.items = r.items || [];
          r.items.forEach(normalizeProduct);
          r.sources = r.sources || [];
          r.suggestions = r.suggestions || [];
          if (r.page == null) r.page = 0;
          if (r.totalPages == null) r.totalPages = 1;
          return r;
        });
      }
      var q = (params.q || '').trim().toLowerCase();
      if (q.length > 50) return fail('검색어는 50자 이내로 입력해 주세요.', 400, 'INVALID_INPUT');
      var useCoupon = params.coupon !== false;
      var list = M.products.filter(function (p) {
        if (!q) return true;
        return (p.name + ' ' + p.spec + ' ' + p.categoryName + ' ' + p.brand).toLowerCase().indexOf(q) > -1 ||
          (q.indexOf('러닝') > -1 && p.category !== 'electronics');
      }).map(function (p) { return summary(p, useCoupon); });

      list = list.filter(function (s) {
        var src = findProduct(s.id).offers;
        if (params.stores && params.stores.length && !src.some(function (o) { return params.stores.indexOf(o.storeId) > -1; })) return false;
        if (params.countries && params.countries.length && !src.some(function (o) { return params.countries.indexOf(o.currency === 'KRW' ? 'KRW' : o.originCountry) > -1; })) return false;
        var b = params.benefits || [];
        if (b.indexOf('event') > -1 && !src.some(function (o) { return o.coupon || o.eventId; })) return false;
        if (b.indexOf('freeShip') > -1 && !src.some(function (o) { return !o.shipping; })) return false;
        if (b.indexOf('taxIncluded') > -1 && !src.some(function (o) { return o.taxIncluded; })) return false;
        if (params.inStock && !src.some(function (o) { return (o.stockStatus || 'IN_STOCK') === 'IN_STOCK'; })) return false;
        if (params.minPrice != null && s.bestTotal < params.minPrice) return false;
        if (params.maxPrice != null && s.bestTotal > params.maxPrice) return false;
        return true;
      });

      var sorters = {
        total: function (a, b) { return a.bestTotal - b.bestTotal; },
        popular: function (a, b) { return b.popularity - a.popularity; },
        rating: function (a, b) { return b.rating - a.rating; },
        discount: function (a, b) { return (b.bestNormalTotal - b.bestTotal) / b.bestNormalTotal - (a.bestNormalTotal - a.bestTotal) / a.bestNormalTotal; }
      };
      list.sort(sorters[params.sort] || sorters.total);
      var total = list.length;
      return delay({
        query: params.q || '', sourceQuery: params.q || '', total: total, items: list.slice(page * size, page * size + size),
        ratesUpdatedAt: M.exchangeRates.updatedAt, sources: [], suggestions: [],
        page: page, size: size, totalElements: total, totalPages: Math.max(1, Math.ceil(total / size))
      });
    },

    getProduct: function (id, useCoupon) {
      if (!cfg.USE_MOCK) {
        return request('/api/products/' + encodeURIComponent(id) + qs({ coupon: useCoupon })).then(function (p) {
          // 로그인 상태면 서버가 wished(찜 여부)를 같이 주기로 함 → 하트 표시 캐시 갱신
          if (typeof p.wished === 'boolean') {
            var ids = LS.get('gp_wish', []).filter(function (x) { return x !== Number(p.id); });
            if (p.wished) ids.push(Number(p.id));
            LS.set('gp_wish', ids);
          }
          return normalizeProduct(p);
        });
      }
      var p = findProduct(id);
      if (!p) return fail('상품을 찾을 수 없어요.', 404, 'PRODUCT_NOT_FOUND');
      var s = summary(p, useCoupon);
      var offers = buildOffers(p, useCoupon);
      var eventIds = {};
      p.offers.forEach(function (o) { if (o.coupon) eventIds[o.coupon.eventId] = true; if (o.eventId) eventIds[o.eventId] = true; });
      return delay({
        id: p.id, name: p.name, brand: p.brand, model: p.model, spec: p.spec, description: p.description, imageUrl: p.imageUrl || null, categoryName: p.categoryName,
        rating: p.rating, reviewCount: p.reviewCount, badges: s.badges,
        bestTotal: s.bestTotal, bestNormalTotal: s.bestNormalTotal, bestStore: s.bestStore,
        offers: offers,
        events: M.events.filter(function (e) { return eventIds[e.id]; }),
        rates: M.exchangeRates
      });
    },

    getPriceHistory: function (id, period) {
      if (!cfg.USE_MOCK) return request('/api/products/' + encodeURIComponent(id) + '/price-history' + qs({ period: period }));
      var p = findProduct(id);
      return p ? delay(historyFor(p, period), 150) : fail('상품을 찾을 수 없어요.', 404, 'PRODUCT_NOT_FOUND');
    },

    /* ---------- 회원 ---------- */
    signup: function (body) {
      if (!cfg.USE_MOCK) return request('/api/auth/signup', { method: 'POST', body: body });
      var users = LS.get('gp_users', []);
      if (users.some(function (u) { return u.email === body.email; })) return fail('이미 가입된 이메일이에요.', 409, 'DUPLICATE_EMAIL');
      users.push({ email: body.email, nickname: body.nickname }); // 목업: 비밀번호는 저장하지 않음
      LS.set('gp_users', users);
      return delay({ email: body.email, nickname: body.nickname });
    },
    login: function (body) {
      if (!cfg.USE_MOCK) {
        // 성공하면 서버가 세션 쿠키(Set-Cookie)를 내려줌. 응답 본문에는 사용자 정보만 있음
        return request('/api/auth/login', { method: 'POST', body: body }).then(function (r) { var u = pickUser(r); setUser(u); return u; });
      }
      var u = LS.get('gp_users', []).filter(function (x) { return x.email === body.email; })[0];
      if (!u) return fail('이메일 또는 비밀번호를 확인해 주세요.', 401, 'LOGIN_FAILED');
      setUser(u);
      return delay(u);
    },
    logout: function () {
      if (cfg.USE_MOCK) { setUser(null); return Promise.resolve(); }
      // 서버 세션을 끊고, 실패해도 화면은 로그아웃 상태로
      return request('/api/auth/logout', { method: 'POST' }).catch(function () { return null; }).then(function () { setUser(null); });
    },
    me: function () { return currentUser(); }, // 바로 쓰는 캐시 값 (헤더 첫 표시용)
    refreshMe: refreshMe,

    /* ---------- 관심 상품 (찜) ---------- */
    getWishlist: function () {
      if (!cfg.USE_MOCK) {
        return request('/api/wishlist').then(function (list) {
          LS.set('gp_wish', (list || []).map(function (p) { return Number(p.id); })); // 상세 화면 하트 표시용
          return list;
        });
      }
      var need = requireLogin(); if (need) return need;
      var ids = LS.get('gp_wish', []);
      return delay(ids.map(function (id) { var p = findProduct(id); return p ? summary(p, true) : null; }).filter(Boolean));
    },
    isWished: function (id) { return LS.get('gp_wish', []).indexOf(Number(id)) > -1; },
    toggleWish: function (id) {
      if (!cfg.USE_MOCK) {
        var on = this.isWished(id), n0 = Number(id);
        return (on ? request('/api/wishlist/' + id, { method: 'DELETE' }) : request('/api/wishlist', { method: 'POST', body: { productId: n0 } }))
          .then(function () {
            var ids = LS.get('gp_wish', []).filter(function (x) { return x !== n0; });
            if (!on) ids.push(n0);
            LS.set('gp_wish', ids);
            return { wished: !on };
          });
      }
      var need = requireLogin(); if (need) return need;
      var ids = LS.get('gp_wish', []), n = Number(id), i = ids.indexOf(n);
      if (i > -1) ids.splice(i, 1); else ids.push(n);
      LS.set('gp_wish', ids);
      return delay({ wished: i === -1 }, 80);
    },

    /* ---------- 가격 하락 알림 ---------- */
    getAlerts: function () {
      if (!cfg.USE_MOCK) {
        // API_SPEC 8번 응답(alertId · targetPriceKrw)을 화면에서 쓰는 이름으로 맞춤
        return request('/api/alerts').then(function (list) {
          return (list || []).map(function (a) {
            return { id: a.alertId, productId: a.productId, productName: a.productName, targetPrice: a.targetPriceKrw, alertBasis: a.alertBasis,
              storeEventId: a.storeEventId, eventName: a.eventName, isActive: a.isActive, lastNotifiedAt: a.lastNotifiedAt, createdAt: a.createdAt };
          });
        });
      }
      var need = requireLogin(); if (need) return need;
      return delay(LS.get('gp_alerts', []));
    },
    /**
     * basis: TARGET_PRICE(기본, targetPrice 필수) · PERIOD_LOWEST(최근 30일 최저가보다 내려가면, targetPrice 없음)
     * 같은 조건 · 같은 상품은 서버에서 1건으로 유지 (목표가를 바꾸면 UPDATE)
     */
    createAlert: function (productId, targetPrice, basis) {
      basis = basis || 'TARGET_PRICE';
      if (!cfg.USE_MOCK) {
        var body = { alertBasis: basis, productId: Number(productId) };
        if (basis === 'TARGET_PRICE') body.targetPriceKrw = targetPrice;
        return request('/api/alerts', { method: 'POST', body: body });
      }
      var need = requireLogin(); if (need) return need;
      var list = LS.get('gp_alerts', []), p = findProduct(productId);
      list = list.filter(function (a) { return !(a.productId === Number(productId) && (a.alertBasis || 'TARGET_PRICE') === basis); });
      list.push({ id: Date.now(), alertBasis: basis, productId: Number(productId), productName: p ? p.name : '', targetPrice: basis === 'TARGET_PRICE' ? targetPrice : null, isActive: true, createdAt: new Date().toISOString() });
      LS.set('gp_alerts', list);
      return delay({ ok: true }, 80);
    },
    /**
     * 행사 알림 (백엔드 명세 6-9): basis = EVENT_START(행사 시작일 아침) · COUPON_EXPIRY(쿠폰 마감 하루 전)
     * 상품이 아니라 행사(storeEventId)에 거는 알림 → productId · targetPriceKrw 는 보내면 안 됨 (400)
     */
    createEventAlert: function (storeEventId, basis) {
      if (!cfg.USE_MOCK) return request('/api/alerts', { method: 'POST', body: { alertBasis: basis, storeEventId: Number(storeEventId) } });
      var need = requireLogin(); if (need) return need;
      var ev = M.events.filter(function (e) { return String(e.storeEventId || e.id) === String(storeEventId); })[0];
      var list = LS.get('gp_alerts', []).filter(function (a) { return !(String(a.storeEventId) === String(storeEventId) && a.alertBasis === basis); });
      var a = { id: Date.now(), alertBasis: basis, productId: null, productName: null, storeEventId: storeEventId, eventName: ev ? ev.name : '행사',
        targetPrice: null, isActive: true, createdAt: new Date().toISOString() };
      list.push(a);
      LS.set('gp_alerts', list);
      return delay(a, 80);
    },
    deleteAlert: function (alertId) {
      if (!cfg.USE_MOCK) return request('/api/alerts/' + alertId, { method: 'DELETE' });
      LS.set('gp_alerts', LS.get('gp_alerts', []).filter(function (a) { return a.id !== alertId; }));
      return delay(null, 60);
    },

    /* ---------- 받은 알림 (백엔드 명세 v1.1 6-10) ----------
       응답: { unreadCount, items: [{ notificationId, alertBasis, message, productId, storeEventId, sentAt, readAt }], page, totalPages } */
    getNotifications: function (opts) {
      opts = opts || {};
      if (!cfg.USE_MOCK) {
        return request('/api/notifications' + qs({ unread: opts.unread ? true : null, page: opts.page || 0, size: opts.size || 20 }))
          .then(function (r) { r.items = r.items || []; return r; });
      }
      var need = requireLogin(); if (need) return need;
      var items = LS.get('gp_notifs', []);
      if (opts.unread) items = items.filter(function (n) { return !n.readAt; });
      return delay({ unreadCount: LS.get('gp_notifs', []).filter(function (n) { return !n.readAt; }).length, items: items, page: 0, size: 20, totalElements: items.length, totalPages: 1 }, 80);
    },
    readNotification: function (notificationId) {
      if (!cfg.USE_MOCK) return request('/api/notifications/' + notificationId + '/read', { method: 'PATCH' });
      var list = LS.get('gp_notifs', []), now = new Date().toISOString();
      list.forEach(function (n) { if (n.notificationId === notificationId && !n.readAt) n.readAt = now; });
      LS.set('gp_notifs', list);
      return delay({ notificationId: notificationId, readAt: now }, 60);
    }
  };

  // 페이지가 열리면 로그인 상태를 서버와 한 번 맞춤. 화면 코드는 GP.api.ready.then(user => ...) 로 사용
  api.ready = refreshMe();
  return api;
})();
