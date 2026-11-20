package com.teamsoda.globalprice.alert;

import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamsoda.globalprice.alert.PriceAlert.Basis;
import com.teamsoda.globalprice.catalog.CatalogRepository;
import com.teamsoda.globalprice.pricing.PricingModels.EventType;
import com.teamsoda.globalprice.pricing.PricingModels.StoreEvent;
import com.teamsoda.globalprice.pricing.RuleRepository;

/**
 * 알림 판정 (명세서 7-3). 같은 알림은 하루 1회(last_notified_at 날짜가 오늘이면 건너뜀).
 * 조건을 만족하면 notifications 저장 + last_notified_at 갱신을 한 트랜잭션으로 처리한다.
 */
@Service
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    /** 상품별 판정 입력. currentKrw = 현재 예상 구매비용(쿠폰 제외, 품절 제외), previousLowest30d = 이번 갱신 전 30일 최저 */
    public record ProductPrice(long productId, Long currentKrw, Long previousLowest30d) {
    }

    private final PriceAlertRepository alerts;
    private final NotificationRepository notifications;
    private final CatalogRepository catalog;
    private final RuleRepository rules;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AlertEvaluator(PriceAlertRepository alerts, NotificationRepository notifications, CatalogRepository catalog,
                          RuleRepository rules, PlatformTransactionManager txManager, Clock clock) {
        this.alerts = alerts;
        this.notifications = notifications;
        this.catalog = catalog;
        this.rules = rules;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** TARGET_PRICE · PERIOD_LOWEST. 만든 알림 수 */
    public int evaluatePrices(Map<Long, ProductPrice> prices) {
        if (prices.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        int created = 0;
        for (PriceAlert a : alerts.findByActiveTrueAndBasisInAndProductIdIn(
                EnumSet.of(Basis.TARGET_PRICE, Basis.PERIOD_LOWEST), prices.keySet())) {
            ProductPrice p = prices.get(a.getProductId());
            if (p == null || p.currentKrw() == null || notifiedToday(a, now)) continue;
            String name = catalog.productName(a.getProductId()).orElse("관심 상품");
            String message = null;
            if (a.getBasis() == Basis.TARGET_PRICE && a.getTargetPriceKrw() != null
                    && p.currentKrw() <= a.getTargetPriceKrw().longValue()) {
                message = name + "의 예상 구매비용이 " + won(p.currentKrw()) + "으로 목표 가격 "
                        + won(a.getTargetPriceKrw().longValue()) + " 이하가 되었어요.";
            } else if (a.getBasis() == Basis.PERIOD_LOWEST && p.previousLowest30d() != null
                    && p.currentKrw() < p.previousLowest30d()) {
                message = name + "의 예상 구매비용이 " + won(p.currentKrw()) + "으로 최근 30일 최저가("
                        + won(p.previousLowest30d()) + ")보다 낮아졌어요.";
            }
            if (message != null && send(a, message, now)) created++;
        }
        return created;
    }

    /** EVENT_START(시작일이 오늘) · COUPON_EXPIRY(쿠폰 마감일이 내일) */
    public int evaluateEvents() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = now.toLocalDate();
        int created = 0;
        List<PriceAlert> list = alerts.findByActiveTrueAndBasisIn(EnumSet.of(Basis.EVENT_START, Basis.COUPON_EXPIRY));
        for (PriceAlert a : list) {
            if (a.getStoreEventId() == null || notifiedToday(a, now)) continue;
            StoreEvent e = rules.event(a.getStoreEventId()).orElse(null);
            if (e == null || !e.active()) continue;
            String message = null;
            if (a.getBasis() == Basis.EVENT_START && e.startAt().toLocalDate().equals(today)) {
                message = "'" + e.eventName() + "' 행사가 오늘 시작했어요.";
            } else if (a.getBasis() == Basis.COUPON_EXPIRY && e.eventType() == EventType.COUPON
                    && e.endAt().toLocalDate().equals(today.plusDays(1))) {
                message = "'" + e.eventName() + "' 쿠폰이 내일 마감돼요.";
            }
            if (message != null && send(a, message, now)) created++;
        }
        return created;
    }

    private boolean send(PriceAlert a, String message, LocalDateTime now) {
        try {
            tx.executeWithoutResult(s -> {
                PriceAlert fresh = alerts.findById(a.getId()).orElseThrow();
                if (notifiedToday(fresh, now)) return;
                notifications.save(new Notification(fresh.getId(), message, now));
                fresh.markNotified(now);
                alerts.save(fresh);
            });
            return true;
        } catch (RuntimeException e) {
            log.warn("알림 저장 실패 alert={}: {}", a.getId(), e.getMessage());
            return false;
        }
    }

    static boolean notifiedToday(PriceAlert a, LocalDateTime now) {
        return a.getLastNotifiedAt() != null && a.getLastNotifiedAt().toLocalDate().equals(now.toLocalDate());
    }

    static String won(long v) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(v) + "원";
    }
}
