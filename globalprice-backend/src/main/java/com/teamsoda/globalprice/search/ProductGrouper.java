package com.teamsoda.globalprice.search;

import java.util.List;

import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;

/** 같은 상품 묶기. 구현은 {@link SameProductGrouper} (productId 기준) */
public interface ProductGrouper {

    List<ProductGroup> group(List<PricedOffer> offers);
}
