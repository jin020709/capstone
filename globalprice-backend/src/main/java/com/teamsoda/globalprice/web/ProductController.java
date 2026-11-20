package com.teamsoda.globalprice.web;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teamsoda.globalprice.auth.LoginInterceptor;
import com.teamsoda.globalprice.catalog.ProductService;
import com.teamsoda.globalprice.catalog.ProductService.PriceHistory;
import com.teamsoda.globalprice.catalog.ProductService.ProductDetail;
import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;
import com.teamsoda.globalprice.search.SearchService;
import com.teamsoda.globalprice.search.SearchService.SearchRequest;
import com.teamsoda.globalprice.search.SearchService.SearchResponse;
import com.teamsoda.globalprice.wishlist.WishlistService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final SearchService search;
    private final ProductService products;
    private final WishlistService wishlist;

    public ProductController(SearchService search, ProductService products, WishlistService wishlist) {
        this.search = search;
        this.products = products;
        this.wishlist = wishlist;
    }

    /**
     * 검색. 명세서 파라미터(freeShipping · onSale · inStock · sort=price_asc|discount_desc)와
     * 프론트 파라미터(benefits=event,freeShip,taxIncluded · sort=total|discount|popular|rating)를 모두 받는다.
     */
    @GetMapping
    public SearchResponse search(@RequestParam(value = "q", required = false) String q,
                                 @RequestParam(value = "stores", required = false) String stores,
                                 @RequestParam(value = "countries", required = false) String countries,
                                 @RequestParam(value = "minPrice", required = false) Long minPrice,
                                 @RequestParam(value = "maxPrice", required = false) Long maxPrice,
                                 @RequestParam(value = "freeShipping", defaultValue = "false") boolean freeShipping,
                                 @RequestParam(value = "onSale", defaultValue = "false") boolean onSale,
                                 @RequestParam(value = "inStock", defaultValue = "false") boolean inStock,
                                 @RequestParam(value = "benefits", required = false) String benefits,
                                 @RequestParam(value = "coupon", defaultValue = "false") boolean coupon,
                                 @RequestParam(value = "sort", required = false) String sort,
                                 @RequestParam(value = "page", defaultValue = "0") int page,
                                 @RequestParam(value = "size", defaultValue = "20") int size,
                                 @RequestParam(value = "brands", required = false) String brands,
                                 @RequestParam(value = "models", required = false) String models,
                                 @RequestParam(value = "storages", required = false) String storages,
                                 @RequestParam(value = "colors", required = false) String colors) {
        if (page < 0 || size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page 는 0 이상, size 는 1~50 이에요.");
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "최소 금액이 최대 금액보다 커요.");
        }
        Set<String> b = csv(benefits, false);
        return search.search(new SearchRequest(q, csv(stores, false), csv(countries, true), minPrice, maxPrice,
                freeShipping || b.contains("freeship"), onSale || b.contains("event"), inStock,
                b.contains("taxincluded"), coupon, SearchService.Sort.parse(sort), page, size),
                new SearchService.FacetFilter(csv(brands, false), csv(models, false), csv(storages, false),
                        csv(colors, false)));
    }

    @GetMapping("/{id}")
    public ProductDetail detail(@PathVariable("id") long id,
                                @RequestParam(value = "coupon", defaultValue = "false") boolean coupon,
                                HttpServletRequest request) {
        Long userId = LoginInterceptor.currentUserIdOrNull(request);
        Boolean wished = userId == null ? null : wishlist.contains(userId, id);
        return products.detail(id, coupon, wished);
    }

    @GetMapping("/{id}/price-history")
    public PriceHistory history(@PathVariable("id") long id,
                                @RequestParam(value = "period", defaultValue = "1m") String period) {
        return products.history(id, period);
    }

    static Set<String> csv(String s, boolean upper) {
        if (s == null || s.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(s.split(",")).map(String::strip).filter(v -> !v.isEmpty())
                .map(v -> upper ? v.toUpperCase(Locale.ROOT) : v.toLowerCase(Locale.ROOT)).forEach(out::add);
        return out;
    }
}
