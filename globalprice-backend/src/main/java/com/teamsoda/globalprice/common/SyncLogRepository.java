package com.teamsoda.globalprice.common;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * api_sync_logs 기록. 스케줄러 회차와 검색 중 실패 건만 남긴다 (성공한 검색까지 쓰면 행이 폭증).
 * api_sources 에 해당 source_name 이 없으면 경고만 남기고 넘어간다.
 */
@Repository
public class SyncLogRepository {

    private static final Logger log = LoggerFactory.getLogger(SyncLogRepository.class);

    public enum Status { SUCCESS, PARTIAL, FAILED }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SyncLogRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String sourceName, String syncType, Status status, Integer httpStatus,
                       int fetched, int skipped, String errorMessage, LocalDateTime startedAt) {
        try {
            List<Long> ids = jdbc.queryForList(
                    "SELECT api_source_id FROM api_sources WHERE source_name = ?", Long.class, sourceName);
            if (ids.isEmpty()) {
                log.warn("api_sources 에 {} 가 없어 동기화 로그를 남기지 못함", sourceName);
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            jdbc.update("""
                    INSERT INTO api_sync_logs (api_source_id, sync_type, status, http_status, records_fetched,
                                               records_skipped, error_message, started_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    ids.getFirst(), syncType, status.name(), httpStatus, fetched, skipped,
                    truncate(errorMessage), startedAt == null ? now : startedAt, now);
        } catch (RuntimeException e) {
            log.warn("동기화 로그 기록 실패: {}", e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
