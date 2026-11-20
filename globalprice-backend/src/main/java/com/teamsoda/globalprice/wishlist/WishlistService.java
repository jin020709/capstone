package com.teamsoda.globalprice.wishlist;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamsoda.globalprice.catalog.CatalogRepository;
import com.teamsoda.globalprice.catalog.ProductService;
import com.teamsoda.globalprice.catalog.Views.OfferView;
import com.teamsoda.globalprice.catalog.Views.ProductSummary;
import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;

/** 관심 상품. 목록의 가격은 저장된 최신값으로 계산한다 (외부 호출 없음) */
@Service
public class WishlistService {

    /**
     * 프론트 API_SPEC 7: ProductSummary 배열. 명세서 6-8 의 wishlistId · productId · productName · lowestTotalKrw ·
     * createdAt 도 같이 담는다.
     */
    public record WishlistItem(long wishlistId, long id, long productId, String name, String productName,
                               String imageUrl, String categoryName, List<String> badges, int offerCount,
                               Long bestTotal, Long bestNormalTotal, Long lowestTotalKrw, String bestStore,
                               List<OfferView> offers, LocalDateTime createdAt) {
    }

    public record AddResult(WishlistItem item, boolean created) {
    }

    private final WishlistRepository repository;
    private final CatalogRepository catalog;
    private final ProductService products;
    private final Clock clock;

    public WishlistService(WishlistRepository repository, CatalogRepository catalog, ProductService products,
                           Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.products = products;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WishlistItem> list(long userId) {
        List<Wishlist> rows = repository.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        Map<Long, ProductSummary> summaries = products.summaries(rows.stream().map(Wishlist::getProductId).toList())
                .stream().collect(Collectors.toMap(ProductSummary::id, Function.identity()));
        List<WishlistItem> out = new ArrayList<>();
        for (Wishlist w : rows) {
            ProductSummary s = summaries.get(w.getProductId());
            out.add(item(w, s, w.getProductId()));
        }
        return out;
    }

    @Transactional
    public AddResult add(long userId, long productId) {
        if (!catalog.productExists(productId)) throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        Wishlist existing = repository.findByUserIdAndProductId(userId, productId).orElse(null);
        boolean created = false;
        if (existing == null) {
            try {
                existing = repository.saveAndFlush(new Wishlist(userId, productId,
                        LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)));
                created = true;
            } catch (DataIntegrityViolationException e) {
                existing = repository.findByUserIdAndProductId(userId, productId).orElseThrow(() -> e);
            }
        }
        ProductSummary s = products.summaries(List.of(productId)).stream().findFirst().orElse(null);
        return new AddResult(item(existing, s, productId), created);
    }

    @Transactional
    public void remove(long userId, long productId) {
        // 멱등: 이미 없으면 그대로 204 (본인 것만 지우므로 남의 찜 존재 여부는 드러나지 않음)
        repository.findByUserIdAndProductId(userId, productId).ifPresent(repository::delete);
    }

    @Transactional(readOnly = true)
    public boolean contains(long userId, long productId) {
        return repository.existsByUserIdAndProductId(userId, productId);
    }

    private WishlistItem item(Wishlist w, ProductSummary s, long productId) {
        String name = s != null ? s.name() : catalog.productName(productId).orElse(null);
        return new WishlistItem(w.getId(), productId, productId, name, name, s == null ? null : s.imageUrl(),
                s == null ? "" : s.categoryName(), s == null ? List.of() : s.badges(), s == null ? 0 : s.offerCount(),
                s == null ? null : s.bestTotal(), s == null ? null : s.bestNormalTotal(),
                s == null ? null : s.bestTotal(), s == null ? null : s.bestStore(),
                s == null ? List.of() : s.offers(), w.getCreatedAt());
    }
}
