package com.teamsoda.globalprice.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamsoda.globalprice.catalog.OfferPricer;
import com.teamsoda.globalprice.catalog.ViewFactory;
import com.teamsoda.globalprice.catalog.Views.EventView;
import com.teamsoda.globalprice.catalog.Views.ProductSummary;
import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;
import com.teamsoda.globalprice.pricing.PricingModels.FxRate;
import com.teamsoda.globalprice.pricing.PricingModels.FxTable;
import com.teamsoda.globalprice.pricing.RuleRepository;
import com.teamsoda.globalprice.rate.ExchangeRateService;

/** 환율 (6-5) · 홈 (6-6) */
@RestController
@RequestMapping("/api")
public class InfoController {

    /**
     * BACKEND_SPEC 6-5 (base · source · baseDate · updatedAt · rates 맵 + items 배열).
     * rates 는 계산에 쓰는 값 (전신환매도율, 없으면 매매기준율), 1 단위 기준.
     */
    public record ExchangeRatesResponse(String base, String source, LocalDate baseDate, LocalDateTime updatedAt,
                                        Map<String, BigDecimal> rates, List<RateItem> items) {
    }

    public record RateItem(String currencyCode, BigDecimal rateToKrw, BigDecimal ttsRate, LocalDate baseDate,
                           LocalDateTime recordedAt) {
    }

    public record HomeResponse(List<String> categories, List<String> hotKeywords, List<EventView> events,
                               List<EventView> monthlyEvents, List<ProductSummary> popular) {
    }

    private final ExchangeRateService rates;
    private final RuleRepository rules;
    private final OfferPricer pricer;
    private final ViewFactory views;
    private final GlobalPriceProperties props;

    public InfoController(ExchangeRateService rates, RuleRepository rules, OfferPricer pricer, ViewFactory views,
                          GlobalPriceProperties props) {
        this.rates = rates;
        this.rules = rules;
        this.pricer = pricer;
        this.views = views;
        this.props = props;
    }

    @GetMapping("/exchange-rates")
    public ExchangeRatesResponse exchangeRates() {
        FxTable fx = rates.currentTable();
        if (fx.rates().isEmpty()) throw new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE, "환율 정보를 아직 불러오지 못했어요.");
        var v = views.rates(fx);
        List<RateItem> items = fx.rates().values().stream()
                .map((FxRate r) -> new RateItem(r.currencyCode(), r.rateToKrw(), r.ttsRate(), r.baseDate(),
                        r.recordedAt() == null ? null : r.recordedAt().truncatedTo(ChronoUnit.SECONDS)))
                .toList();
        return new ExchangeRatesResponse(v.base(), v.source(), v.baseDate(), v.updatedAt(), v.rates(), items);
    }

    /** 이번 달과 기간이 겹치는 활성 행사. 인기 상품은 1차 제외(빈 배열) */
    @GetMapping("/home")
    public HomeResponse home() {
        OfferPricer.Context ctx = pricer.context();
        LocalDateTime monthStart = ctx.now().toLocalDate().withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = monthStart.plusMonths(1).minusSeconds(1);
        List<EventView> events = rules.activeEventsOverlapping(monthStart, monthEnd).stream()
                .map(e -> views.event(e, ctx.malls())).toList();
        return new HomeResponse(rules.categoryNames(), props.home().hotKeywords(), events, events, List.of());
    }
}
