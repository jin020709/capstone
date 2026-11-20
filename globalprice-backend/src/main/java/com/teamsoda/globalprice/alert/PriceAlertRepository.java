package com.teamsoda.globalprice.alert;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

    List<PriceAlert> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<PriceAlert> findByIdAndUserId(Long id, Long userId);

    @Query("""
            select a from PriceAlert a where a.userId = :userId and a.basis = :basis
            and ((:productId is null and a.productId is null) or a.productId = :productId)
            and ((:eventId is null and a.storeEventId is null) or a.storeEventId = :eventId)""")
    Optional<PriceAlert> findSame(@Param("userId") Long userId, @Param("basis") PriceAlert.Basis basis,
                                  @Param("productId") Long productId, @Param("eventId") Long eventId);

    List<PriceAlert> findByActiveTrueAndBasisIn(Collection<PriceAlert.Basis> bases);

    List<PriceAlert> findByActiveTrueAndBasisInAndProductIdIn(Collection<PriceAlert.Basis> bases,
                                                              Collection<Long> productIds);
}
