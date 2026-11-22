package com.teamsoda.globalprice.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamsoda.globalprice.alert.AlertService;
import com.teamsoda.globalprice.alert.AlertService.AlertRequest;
import com.teamsoda.globalprice.alert.AlertService.AlertView;
import com.teamsoda.globalprice.alert.AlertService.NotificationPage;
import com.teamsoda.globalprice.alert.AlertService.ReadResult;
import com.teamsoda.globalprice.auth.LoginInterceptor;
import com.teamsoda.globalprice.wishlist.WishlistService;
import com.teamsoda.globalprice.wishlist.WishlistService.WishlistItem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 로그인 필요 API: 관심 상품 (6-8) · 가격 알림 (6-9) · 받은 알림 (6-10). 인증은 LoginInterceptor.
 * 목록은 BACKEND_SPEC 6-8 · 6-9 에 맞춰 배열로 응답한다.
 */
@RestController
@RequestMapping("/api")
public class MemberController {

    public record WishlistForm(@NotNull(message = "productId 가 필요해요.") @Positive Long productId) {
    }

    private final WishlistService wishlist;
    private final AlertService alerts;

    public MemberController(WishlistService wishlist, AlertService alerts) {
        this.wishlist = wishlist;
        this.alerts = alerts;
    }

    // ---------- 관심 상품 ----------

    @GetMapping("/wishlist")
    public List<WishlistItem> wishlist(HttpServletRequest req) {
        return wishlist.list(LoginInterceptor.currentUserId(req));
    }

    @PostMapping("/wishlist")
    public ResponseEntity<WishlistItem> addWish(@Valid @RequestBody WishlistForm form, HttpServletRequest req) {
        var r = wishlist.add(LoginInterceptor.currentUserId(req), form.productId());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.item());
    }

    @DeleteMapping("/wishlist/{productId}")
    public ResponseEntity<Void> removeWish(@PathVariable("productId") long productId, HttpServletRequest req) {
        wishlist.remove(LoginInterceptor.currentUserId(req), productId);
        return ResponseEntity.noContent().build();
    }

    // ---------- 가격 알림 ----------

    @GetMapping("/alerts")
    public List<AlertView> alerts(HttpServletRequest req) {
        return alerts.list(LoginInterceptor.currentUserId(req));
    }

    @PostMapping("/alerts")
    public ResponseEntity<AlertView> saveAlert(@RequestBody AlertRequest body, HttpServletRequest req) {
        var r = alerts.save(LoginInterceptor.currentUserId(req), body);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.alert());
    }

    @DeleteMapping("/alerts/{alertId}")
    public ResponseEntity<Void> deleteAlert(@PathVariable("alertId") long alertId, HttpServletRequest req) {
        alerts.delete(LoginInterceptor.currentUserId(req), alertId);
        return ResponseEntity.noContent().build();
    }

    // ---------- 받은 알림 ----------

    @GetMapping("/notifications")
    public NotificationPage notifications(@RequestParam(value = "unread", defaultValue = "false") boolean unread,
                                          @RequestParam(value = "page", defaultValue = "0") int page,
                                          @RequestParam(value = "size", defaultValue = "20") int size,
                                          HttpServletRequest req) {
        return alerts.notifications(LoginInterceptor.currentUserId(req), unread, page, size);
    }

    @PatchMapping("/notifications/{id}/read")
    public ReadResult read(@PathVariable("id") long id, HttpServletRequest req) {
        return alerts.markRead(LoginInterceptor.currentUserId(req), id);
    }
}
