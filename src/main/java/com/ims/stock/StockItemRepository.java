package com.ims.stock;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockItemRepository extends JpaRepository<StockItem, UUID>, JpaSpecificationExecutor<StockItem> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItem s where s.id = :id")
    Optional<StockItem> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItem s where s.id in :ids order by s.id asc")
    List<StockItem> findAllByIdInForUpdateOrderByIdAsc(@Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItem s where s.partId = :partId and s.status in ('OK', 'ATTENTION') and s.building = false and s.customerId is null and s.installedInId is null and s.quantity > 0 order by s.createdAt asc, s.id asc")
    List<StockItem> findAvailableFifoByPartIdForUpdate(@Param("partId") UUID partId);

    boolean existsByLocationId(UUID locationId);

    long countByLocationId(UUID locationId);

    boolean existsByPartIdAndSerialNumber(UUID partId, String serialNumber);

    Optional<StockItem> findByPartIdAndSerialNumber(UUID partId, String serialNumber);

    Optional<StockItem> findByCustomBarcode(String customBarcode);

    List<StockItem> findByPartIdOrderByCreatedAtDesc(UUID partId);

    List<StockItem> findByParentItemIdOrderByCreatedAtAsc(UUID parentItemId);

    List<StockItem> findByInstalledInIdOrderByCreatedAtAsc(UUID installedInId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StockItem s set s.locationId = :newLocationId where s.locationId = :oldLocationId")
    int reassignLocation(@Param("oldLocationId") UUID oldLocationId, @Param("newLocationId") UUID newLocationId);

    @Query("select coalesce(sum(s.quantity), 0) from StockItem s where s.partId = :partId and s.status in ('OK', 'ATTENTION') and s.building = false and s.customerId is null and s.installedInId is null")
    BigDecimal computeActualInStockForPart(@Param("partId") UUID partId);

    @Query("select coalesce(sum(s.quantity), 0) from StockItem s where s.partId = :partId and s.building = true")
    BigDecimal computeActualInProductionOutputsForPart(@Param("partId") UUID partId);
}
