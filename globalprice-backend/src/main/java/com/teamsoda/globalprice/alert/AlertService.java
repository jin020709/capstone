package com.teamsoda.globalprice.alert;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamsoda.globalprice.alert.PriceAlert.Basis;
import com.teamsoda.globalprice.catalog.CatalogRepository;
import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;
import com.teamsoda.globalprice.pricing.RuleRepository;

/** 알림 조건 CRUD (6-9) · 받은 알림 (6-10). 남의 것에 접근하면 404 */
@Service
public class AlertService {

    public record AlertRequest(String alertBasis, Long productId, Long storeEventId, BigDecimal targetPriceKrw) {
    }

    public record AlertView(long alertId, String alertBasis, Long productId, String productName, Long storeEventId,
                            String eventName, Long targetPriceKrw, boolean isActive, LocalDateTime lastNotifiedAt,
                            LocalDateTime createdAt) {
    }

    public record SaveResult(AlertView alert, boolean created) {
    }

    public record NotificationView(long notificationId, long alertId, String alertBasis, String message,
                                   Long productId, Long storeEventId, LocalDateTime sentAt, LocalDateTime readAt) {
    }

    public record NotificationPage(long unreadCount, List<NotificationView> items, int page, int size,
                                   long totalElements, int totalPages) {
    }

    public record ReadResult(long notificationId, LocalDateTime readAt) {
    }

    private final PriceAlertRepository alerts;
    private final NotificationRepository notifications;
    private final CatalogRepository catalog;
    private final RuleRepository rules;
    private final Clock clock;

    public AlertService(PriceAlertRepository alerts, NotificationRepository notifications, CatalogRepository catalog,
                        RuleRepository rules, Clock clock) {
        this.alerts = alerts;
        this.notifications = notifications;
        this.catalog = catalog;
        this.rules = rules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AlertView> list(long userId) {
        return alerts.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(this::view).toList();
    }

    /** 같은 조건(회원 · 기준 · 대상)이 있으면 UPDATE 후 200, 없으면 INSERT 후 201 */
    @Transactional
    public SaveResult save(long userId, AlertRequest req) {
        Basis basis = parseBasis(req.alertBasis());
        validate(basis, req);
        if (req.productId() != null && !catalog.productExists(req.productId())) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (req.storeEventId() != null && rules.event(req.storeEventId()).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "행사를 찾을 수 없어요.");
        }
        BigDecimal target = req.targetPriceKrw() == null ? null
                : req.targetPriceKrw().setScale(0, java.math.RoundingMode.HALF_UP);
        PriceAlert existing = alerts.findSame(userId, basis, req.productId(), req.storeEventId()).orElse(null);
        if (existing != null) {
            existing.update(target);
            return new SaveResult(view(existing), false);
        }
        try {
            PriceAlert a = alerts.saveAndFlush(new PriceAlert(userId, basis, req.productId(), req.storeEventId(), target,
                    LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)));
            return new SaveResult(view(a), true);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알림 조건을 저장하지 못했어요. 입력값을 확인해 주세요.");
        }
    }

    @Transactional
    public void delete(long userId, long alertId) {
        PriceAlert a = alerts.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "알림을 찾을 수 없어요."));
        alerts.delete(a);   // notifications 는 FK CASCADE 로 함께 삭제
    }

    @Transactional(readOnly = true)
    public NotificationPage notifications(long userId, boolean unreadOnly, int page, int size) {
        int s = Math.min(Math.max(size, 1), 50);
        int p = Math.max(page, 0);
        Page<Notification> result = notifications.findForUser(userId, unreadOnly, PageRequest.of(p, s));
        Map<Long, PriceAlert> alertById = new HashMap<>();
        alerts.findAllById(result.getContent().stream().map(Notification::getAlertId).distinct().toList())
                .forEach(a -> alertById.put(a.getId(), a));
        List<NotificationView> items = result.getContent().stream().map(n -> {
            PriceAlert a = alertById.get(n.getAlertId());
            return new NotificationView(n.getId(), n.getAlertId(), a == null ? null : a.getBasis().name(),
                    n.getMessage(), a == null ? null : a.getProductId(), a == null ? null : a.getStoreEventId(),
                    n.getSentAt(), n.getReadAt());
        }).toList();
        return new NotificationPage(notifications.countUnread(userId), items, p, s, result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional
    public ReadResult markRead(long userId, long notificationId) {
        Notification n = notifications.findForUser(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "알림을 찾을 수 없어요."));
        n.markRead(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        return new ReadResult(n.getId(), n.getReadAt());
    }

    /** DB chk_alert_target 과 같은 조합 검사 */
    static void validate(Basis basis, AlertRequest r) {
        boolean ok = switch (basis) {
            case TARGET_PRICE -> r.productId() != null && r.storeEventId() == null && r.targetPriceKrw() != null
                    && r.targetPriceKrw().signum() > 0;
            case PERIOD_LOWEST -> r.productId() != null && r.storeEventId() == null && r.targetPriceKrw() == null;
            case EVENT_START, COUPON_EXPIRY -> r.productId() == null && r.storeEventId() != null
                    && r.targetPriceKrw() == null;
        };
        if (!ok) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, switch (basis) {
                case TARGET_PRICE -> "목표가 알림은 상품과 0보다 큰 목표 가격이 필요해요.";
                case PERIOD_LOWEST -> "최저가 알림은 상품만 지정해 주세요.";
                default -> "행사 알림은 행사만 지정해 주세요.";
            });
        }
    }

    static Basis parseBasis(String s) {
        try {
            return Basis.valueOf(s == null ? "" : s.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "alertBasis 는 TARGET_PRICE · PERIOD_LOWEST · EVENT_START · COUPON_EXPIRY 중 하나예요.");
        }
    }

    private AlertView view(PriceAlert a) {
        String productName = a.getProductId() == null ? null : catalog.productName(a.getProductId()).orElse(null);
        String eventName = a.getStoreEventId() == null ? null
                : rules.event(a.getStoreEventId()).map(e -> e.eventName()).orElse(null);
        return new AlertView(a.getId(), a.getBasis().name(), a.getProductId(), productName, a.getStoreEventId(),
                eventName, a.getTargetPriceKrw() == null ? null : a.getTargetPriceKrw().longValue(), a.isActive(),
                a.getLastNotifiedAt(), a.getCreatedAt());
    }
}
