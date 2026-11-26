package com.teamsoda.globalprice.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;

/**
 * 같은 상품 묶기: 저장할 때 같은 상품(ProductMatcher 키가 같은 리스팅)은 같은 productId 를 받으므로,
 * 검색 결과는 productId 로 모으면 된다. 순서는 처음 나온 순서 (정렬은 SearchService 가 따로 함)
 */
@Component
public class SameProductGrouper implements ProductGrouper {

    @Override
    public List<ProductGroup> group(List<PricedOffer> offers) {
        Map<Long, List<PricedOffer>> byProduct = new LinkedHashMap<>();
        for (PricedOffer o : offers) {
            List<PricedOffer> list = byProduct.computeIfAbsent(o.data().productId(), k -> new ArrayList<>());
            // 같은 쇼핑몰 옵션이 두 번 오면 (캐시 + 새 응답 등) 한 번만
            if (list.stream().noneMatch(x -> x.data().mallVariantId() == o.data().mallVariantId())) list.add(o);
        }
        List<ProductGroup> out = new ArrayList<>();
        for (List<PricedOffer> list : byProduct.values()) {
            PricedOffer first = list.getFirst();
            String image = list.stream().map(x -> x.data().imageUrl()).filter(i -> i != null && !i.isBlank())
                    .findFirst().orElse(null);
            out.add(new ProductGroup(first.data().productId(), first.data().productName(), image,
                    first.data().matchKey(), List.copyOf(list)));
        }
        return out;
    }
}
