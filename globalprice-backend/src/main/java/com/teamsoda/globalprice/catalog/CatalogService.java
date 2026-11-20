package com.teamsoda.globalprice.catalog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.source.MallSource.MallOffer;

/**
 * 소스 응답 → DB 저장 (명세서 4-3).
 * offer 마다 트랜잭션을 따로 둔다: 한 건 저장에 실패해도 나머지는 저장되고, 실패한 건은 응답에서 뺀다.
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    static final Duration HISTORY_MIN_GAP = Duration.ofHours(6);

    private final CatalogRepository repository;
    private final OfferPricer pricer;
    private final TransactionTemplate tx;

    public CatalogService(CatalogRepository repository, OfferPricer pricer, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.pricer = pricer;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 저장에 성공한 offer 만 productId 를 붙여 돌려준다 */
    public List<OfferData> save(long mallId, List<MallOffer> offers, OfferPricer.Context ctx) {
        List<OfferData> out = new ArrayList<>();
        for (MallOffer o : offers) {
            try {
                out.add(saveOne(mallId, o, ctx));
            } catch (DuplicateKeyException e) {
                // 같은 상품을 동시에 처음 저장한 경우: 다른 요청이 먼저 넣었으므로 한 번 더 시도하면 UPDATE 경로
                try {
                    out.add(saveOne(mallId, o, ctx));
                } catch (RuntimeException again) {
                    log.warn("상품 저장 실패 (응답에서 제외): mall={} id={} - {}", mallId, o.externalProductId(),
                            again.getMessage());
                }
            } catch (RuntimeException e) {
                log.warn("상품 저장 실패 (응답에서 제외): mall={} id={} - {}", mallId, o.externalProductId(),
                        e.getMessage());
            }
        }
        return out;
    }

    private OfferData saveOne(long mallId, MallOffer o, OfferPricer.Context ctx) {
        return tx.execute(status -> {
            CatalogRepository.SavedIds ids = repository.upsert(mallId, o, ctx.now());
            OfferData data = OfferData.of(o, ids, mallId, ctx.now());
            repository.recordHistory(ids.mallVariantId(), o.price(), o.currencyCode(), pricer.historyTotal(data, ctx),
                    ctx.now(), HISTORY_MIN_GAP);
            return data;
        });
    }
}
