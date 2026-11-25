package com.ims.manufacturing.application;

import com.ims.catalog.spi.CatalogReadPort;
import com.ims.common.audit.AuditService;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.manufacturing.domain.model.BuildAllocation;
import com.ims.manufacturing.domain.model.BuildLine;
import com.ims.manufacturing.domain.model.BuildOrder;
import com.ims.manufacturing.infrastructure.persistence.BuildAllocationRepository;
import com.ims.manufacturing.infrastructure.persistence.BuildLineRepository;
import com.ims.manufacturing.infrastructure.persistence.BuildOrderRepository;
import com.ims.shared.exception.BusinessRuleException;
import com.ims.shared.exception.ConflictException;
import com.ims.shared.exception.ResourceNotFoundException;
import com.ims.shared.exception.ValidationException;
import com.ims.stock.spi.StockOperationsPort;
import com.ims.stock.spi.StockReadPort;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiresFeature(Feature.MANUFACTURING)
public class BuildService {

    public record BuildAllocationView(
            UUID id,
            UUID buildOrderId,
            UUID buildLineId,
            UUID stockItemId,
            String serialNumber,
            String batchCode,
            UUID installIntoId,
            BigDecimal quantity,
            boolean consumed
    ) {}

    public record BuildLineView(
            UUID id,
            UUID buildOrderId,
            UUID bomItemId,
            UUID subPartId,
            String subPartIpn,
            String subPartName,
            String subPartUnit,
            boolean subPartTrackable,
            BigDecimal quantity,
            BigDecimal allocated,
            BigDecimal consumed,
            String reference,
            List<BuildAllocationView> allocations
    ) {}

    public record BuildOrderView(
            UUID id,
            String reference,
            String title,
            UUID partId,
            String partIpn,
            String partName,
            String partUnit,
            boolean partTrackable,
            BigDecimal quantity,
            BigDecimal completed,
            UUID parentBuildId,
            String status,
            UUID sourceLocationId,
            UUID destinationLocationId,
            String destinationLocationPath,
            LocalDate targetDate,
            LocalDate completionDate,
            String notes,
            List<BuildLineView> lines,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateBuildOrderCommand(
            UUID partId,
            String title,
            BigDecimal quantity,
            UUID parentBuildId,
            UUID sourceLocationId,
            UUID destinationLocationId,
            LocalDate targetDate,
            String notes,
            Boolean createChildBuilds
    ) {}

    public record AllocateStockCommand(
            UUID buildLineId,
            UUID stockItemId,
            BigDecimal quantity,
            UUID installIntoId
    ) {}

    public record CreateBuildOutputCommand(
            BigDecimal quantity,
            String serialNumber,
            UUID locationId,
            String batchCode
    ) {}

    public record CompleteBuildOutputCommand(
            UUID outputStockItemId,
            UUID destinationLocationId
    ) {}

    private final BuildOrderRepository buildOrderRepository;
    private final BuildLineRepository buildLineRepository;
    private final BuildAllocationRepository allocationRepository;
    private final CatalogReadPort catalogReadPort;
    private final StockReadPort stockReadPort;
    private final StockOperationsPort stockOperationsPort;
    private final ReferenceSequenceService sequenceService;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;

    public BuildService(
            BuildOrderRepository buildOrderRepository,
            BuildLineRepository buildLineRepository,
            BuildAllocationRepository allocationRepository,
            CatalogReadPort catalogReadPort,
            StockReadPort stockReadPort,
            StockOperationsPort stockOperationsPort,
            ReferenceSequenceService sequenceService,
            AuditService auditService,
            OutboxWriter outboxWriter
    ) {
        this.buildOrderRepository = buildOrderRepository;
        this.buildLineRepository = buildLineRepository;
        this.allocationRepository = allocationRepository;
        this.catalogReadPort = catalogReadPort;
        this.stockReadPort = stockReadPort;
        this.stockOperationsPort = stockOperationsPort;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public BigDecimal getMaxBuildableQuantity(UUID partId) {
        CatalogReadPort.PartSummary part = catalogReadPort.requirePartSummary(partId);
        if (!part.assembly()) {
            return BigDecimal.ZERO;
        }
        List<CatalogReadPort.BomItemSummary> bom = catalogReadPort.listBomItems(partId);
        if (bom.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<UUID> subIds = bom.stream().map(CatalogReadPort.BomItemSummary::subPartId).toList();
        Map<UUID, StockReadPort.StockSummaryView> summaries = stockReadPort.getStockSummariesForParts(subIds);

        BigDecimal minBuildable = null;
        for (CatalogReadPort.BomItemSummary item : bom) {
            if (item.optional() || item.consumable() || item.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            StockReadPort.StockSummaryView summary = summaries.get(item.subPartId());
            BigDecimal avail = summary != null ? summary.availableStock() : BigDecimal.ZERO;
            BigDecimal possible = avail.divide(item.quantity(), 0, RoundingMode.FLOOR);
            if (minBuildable == null || possible.compareTo(minBuildable) < 0) {
                minBuildable = possible;
            }
        }
        return minBuildable != null ? minBuildable : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public Page<BuildOrderView> searchBuildOrders(String q, String status, UUID partId, Pageable pageable) {
        Specification<BuildOrder> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pat = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("reference")), pat),
                        cb.like(cb.lower(cb.coalesce(root.get("title"), "")), pat)
                ));
            }
            if (status != null && !status.isBlank()) {
                preds.add(cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (partId != null) {
                preds.add(cb.equal(root.get("partId"), partId));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
        return buildOrderRepository.findAll(spec, pageable).map(this::toView);
    }

    @Transactional(readOnly = true)
    public BuildOrderView getBuildOrder(UUID id) {
        return toView(requireBuildOrder(id));
    }

    @Transactional
    public BuildOrderView createBuildOrder(CreateBuildOrderCommand cmd) {
        if (cmd.partId() == null) {
            throw new ValidationException("partId is required");
        }
        CatalogReadPort.PartSummary assembly = catalogReadPort.requirePartSummary(cmd.partId());
        if (!assembly.assembly()) {
            throw new BusinessRuleException("PART_NOT_AN_ASSEMBLY: Build orders can only be created for parts with assembly = true");
        }
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Build quantity must be positive");
        }
        if (cmd.destinationLocationId() != null && stockReadPort.requireLocation(cmd.destinationLocationId()).structural()) {
            throw new BusinessRuleException("Destination location cannot be structural");
        }

        BuildOrder build = new BuildOrder();
        build.setReference(sequenceService.nextReference("BO"));
        build.setTitle(cmd.title() != null && !cmd.title().isBlank() ? cmd.title().trim() : "Build " + assembly.ipn());
        build.setPartId(assembly.id());
        build.setQuantity(cmd.quantity());
        build.setCompleted(BigDecimal.ZERO);
        build.setParentBuildId(cmd.parentBuildId());
        build.setStatus(BuildOrder.Status.PENDING.name());
        build.setSourceLocationId(cmd.sourceLocationId());
        build.setDestinationLocationId(cmd.destinationLocationId());
        build.setTargetDate(cmd.targetDate());
        build.setNotes(cmd.notes());

        BuildOrder saved = buildOrderRepository.saveAndFlush(build);

        List<CatalogReadPort.BomItemSummary> bom = catalogReadPort.listBomItems(assembly.id());
        for (CatalogReadPort.BomItemSummary item : bom) {
            if (item.consumable()) {
                continue;
            }
            BuildLine line = new BuildLine();
            line.setBuildOrderId(saved.getId());
            line.setBomItemId(item.id());
            line.setSubPartId(item.subPartId());
            line.setQuantity(item.quantity().multiply(cmd.quantity()));
            line.setConsumed(BigDecimal.ZERO);
            line.setReference(item.reference());
            buildLineRepository.save(line);

            if (Boolean.TRUE.equals(cmd.createChildBuilds()) && item.subPartAssembly()) {
                createBuildOrder(new CreateBuildOrderCommand(
                        item.subPartId(),
                        "Sub-build for " + saved.getReference(),
                        item.quantity().multiply(cmd.quantity()),
                        saved.getId(),
                        cmd.sourceLocationId(),
                        cmd.destinationLocationId(),
                        cmd.targetDate(),
                        null,
                        false
                ));
            }
        }
        buildLineRepository.flush();
        return toView(saved);
    }

    @Transactional
    public BuildOrderView startBuild(UUID buildId) {
        BuildOrder build = requireBuildOrderForUpdate(buildId);
        if (!BuildOrder.Status.PENDING.name().equals(build.getStatus())
                && !BuildOrder.Status.ON_HOLD.name().equals(build.getStatus())) {
            throw new ConflictException("Build order must be PENDING or ON_HOLD to start");
        }
        String oldStatus = build.getStatus();
        build.setStatus(BuildOrder.Status.PRODUCTION.name());
        BuildOrder saved = buildOrderRepository.saveAndFlush(build);
        auditService.record("BuildOrder", saved.getId(), "STATUS_CHANGE",
                Map.of("status", Map.of("from", oldStatus, "to", saved.getStatus())), null);
        return toView(saved);
    }

    @Transactional
    public BuildAllocationView allocateStock(UUID buildId, AllocateStockCommand cmd) {
        BuildOrder build = requireBuildOrderForUpdate(buildId);
        if (BuildOrder.Status.COMPLETED.name().equals(build.getStatus())
                || BuildOrder.Status.CANCELLED.name().equals(build.getStatus())) {
            throw new ConflictException("Cannot allocate stock to a completed or cancelled build");
        }
        if (cmd.buildLineId() == null || cmd.stockItemId() == null) {
            throw new ValidationException("buildLineId and stockItemId are required");
        }
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Allocation quantity must be positive");
        }

        BuildLine line = buildLineRepository.findByIdAndBuildOrderIdForUpdate(cmd.buildLineId(), buildId)
                .orElseThrow(() -> new ResourceNotFoundException("Build line not found"));

        StockReadPort.StockItemSnapshot stockItem = stockOperationsPort.lockStockItemForUpdate(cmd.stockItemId());
        if (!stockItem.partId().equals(line.getSubPartId())) {
            throw new BusinessRuleException("Stock item part does not match build line component");
        }
        if (!stockItem.countsTowardInStock()) {
            throw new BusinessRuleException("Stock item is not available in stock");
        }

        BigDecimal alreadyAllocated = allocationRepository.sumUnconsumedAllocatedQuantity(stockItem.id());
        BigDecimal available = stockItem.quantity().subtract(alreadyAllocated);
        if (cmd.quantity().compareTo(available) > 0) {
            throw new BusinessRuleException("INSUFFICIENT_UNALLOCATED_STOCK: Requested " + cmd.quantity() + " but only " + available + " is unallocated on this stock item");
        }

        if (cmd.installIntoId() != null) {
            stockReadPort.requireStockItemSnapshot(cmd.installIntoId());
        }

        BuildAllocation allocation = new BuildAllocation();
        allocation.setBuildOrderId(build.getId());
        allocation.setBuildLineId(line.getId());
        allocation.setStockItemId(stockItem.id());
        allocation.setInstallIntoId(cmd.installIntoId());
        allocation.setQuantity(cmd.quantity());
        allocation.setConsumed(false);
        BuildAllocation saved = allocationRepository.saveAndFlush(allocation);

        stockOperationsPort.adjustAllocatedToBuildsDelta(stockItem.partId(), cmd.quantity());

        if (BuildOrder.Status.PENDING.name().equals(build.getStatus())) {
            build.setStatus(BuildOrder.Status.PRODUCTION.name());
            buildOrderRepository.saveAndFlush(build);
        }

        return new BuildAllocationView(
                saved.getId(),
                saved.getBuildOrderId(),
                saved.getBuildLineId(),
                saved.getStockItemId(),
                stockItem.serialNumber(),
                stockItem.batchCode(),
                saved.getInstallIntoId(),
                saved.getQuantity(),
                saved.isConsumed()
        );
    }

    @Transactional
    public BuildOrderView autoAllocateFifo(UUID buildId) {
        requireBuildOrderForUpdate(buildId);
        List<BuildLine> lines = buildLineRepository.findByBuildOrderIdOrderByCreatedAtAsc(buildId);
        for (BuildLine line : lines) {
            BigDecimal currentlyAllocated = allocationRepository.findByBuildLineIdOrderByCreatedAtAsc(line.getId()).stream()
                    .map(BuildAllocation::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal needed = line.getQuantity().subtract(currentlyAllocated);
            if (needed.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            List<StockReadPort.StockItemSnapshot> candidates = stockOperationsPort.lockAvailableFifoForUpdate(line.getSubPartId());
            for (StockReadPort.StockItemSnapshot candidate : candidates) {
                if (needed.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal alreadyAllocated = allocationRepository.sumUnconsumedAllocatedQuantity(candidate.id());
                BigDecimal avail = candidate.quantity().subtract(alreadyAllocated);
                if (avail.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal take = needed.min(avail);
                allocateStock(buildId, new AllocateStockCommand(line.getId(), candidate.id(), take, null));
                needed = needed.subtract(take);
            }
        }
        return toView(requireBuildOrder(buildId));
    }

    @Transactional
    public StockReadPort.StockItemView createBuildOutput(UUID buildId, CreateBuildOutputCommand cmd) {
        BuildOrder build = requireBuildOrderForUpdate(buildId);
        if (BuildOrder.Status.COMPLETED.name().equals(build.getStatus())
                || BuildOrder.Status.CANCELLED.name().equals(build.getStatus())) {
            throw new ConflictException("Cannot create outputs on a completed or cancelled build");
        }
        BigDecimal qty = cmd.quantity() != null ? cmd.quantity() : BigDecimal.ONE;
        UUID locId = cmd.locationId() != null ? cmd.locationId() : build.getDestinationLocationId();

        if (BuildOrder.Status.PENDING.name().equals(build.getStatus())) {
            build.setStatus(BuildOrder.Status.PRODUCTION.name());
            buildOrderRepository.saveAndFlush(build);
        }

        return stockOperationsPort.createStock(new StockOperationsPort.CreateStockSpec(
                build.getPartId(), locId, qty, cmd.serialNumber(), cmd.batchCode(), "OK",
                null, null, null, null, true, null,
                "In-progress build output for " + build.getReference()
        ));
    }

    @Transactional
    public BuildOrderView completeBuildOutput(UUID buildId, CompleteBuildOutputCommand cmd) {
        BuildOrder build = requireBuildOrderForUpdate(buildId);
        if (cmd.outputStockItemId() == null) {
            throw new ValidationException("outputStockItemId is required");
        }
        StockReadPort.StockItemSnapshot outputItem = stockOperationsPort.lockStockItemForUpdate(cmd.outputStockItemId());
        if (!outputItem.partId().equals(build.getPartId())) {
            throw new BusinessRuleException("Output stock item belongs to a different part");
        }

        UUID destLoc = cmd.destinationLocationId() != null ? cmd.destinationLocationId() : build.getDestinationLocationId();
        stockOperationsPort.completeBuildOutputItem(outputItem.id(), destLoc, "Completed build " + build.getReference());

        List<BuildAllocation> allAllocations = allocationRepository.findByBuildOrderIdOrderByCreatedAtAsc(buildId);
        List<BuildAllocation> toConsume = allAllocations.stream()
                .filter(a -> !a.isConsumed() && (a.getInstallIntoId() == null || a.getInstallIntoId().equals(outputItem.id())))
                .toList();

        for (BuildAllocation alloc : toConsume) {
            StockReadPort.StockItemSnapshot compStock = stockOperationsPort.lockStockItemForUpdate(alloc.getStockItemId());
            if (compStock.serialNumber() != null) {
                stockOperationsPort.installStockItemIntoAssembly(
                        compStock.id(),
                        outputItem.id(),
                        "Installed into " + build.getReference() + " (" + outputItem.id() + ")"
                );
            } else {
                stockOperationsPort.removeStock(
                        compStock.id(),
                        alloc.getQuantity(),
                        "Consumed in build " + build.getReference()
                );
            }
            stockOperationsPort.adjustAllocatedToBuildsDelta(compStock.partId(), alloc.getQuantity().negate());
            alloc.setConsumed(true);
            allocationRepository.save(alloc);

            buildLineRepository.findByIdAndBuildOrderIdForUpdate(alloc.getBuildLineId(), buildId).ifPresent(line -> {
                line.setConsumed(line.getConsumed().add(alloc.getQuantity()));
                buildLineRepository.save(line);
            });
        }

        build.setCompleted(build.getCompleted().add(outputItem.quantity()));
        if (build.getCompleted().compareTo(build.getQuantity()) >= 0) {
            String oldStatus = build.getStatus();
            build.setStatus(BuildOrder.Status.COMPLETED.name());
            build.setCompletionDate(LocalDate.now());
            auditService.record("BuildOrder", build.getId(), "STATUS_CHANGE",
                    Map.of("status", Map.of("from", oldStatus, "to", build.getStatus())), null);
            outboxWriter.enqueue("build_order.completed", "BuildOrder", build.getId(),
                    Map.of("reference", build.getReference(), "completed", build.getCompleted()));
        }
        BuildOrder saved = buildOrderRepository.saveAndFlush(build);
        return toView(saved);
    }

    @Transactional
    public BuildOrderView cancelBuild(UUID buildId) {
        BuildOrder build = requireBuildOrderForUpdate(buildId);
        String oldStatus = build.getStatus();
        if (BuildOrder.Status.COMPLETED.name().equals(oldStatus)
                || BuildOrder.Status.CANCELLED.name().equals(oldStatus)) {
            throw new ConflictException("Build order is already " + oldStatus);
        }

        for (BuildAllocation alloc : allocationRepository.findByBuildOrderIdOrderByCreatedAtAsc(buildId)) {
            if (!alloc.isConsumed()) {
                StockReadPort.StockItemSnapshot comp = stockReadPort.requireStockItemSnapshot(alloc.getStockItemId());
                stockOperationsPort.adjustAllocatedToBuildsDelta(comp.partId(), alloc.getQuantity().negate());
                allocationRepository.delete(alloc);
            }
        }

        build.setStatus(BuildOrder.Status.CANCELLED.name());
        BuildOrder saved = buildOrderRepository.saveAndFlush(build);
        auditService.record("BuildOrder", saved.getId(), "STATUS_CHANGE",
                Map.of("status", Map.of("from", oldStatus, "to", saved.getStatus())), null);
        return toView(saved);
    }

    private BuildOrder requireBuildOrder(UUID id) {
        return buildOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Build order not found: " + id));
    }

    private BuildOrder requireBuildOrderForUpdate(UUID id) {
        return buildOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Build order not found: " + id));
    }

    private BuildOrderView toView(BuildOrder b) {
        List<BuildLine> lines = buildLineRepository.findByBuildOrderIdOrderByCreatedAtAsc(b.getId());
        Set<UUID> partIds = new HashSet<>();
        partIds.add(b.getPartId());
        for (BuildLine l : lines) {
            partIds.add(l.getSubPartId());
        }
        Map<UUID, CatalogReadPort.PartSummary> partsById = catalogReadPort.getPartSummaries(partIds);
        CatalogReadPort.PartSummary part = partsById.get(b.getPartId());

        String destPath = b.getDestinationLocationId() != null
                ? stockReadPort.requireLocation(b.getDestinationLocationId()).pathString()
                : null;

        List<BuildAllocation> allAllocs = allocationRepository.findByBuildOrderIdOrderByCreatedAtAsc(b.getId());
        Set<UUID> stockIds = new HashSet<>();
        for (BuildAllocation a : allAllocs) {
            stockIds.add(a.getStockItemId());
        }
        Map<UUID, StockReadPort.StockItemSnapshot> stockById = stockReadPort.getStockItemSnapshots(stockIds);

        List<BuildLineView> lineViews = new ArrayList<>();
        for (BuildLine l : lines) {
            CatalogReadPort.PartSummary sub = partsById.get(l.getSubPartId());
            List<BuildAllocation> allocs = allAllocs.stream()
                    .filter(a -> a.getBuildLineId().equals(l.getId()))
                    .toList();
            BigDecimal allocSum = allocs.stream().map(BuildAllocation::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<BuildAllocationView> allocViews = allocs.stream().map(a -> {
                StockReadPort.StockItemSnapshot si = stockById.get(a.getStockItemId());
                return new BuildAllocationView(
                        a.getId(), a.getBuildOrderId(), a.getBuildLineId(), a.getStockItemId(),
                        si != null ? si.serialNumber() : null,
                        si != null ? si.batchCode() : null,
                        a.getInstallIntoId(), a.getQuantity(), a.isConsumed()
                );
            }).toList();
            lineViews.add(new BuildLineView(
                    l.getId(), l.getBuildOrderId(), l.getBomItemId(), l.getSubPartId(),
                    sub != null ? sub.ipn() : "",
                    sub != null ? sub.name() : "",
                    sub != null ? sub.unit() : "pcs",
                    sub != null && sub.trackable(),
                    l.getQuantity(), allocSum,
                    l.getConsumed(), l.getReference(), allocViews
            ));
        }

        return new BuildOrderView(
                b.getId(),
                b.getReference(),
                b.getTitle(),
                b.getPartId(),
                part != null ? part.ipn() : "",
                part != null ? part.name() : "",
                part != null ? part.unit() : "pcs",
                part != null && part.trackable(),
                b.getQuantity(),
                b.getCompleted(),
                b.getParentBuildId(),
                b.getStatus(),
                b.getSourceLocationId(),
                b.getDestinationLocationId(),
                destPath,
                b.getTargetDate(),
                b.getCompletionDate(),
                b.getNotes(),
                lineViews,
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }
}
