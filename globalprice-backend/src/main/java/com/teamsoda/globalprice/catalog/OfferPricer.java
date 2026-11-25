package com.teamsoda.globalprice.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.pricing.PriceCalculator;
import com.teamsoda.globalprice.pricing.PricingModels.Breakdown;
import com.teamsoda.globalprice.pricing.PricingModels.FxTable;
import com.teamsoda.globalprice.pricing.PricingModels.PriceInput;
import com.teamsoda.globalprice.pricing.PricingModels.PricingRules;
import com.teamsoda.globalprice.pricing.PricingModels.RateUnavailableException;
import com.teamsoda.globalprice.pricing.RuleRepository;
import com.teamsoda.globalprice.rate.ExchangeRateService;

/** DB 의 규칙 · 환율을 모아 PriceCalculator 에 넘기는 얇은 층 */
@Component
public class OfferPricer {

    private static final Logger log = LoggerFactory.getLogger(OfferPricer.class);

    /** 한 요청 동안 같은 규칙 · 환율 · 시각으로 계산하기 위한 묶음 */
    public record Context(FxTable fx, PricingRules rules, Map<Long, Mall> malls, LocalDateTime now) {
        /** 쇼핑몰 나라 (shopping_malls.country_code, 모르면 null) */
        public String countryOf(long mallId) {
            Mall m = malls.get(mallId);
            return m == null ? null : m.countryCode();
        }
    }

    private final ExchangeRateService rates;
    private final RuleRepository rules;
    private final MallDirectory malls;
    private final Clock clock;
    private final Duration staleAfter;

    public OfferPricer(ExchangeRateService rates, RuleRepository rules, MallDirectory malls, Clock clock,
                       GlobalPriceProperties props) {
        this.rates = rates;
        this.rules = rules;
        this.malls = malls;
        this.clock = clock;
        this.staleAfter = props.search().staleAfter();
    }

    public Context context() {
        // DB DATETIME 은 초 단위 → 저장 · 비교하는 시각을 초로 맞춘다 (밀리초가 반올림돼 순서가 뒤바뀌지 않게)
        return new Context(rates.currentTable(), rules.activeRules(), malls.all(),
                LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    /**
     * @param refreshedNow 이번 요청에서 쇼핑몰 API 로 방금 받은 값이면 true (stale 판정 안 함)
     */
    public PricedOffer price(OfferData d, Context ctx, boolean couponEnabled, boolean refreshedNow) {
        PriceInput in = input(d, ctx);
        try {
            Breakdown applied = PriceCalculator.calculate(in, ctx.fx(), ctx.rules(), ctx.now(), couponEnabled);
            PricingRules noEvents = new PricingRules(ctx.rules().shippingRules(), ctx.rules().taxRules(),
                    ctx.rules().feeRules(), java.util.List.of(), ctx.rules().preferredFeeName());
            Breakdown normal = PriceCalculator.calculate(in, ctx.fx(), noEvents, ctx.now(), false);
            if (applied.ruleMissing()) log.warn("ruleMissing: mall={} currency={}", d.mallId(), d.currency());
            boolean fresh = refreshedNow || (d.fetchedAt() != null
                    && !d.fetchedAt().isBefore(ctx.now().minus(staleAfter)));
            return new PricedOffer(d, applied, normal, fresh ? PriceStatus.ok : PriceStatus.stale);
        } catch (RateUnavailableException e) {
            return new PricedOffer(d, null, null, PriceStatus.unavailable);
        }
    }

    /** 가격 이력에 남길 기본 계산값 (쿠폰 제외, SALE 은 적용). 계산 불가면 null */
    public Long historyTotal(OfferData d, Context ctx) {
        try {
            return PriceCalculator.calculate(input(d, ctx), ctx.fx(), ctx.rules(), ctx.now(), false).totalAmountKrw();
        } catch (RateUnavailableException e) {
            return null;
        }
    }

    /** 출발국은 OfferData 를 만들 때 정해 둔 값을 그대로 쓴다 */
    private static PriceInput input(OfferData d, Context ctx) {
        return new PriceInput(d.price(), d.currency(), d.shippingFee(), d.mallId(), d.origin(),
                ctx.countryOf(d.mallId()), d.categoryId());
    }
}
