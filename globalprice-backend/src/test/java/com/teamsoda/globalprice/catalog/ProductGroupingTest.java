package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 같은 상품 묶기 판단 (DB 없이, 메모리 Store 로) */
class ProductGroupingTest {

    /** 메모리 Store: products · 리스팅 수 · 알림 수 · 찜을 흉내 */
    static class MemoryStore implements ProductGrouping.Store {
        record Product(String name, String image, String key) {
        }

        final Map<Long, Product> products = new HashMap<>();
        final Map<Long, Long> listingToProduct = new HashMap<>();   // mall_product_id → product_id
        final Map<Long, Integer> alerts = new HashMap<>();
        final Map<Long, List<String>> wishlists = new HashMap<>();   // product_id → 사용자
        final List<String> log = new ArrayList<>();
        long nextId = 100;

        long product(String name, String key) {
            long id = nextId++;
            products.put(id, new Product(name, null, key));
            return id;
        }

        void listing(long mallProductId, long productId) {
            listingToProduct.put(mallProductId, productId);
        }

        @Override
        public Long productByKey(String key) {
            return products.entrySet().stream().filter(e -> key.equals(e.getValue().key())).map(Map.Entry::getKey)
                    .findFirst().orElse(null);
        }

        @Override
        public String matchKey(long productId) {
            return products.get(productId).key();
        }

        @Override
        public long insertProduct(String name, String imageUrl, String matchKey) {
            long id = nextId++;
            products.put(id, new Product(name, imageUrl, matchKey));
            log.add("insert " + id);
            return id;
        }

        @Override
        public void fillImage(long productId, String imageUrl) {
            Product p = products.get(productId);
            if (p.image() == null) products.put(productId, new Product(p.name(), imageUrl, p.key()));
        }

        @Override
        public void setKeyAndName(long productId, String matchKey, String name) {
            products.put(productId, new Product(name, products.get(productId).image(), matchKey));
        }

        @Override
        public void rename(long productId, String name) {
            Product p = products.get(productId);
            products.put(productId, new Product(name, p.image(), p.key()));
        }

        @Override
        public int listingCount(long productId) {
            return (int) listingToProduct.values().stream().filter(v -> v == productId).count();
        }

        @Override
        public int alertCount(long productId) {
            return alerts.getOrDefault(productId, 0);
        }

        @Override
        public void moveListing(long mallProductId, long productId) {
            listingToProduct.put(mallProductId, productId);
        }

        @Override
        public void moveWishlists(long fromProductId, long toProductId) {
            List<String> from = wishlists.remove(fromProductId);
            if (from != null) wishlists.computeIfAbsent(toProductId, k -> new ArrayList<>()).addAll(from);
        }

        @Override
        public void deleteProduct(long productId) {
            products.remove(productId);
            log.add("delete " + productId);
        }
    }

    MemoryStore store = new MemoryStore();
    ProductGrouping grouping = new ProductGrouping(store);

    static ProductMatcher.Match match(String title) {
        return ProductMatcher.match(title);
    }

    @Test
    void newListing_joinsProductWithSameKey_orCreatesOne() {
        ProductMatcher.Match m = match("Apple iPhone 17 256GB Lavender Unlocked");
        long first = grouping.productForNewListing(m, "Apple iPhone 17 256GB Lavender Unlocked", "img1");
        assertThat(store.products.get(first).key()).isEqualTo(m.key());
        assertThat(store.products.get(first).name()).isEqualTo("Apple iPhone 17 256GB");   // 정리된 대표 이름

        long second = grouping.productForNewListing(match("iPhone 17 256 GB Black"), "iPhone 17 256 GB Black", "img2");
        assertThat(second).isEqualTo(first);
        assertThat(store.products.get(first).image()).isEqualTo("img1");   // 이미지는 비어 있을 때만 채움
    }

    @Test
    void newListing_withoutKey_getsOwnProduct() {
        long id = grouping.productForNewListing(null, "abc", null);
        assertThat(store.products.get(id).key()).isNull();
        assertThat(store.products.get(id).name()).isEqualTo("abc");
    }

    @Test
    void existingListing_aloneOldProduct_getsKeyInPlace() {   // 예전 방식(키 없는 상품 1 : 리스팅 1)
        long old = store.product("Apple iPhone 17 256GB Lavender", null);
        store.listing(1, old);
        ProductMatcher.Match m = match("Apple iPhone 17 256GB Lavender");
        assertThat(grouping.productForExistingListing(1, old, m, "Apple iPhone 17 256GB Lavender", null)).isEqualTo(old);
        assertThat(store.products.get(old).key()).isEqualTo(m.key());
    }

    @Test
    void existingListing_aloneOldProduct_mergesIntoKeyedProduct_andMovesWishlists() {
        ProductMatcher.Match m = match("Apple iPhone 17 256GB");
        long keyed = store.product("Apple iPhone 17 256GB", m.key());
        store.listing(1, keyed);
        long old = store.product("iPhone 17 256GB Black", null);
        store.listing(2, old);
        store.wishlists.put(old, new ArrayList<>(List.of("user1")));

        long result = grouping.productForExistingListing(2, old, match("iPhone 17 256GB Black"), "iPhone 17 256GB Black",
                null);
        assertThat(result).isEqualTo(keyed);
        assertThat(store.listingToProduct.get(2L)).isEqualTo(keyed);
        assertThat(store.wishlists.get(keyed)).containsExactly("user1");
        assertThat(store.products).doesNotContainKey(old);
    }

    @Test
    void existingListing_aloneWithAlert_staysSoAlertKeepsWorking() {
        ProductMatcher.Match m = match("Apple iPhone 17 256GB");
        long keyed = store.product("Apple iPhone 17 256GB", m.key());
        store.listing(1, keyed);
        long old = store.product("iPhone 17 256GB Black", null);
        store.listing(2, old);
        store.alerts.put(old, 1);

        assertThat(grouping.productForExistingListing(2, old, m, "iPhone 17 256GB Black", null)).isEqualTo(old);
        assertThat(store.products).containsKey(old);
    }

    @Test
    void existingListing_inSharedProduct_movesOutWithoutDeletingProduct() {   // 규칙이 고쳐져 키가 바뀐 리스팅
        long shared = store.product("Apple iPhone 17 256GB", "iphone|17|256");
        store.listing(1, shared);
        store.listing(2, shared);
        ProductMatcher.Match air = match("Apple iPhone Air 256GB");

        long result = grouping.productForExistingListing(2, shared, air, "Apple iPhone Air 256GB", null);
        assertThat(result).isNotEqualTo(shared);
        assertThat(store.products.get(result).key()).isEqualTo(air.key());
        assertThat(store.products).containsKey(shared);
        assertThat(store.listingToProduct.get(1L)).isEqualTo(shared);
    }

    @Test
    void sameKey_titleKeyedProduct_takesLatestListingName() {
        ProductMatcher.Match m = match("Some Gadget Deluxe Edition");
        assertThat(m.key()).startsWith("t:");
        long id = store.product("Some Gadget Deluxe Edition Some Gadget Deluxe Edition", m.key());   // 예전 중복 이름
        store.listing(1, id);
        grouping.productForExistingListing(1, id, m, "Some Gadget Deluxe Edition", null);
        assertThat(store.products.get(id).name()).isEqualTo("Some Gadget Deluxe Edition");
    }
}
