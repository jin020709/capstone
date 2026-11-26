package com.teamsoda.globalprice.support;

import java.nio.file.Path;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 통합 테스트 공통 설정 (@SpringBootTest + MySQL 8).
 * <ul>
 *   <li>환경변수 GP_TEST_DB_URL 이 있으면 그 DB 사용 (GlobalPrice_DB 의 01 ~ 08 스크립트를 적용한 globalprice DB.
 *       테스트가 데이터를 쓰므로 개발 DB 말고 테스트 전용 DB 를 지정할 것)</li>
 *   <li>없으면 Testcontainers 로 mysql:8.0 을 띄우고 GlobalPrice_DB 스크립트를 순서대로 적용</li>
 *   <li>둘 다 안 되면 (Docker 꺼짐) 테스트를 건너뜀</li>
 * </ul>
 * CHECK 제약 · 함수형 UNIQUE 가 있어서 H2 로는 테스트할 수 없다.
 * 외부 쇼핑몰은 모두 가짜 서버({@link #EXTERNAL})로 보낸다. Shopify 는 끔.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(IntegrationTestBase.FixedRates.class)
@EnabledIf("com.teamsoda.globalprice.support.IntegrationTestBase#databaseAvailable")
public abstract class IntegrationTestBase {

    public static final MockHttp EXTERNAL = new MockHttp();

    /**
     * 환율 고정: 손계산 기대값이 개발 DB 의 실제 환율(매일 바뀜)에 흔들리지 않도록
     * 03_seed_data 와 같은 USD 1,400 · JPY 9.5 (tts 없음) 를 쓴다.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class FixedRates {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.teamsoda.globalprice.rate.ExchangeRateService fixedExchangeRates(
                com.teamsoda.globalprice.rate.KoreaEximClient client,
                com.teamsoda.globalprice.rate.ExchangeRateRepository repository,
                com.teamsoda.globalprice.common.SyncLogRepository syncLog, java.time.Clock clock) {
            java.time.LocalDate base = java.time.LocalDate.of(2026, 11, 10);
            java.time.LocalDateTime at = base.atTime(22, 26, 4);
            var table = new com.teamsoda.globalprice.pricing.PricingModels.FxTable(java.util.Map.of(
                    "USD", new com.teamsoda.globalprice.pricing.PricingModels.FxRate(1L, "USD",
                            new java.math.BigDecimal("1400"), null, base, at),
                    "JPY", new com.teamsoda.globalprice.pricing.PricingModels.FxRate(2L, "JPY",
                            new java.math.BigDecimal("9.5"), null, base, at)));
            return new com.teamsoda.globalprice.rate.ExchangeRateService(client, repository, syncLog, clock) {
                @Override
                public com.teamsoda.globalprice.pricing.PricingModels.FxTable currentTable() {
                    return table;
                }
            };
        }
    }
    private static final String EXTERNAL_DB = System.getenv("GP_TEST_DB_URL");
    private static MySQLContainer mysql;

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected CacheManager cacheManager;

    private static Boolean available;

    /** 외부 DB 가 지정됐거나 Docker 데몬에 실제로 연결될 때만 true (한 번만 확인) */
    public static synchronized boolean databaseAvailable() {
        if (available != null) return available;
        if (EXTERNAL_DB != null && !EXTERNAL_DB.isBlank()) return available = true;
        try {
            DockerClientFactory.instance().client().pingCmd().exec();
            available = true;
        } catch (Throwable e) {
            available = false;
        }
        return available;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        if (EXTERNAL_DB != null && !EXTERNAL_DB.isBlank()) {
            r.add("spring.datasource.url", () -> EXTERNAL_DB);
            r.add("spring.datasource.username", () -> envOr("GP_TEST_DB_USER", "root"));
            r.add("spring.datasource.password", () -> envOr("GP_TEST_DB_PASSWORD", ""));
        } else {
            MySQLContainer c = container();
            r.add("spring.datasource.url", () -> c.getJdbcUrl() + "?serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
            r.add("spring.datasource.username", c::getUsername);
            r.add("spring.datasource.password", c::getPassword);
        }
        String base = EXTERNAL.baseUrl();
        r.add("globalprice.sources.shopify.enabled", () -> "false");
        r.add("globalprice.sources.aliexpress.enabled", () -> "false");
        r.add("globalprice.sources.etsy.enabled", () -> "false");
        r.add("globalprice.sources.ebay.enabled", () -> "true");
        r.add("globalprice.sources.ebay.client-id", () -> "test-id");
        r.add("globalprice.sources.ebay.client-secret", () -> "test-secret");
        r.add("globalprice.sources.ebay.base-url", () -> base);
        r.add("globalprice.sources.ebay.token-url", () -> base + "/identity/v1/oauth2/token");
        r.add("globalprice.sources.koreaexim.auth-key", () -> "");
        r.add("globalprice.sources.koreaexim.url", () -> base + "/exchangeJSON");
        r.add("globalprice.device-catalog.enabled", () -> "false");   // 테스트 중 Google 기기 목록을 받지 않음
    }

    private static synchronized MySQLContainer container() {
        if (mysql == null) {
            Path db = Path.of("..", "GlobalPrice_DB", "GlobalPrice_DB").toAbsolutePath().normalize();
            mysql = new MySQLContainer("mysql:8.0")
                    .withDatabaseName("globalprice")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
                            "--default-time-zone=+09:00")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("01_create_database.sql")),
                            "/docker-entrypoint-initdb.d/01.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("02_create_tables.sql")),
                            "/docker-entrypoint-initdb.d/02.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("03_seed_data.sql")),
                            "/docker-entrypoint-initdb.d/03a.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("03_migration_001.sql")),
                            "/docker-entrypoint-initdb.d/03b.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("04_seed_dev.sql")),
                            "/docker-entrypoint-initdb.d/04.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("05_migration_002.sql")),
                            "/docker-entrypoint-initdb.d/05.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("06_migration_003.sql")),
                            "/docker-entrypoint-initdb.d/06.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("07_migration_004.sql")),
                            "/docker-entrypoint-initdb.d/07.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("08_migration_005.sql")),
                            "/docker-entrypoint-initdb.d/08.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("09_migration_006.sql")),
                            "/docker-entrypoint-initdb.d/09.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath(db.resolve("10_migration_007.sql")),
                            "/docker-entrypoint-initdb.d/10.sql");
            mysql.start();
        }
        return mysql;
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null ? def : v;
    }

    protected void clearSearchCache() {
        var cache = cacheManager.getCache("sourceSearch");
        if (cache != null) cache.clear();
    }

    protected static String unique(String prefix) {
        return prefix + System.nanoTime();
    }
}
