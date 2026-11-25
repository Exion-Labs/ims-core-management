package com.ims.catalog.application;

import com.ims.catalog.domain.model.BomItem;
import com.ims.catalog.domain.model.Part;
import com.ims.catalog.infrastructure.persistence.BomItemRepository;
import com.ims.catalog.infrastructure.persistence.PartRepository;
import com.ims.catalog.spi.CatalogReadPort;
import com.ims.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CatalogQueryService implements CatalogReadPort {

    private final PartRepository partRepository;
    private final BomItemRepository bomItemRepository;

    public CatalogQueryService(PartRepository partRepository, BomItemRepository bomItemRepository) {
        this.partRepository = partRepository;
        this.bomItemRepository = bomItemRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PartSummary requirePartSummary(UUID partId) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + partId));
        return toSummary(part);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PartSummary> findPartSummary(UUID partId) {
        return partRepository.findById(partId).map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PartSummary> getPartSummaries(Collection<UUID> partIds) {
        Map<UUID, PartSummary> result = new HashMap<>();
        if (partIds == null || partIds.isEmpty()) {
            return result;
        }
        for (Part p : partRepository.findAllById(partIds)) {
            result.put(p.getId(), toSummary(p));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listVariantIds(UUID templatePartId) {
        return partRepository.findByVariantOfIdOrderByIpnAsc(templatePartId).stream()
                .map(Part::getId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listAllPartIds() {
        return partRepository.findAll().stream().map(Part::getId).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countParts() {
        return partRepository.count();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartSummary> searchPartsByKeyword(String query, int limit) {
        return partRepository.searchByKeyword(query).stream()
                .limit(limit)
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BomItemSummary> listBomItems(UUID assemblyPartId) {
        requirePartSummary(assemblyPartId);
        List<BomItem> items = bomItemRepository.findByPartIdOrderByCreatedAtAsc(assemblyPartId);
        if (items.isEmpty()) {
            return List.of();
        }
        List<UUID> subIds = items.stream().map(BomItem::getSubPartId).distinct().toList();
        Map<UUID, PartSummary> partsById = getPartSummaries(subIds);
        return items.stream().map(item -> {
            PartSummary sub = partsById.get(item.getSubPartId());
            return new BomItemSummary(
                    item.getId(),
                    item.getSubPartId(),
                    sub != null ? sub.ipn() : "",
                    sub != null ? sub.name() : "",
                    sub != null ? sub.unit() : "pcs",
                    sub != null && sub.assembly(),
                    item.getQuantity(),
                    item.isOptional(),
                    item.isConsumable(),
                    item.getReference()
            );
        }).toList();
    }

    private PartSummary toSummary(Part p) {
        return new PartSummary(
                p.getId(),
                p.getIpn(),
                p.getName(),
                p.getDescription(),
                p.getUnit(),
                p.getRevision(),
                p.isActive(),
                p.isAssembly(),
                p.isComponent(),
                p.isTrackable(),
                p.isPurchaseable(),
                p.isSalable(),
                p.isVirtual()
        );
    }
}
