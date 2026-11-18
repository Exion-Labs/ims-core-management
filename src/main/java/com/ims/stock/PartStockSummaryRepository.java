package com.ims.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartStockSummaryRepository extends JpaRepository<PartStockSummary, UUID> {

    Optional<PartStockSummary> findByPartId(UUID partId);

    List<PartStockSummary> findByPartIdIn(Collection<UUID> partIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PartStockSummary s
               set s.inStock = s.inStock + :inStockDelta,
                   s.allocatedToBuilds = s.allocatedToBuilds + :buildsDelta,
                   s.allocatedToSales = s.allocatedToSales + :salesDelta,
                   s.onOrder = s.onOrder + :onOrderDelta,
                   s.inProduction = s.inProduction + :inProdDelta
             where s.partId = :partId
            """)
    int applyDeltas(
            @Param("partId") UUID partId,
            @Param("inStockDelta") BigDecimal inStockDelta,
            @Param("buildsDelta") BigDecimal buildsDelta,
            @Param("salesDelta") BigDecimal salesDelta,
            @Param("onOrderDelta") BigDecimal onOrderDelta,
            @Param("inProdDelta") BigDecimal inProdDelta
    );
}
