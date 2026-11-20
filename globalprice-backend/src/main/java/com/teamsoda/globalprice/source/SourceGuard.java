package com.teamsoda.globalprice.source;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.config.GlobalPriceProperties;

/**
 * 소스별 호출 정책 (명세서 4-5): 하루 호출 상한(메모리 카운터), 429 이후 일정 시간 호출 중단.
 */
@Component
public class SourceGuard {

    public enum Decision { ALLOW, BUDGET_EXCEEDED, COOLING_DOWN, DISABLED }

    private record Counter(LocalDate day, AtomicInteger count) {
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();
    private final Clock clock;
    private final GlobalPriceProperties props;

    public SourceGuard(Clock clock, GlobalPriceProperties props) {
        this.clock = clock;
        this.props = props;
    }

    /** 호출 1회를 예약한다. ALLOW 일 때만 카운터가 올라간다 */
    public Decision acquire(MallSource source) {
        if (!source.enabled()) return Decision.DISABLED;
        Instant until = cooldownUntil.get(source.sourceName());
        if (until != null && clock.instant().isBefore(until)) return Decision.COOLING_DOWN;
        LocalDate today = LocalDate.now(clock);
        Counter c = counters.compute(source.sourceName(),
                (k, old) -> old == null || !old.day().equals(today) ? new Counter(today, new AtomicInteger()) : old);
        if (c.count().incrementAndGet() > source.dailyBudget()) {
            c.count().decrementAndGet();
            return Decision.BUDGET_EXCEEDED;
        }
        return Decision.ALLOW;
    }

    public void onRateLimited(MallSource source) {
        cooldownUntil.put(source.sourceName(), clock.instant().plus(props.search().rateLimitCooldown()));
    }

    public int usedToday(String sourceName) {
        Counter c = counters.get(sourceName);
        return c == null || !c.day().equals(LocalDate.now(clock)) ? 0 : c.count().get();
    }
}
