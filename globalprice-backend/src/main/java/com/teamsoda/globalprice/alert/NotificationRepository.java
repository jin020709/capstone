package com.teamsoda.globalprice.alert;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
            select n from Notification n
            where n.alertId in (select a.id from PriceAlert a where a.userId = :userId)
            and (:unreadOnly = false or n.readAt is null)
            order by n.sentAt desc, n.id desc""")
    Page<Notification> findForUser(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly,
                                   Pageable pageable);

    @Query("""
            select count(n) from Notification n
            where n.alertId in (select a.id from PriceAlert a where a.userId = :userId) and n.readAt is null""")
    long countUnread(@Param("userId") Long userId);

    @Query("""
            select n from Notification n
            where n.id = :id and n.alertId in (select a.id from PriceAlert a where a.userId = :userId)""")
    Optional<Notification> findForUser(@Param("id") Long id, @Param("userId") Long userId);
}
