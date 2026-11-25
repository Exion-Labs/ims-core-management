package com.ims.catalog.spi;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read port for the Part Catalog & BOM bounded context.
 * Never exposes JPA @Entity instances outside com.ims.catalog.
 */
public interface CatalogReadPort {

    record PartSummary(
            UUID id,
            String ipn,
            String name,
            String description,
            String unit,
            String revision,
            boolean active,
            boolean assembly,
            boolean component,
            boolean trackable,
            boolean purchaseable,
            boolean salable,
            boolean virtual
    ) {}

    record BomItemSummary(
            UUID id,
            UUID subPartId,
            String subPartIpn,
            String subPartName,
            String subPartUnit,
            boolean subPartAssembly,
            BigDecimal quantity,
            boolean optional,
            boolean consumable,
            String reference
    ) {}

    PartSummary requirePartSummary(UUID partId);

    Optional<PartSummary> findPartSummary(UUID partId);

    Map<UUID, PartSummary> getPartSummaries(Collection<UUID> partIds);

    List<UUID> listVariantIds(UUID templatePartId);

    List<UUID> listAllPartIds();

    long countParts();

    List<PartSummary> searchPartsByKeyword(String query, int limit);

    List<BomItemSummary> listBomItems(UUID assemblyPartId);
}
