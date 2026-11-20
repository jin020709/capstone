/* 마이페이지: 관심 상품(찜) · 받은 알림 · 설정한 가격 알림
   실제 서버 모드 기준은 백엔드 명세 v1.1 6-8 ~ 6-10 */
(function () {
  var U = GP.ui, P = GP.price;
  var notiPage = 0;

  function thumb(p) {
    return '<a class="thumb" href="detail.html?id=' + p.id + '" aria-label="' + U.esc(p.name) + '">' +
      (p.imageUrl ? '<img src="' + U.esc(p.imageUrl) + '" alt="" loading="lazy" referrerpolicy="no-referrer">' : '[이미지]') + '</a>';
  }

  function loadWish() {
    var box = U.$('#wishList');
    box.innerHTML = U.stateHTML('loading', '불러오는 중…');
    GP.api.getWishlist().then(function (list) {
      list = list || [];
      if (!list.length) { box.innerHTML = U.stateHTML('empty', '아직 담은 상품이 없어요. 상품 상세에서 ♡ 관심 상품을 눌러 보세요.'); return; }
      box.innerHTML = list.map(function (p) {
        var sub = [p.bestStore ? (p.offerCount > 1 ? '최저 ' : '') + p.bestStore : null, p.offerCount > 1 ? '판매처 ' + p.offerCount + '곳' : null].filter(Boolean).join(' · ');
        // 품절 · 환율 없음 등으로 비교할 가격이 없으면 bestTotal 이 null
        var price = p.bestTotal != null ? '<span class="price" style="font-size:18px">' + P.won(p.bestTotal) + '</span>' : '<span class="small muted">가격 확인 불가</span>';
        return '<div class="list-row">' + thumb(p) +
          '<div style="flex:1;min-width:0"><a href="detail.html?id=' + p.id + '" style="font-weight:700;color:var(--text);text-decoration:none">' + U.esc(p.name) + '</a>' +
          (sub ? '<div class="small muted">' + U.esc(sub) + '</div>' : '') + '</div>' + price +
          '<button type="button" class="btn btn-line btn-sm" data-unwish="' + p.id + '">삭제</button></div>';
      }).join('');
    }).catch(function (e) { box.innerHTML = U.errorHTML(e); });
  }

  // 알림 조건(alertBasis)별 표시
  var BASIS = {
    TARGET_PRICE: function (a) { return '목표 ' + P.won(a.targetPrice); },
    PERIOD_LOWEST: function () { return '최근 30일 최저가'; },
    EVENT_START: function () { return '행사 시작'; },
    COUPON_EXPIRY: function () { return '쿠폰 마감 하루 전'; }
  };
  function loadAlerts() {
    var box = U.$('#alertList');
    GP.api.getAlerts().then(function (list) {
      list = list || [];
      if (!list.length) { box.innerHTML = U.stateHTML('empty', '설정한 알림이 없어요. 상품 상세에서 목표 가격을 입력해 보세요.'); return; }
      box.innerHTML = list.map(function (a) {
        var basis = a.alertBasis || 'TARGET_PRICE';
        var title = a.productId != null
          ? '<a href="detail.html?id=' + a.productId + '" style="font-weight:700;color:var(--text);text-decoration:none">' + U.esc(a.productName || '상품 ' + a.productId) + '</a>'
          : '<b>' + U.esc(a.eventName || '행사 알림') + '</b>';
        var meta = ['설정 ' + U.fmtTime(a.createdAt)];
        if (a.lastNotifiedAt) meta.push('마지막 알림 ' + U.ago(a.lastNotifiedAt));
        if (a.isActive === false) meta.push('꺼짐');
        return '<div class="list-row"><div style="flex:1;min-width:0">' + title +
          '<div class="small muted">' + U.esc(meta.join(' · ')) + '</div></div>' +
          '<span style="font-weight:700">' + U.esc((BASIS[basis] || BASIS.TARGET_PRICE)(a)) + '</span>' +
          '<button type="button" class="btn btn-line btn-sm" data-unalert="' + a.id + '">삭제</button></div>';
      }).join('');
    }).catch(function (e) { box.innerHTML = U.errorHTML(e); });
  }

  function loadNotis() {
    var box = U.$('#notiList');
    GP.api.getNotifications({ page: notiPage, size: 10 }).then(function (r) {
      var items = r.items || [], unread = r.unreadCount || 0;
      U.$('#notiCount').textContent = unread ? '안 읽음 ' + unread : '';
      U.$('#notiReadAll').classList.toggle('hidden', !items.some(function (n) { return !n.readAt; }));
      if (!items.length) { box.innerHTML = U.stateHTML('empty', '받은 알림이 없어요. 가격 알림을 설정하면 조건에 맞을 때 여기에 쌓여요.'); }
      else box.innerHTML = items.map(function (n) {
        var body = '<span class="noti-msg">' + U.esc(n.message) + '</span><span class="small muted">' + U.esc(U.ago(n.sentAt)) + '</span>';
        var inner = n.productId != null ? '<a href="detail.html?id=' + n.productId + '" data-noti="' + n.notificationId + '">' + body + '</a>' : '<div>' + body + '</div>';
        return '<div class="list-row noti-row' + (n.readAt ? ' is-read' : '') + '">' + (n.readAt ? '' : '<span class="noti-dot" aria-label="안 읽음"></span>') +
          '<div class="noti-body">' + inner + '</div>' +
          (n.readAt ? '' : '<button type="button" class="btn btn-line btn-sm" data-read="' + n.notificationId + '">읽음</button>') + '</div>';
      }).join('');
      var nav = U.$('#notiPager'), total = r.totalPages || 1;
      nav.classList.toggle('hidden', total <= 1);
      nav.innerHTML = total <= 1 ? '' :
        '<button type="button" data-npage="' + (notiPage - 1) + '"' + (notiPage === 0 ? ' disabled' : '') + ' aria-label="이전 페이지">‹</button>' +
        '<span class="small muted">' + (notiPage + 1) + ' / ' + total + '</span>' +
        '<button type="button" data-npage="' + (notiPage + 1) + '"' + (notiPage >= total - 1 ? ' disabled' : '') + ' aria-label="다음 페이지">›</button>';
    }).catch(function (e) { box.innerHTML = U.errorHTML(e); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    GP.api.ready.then(init);
  });

  function init(user) {
    if (!user) {
      U.$('#needLogin').classList.remove('hidden');
      ['#wish', '#notis', '#alerts'].forEach(function (s) { U.$(s).classList.add('hidden'); });
      return;
    }
    loadWish();
    if (GP.config.FEATURES.alerts) { loadNotis(); loadAlerts(); }
    else {
      U.$('#notis').classList.add('hidden');
      U.$('#alertList').innerHTML = U.stateHTML('empty', '가격 하락 알림은 2차 확장 기능으로 준비 중이에요. 관심 상품에 담아 두면 가격을 바로 확인할 수 있어요.');
    }
    var oops = function (e) { U.toast(U.errorInfo(e).title); };
    document.addEventListener('click', function (e) {
      var w = e.target.closest('[data-unwish]'), a = e.target.closest('[data-unalert]');
      var rd = e.target.closest('[data-read]'), pg = e.target.closest('[data-npage]'), link = e.target.closest('a[data-noti]');
      if (w) GP.api.toggleWish(w.getAttribute('data-unwish')).then(loadWish).catch(oops);
      if (a) GP.api.deleteAlert(Number(a.getAttribute('data-unalert'))).then(loadAlerts).catch(oops);
      if (rd) GP.api.readNotification(Number(rd.getAttribute('data-read'))).then(loadNotis).catch(oops);
      if (link) GP.api.readNotification(Number(link.getAttribute('data-noti'))).catch(function () { /* 이동은 그대로 */ }); // 상품으로 가면 읽음 처리
      if (pg && !pg.disabled) { notiPage = Number(pg.getAttribute('data-npage')); loadNotis(); }
    });
    U.$('#notiReadAll').addEventListener('click', function () {
      // 서버에 "모두 읽음" API 가 없어 이 페이지에 보이는 안 읽은 알림을 하나씩 읽음 처리
      var ids = U.$all('[data-read]').map(function (b) { return Number(b.getAttribute('data-read')); });
      Promise.all(ids.map(function (id) { return GP.api.readNotification(id).catch(function () { return null; }); })).then(loadNotis);
    });
  }
})();
