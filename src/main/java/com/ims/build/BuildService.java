package com.ims.build;

import com.ims.common.audit.AuditService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.part.BomService;
import com.ims.part.Part;
import com.ims.part.PartService;
import com.ims.stock.StockItem;
import com.ims.stock.StockLocationService;
import com.ims.stock.StockService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final PartService partService;
    private final BomService bomService;
    private final StockService stockService;
    private final StockLocationService locationService;
    private final ReferenceSequenceService sequenceService;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;

    public BuildService(
            BuildOrderRepository buildOrderRepository,
            BuildLineRepository buildLineRepository,
            BuildAllocationRepository allocationRepository,
            PartService partService,
            BomService bomService,
            StockService stockService,
            StockLocationService locationService,
            ReferenceSequenceService sequenceService,
            AuditService auditService,
            OutboxWriter outboxWriter
    ) {
        this.buildOrderRepository = buildOrderRepository;
        this.buildLineRepository = buildLineRepository;
        this.allocationRepository = allocationRepository;
        this.partService = partService;
        this.bomService = bomService;
        this.stockService = stockService;
        this.locationService = locationService;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public BigDecimal getMaxBuildableQuantity(UUID partId) {
        Part part = partService.requirePart(partId);
        if (!part.isAssembly()) {
            return BigDecimal.ZERO;
        }
        List<BomService.BomItemView> bom = bomService.listBomItems(partId);
        if (bom.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<UUID> subIds = bom.stream().map(BomService.BomItemView::subPartId).toList();
        Map<UUID, StockService.StockSummaryView> summaries = stockService.getStockSummariesForParts(subIds);

        BigDecimal minBuildable = null;
        for (BomService.BomItemView item : bom) {
            if (item.optional() || item.consumable() || item.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            StockService.StockSummaryView summary = summaries.get(item.subPartId());
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId is required");
        }
        Part assembly = partService.requirePart(cmd.partId());
        if (!assembly.isAssembly()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "PART_NOT_AN_ASSEMBLY: Build orders can only be created for parts with assembly = true"
            );
        }
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build quantity must be positive");
        }
        if (cmd.destinationLocationId() != null && locationService.requireLocation(cmd.destinationLocationId()).isStructural()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Destination location cannot be structural");
        }

        BuildOrder build = new BuildOrder();
        build.setReference(sequenceService.nextReference("BO"));
        build.setTitle(cmd.title() != null && !cmd.title().isBlank() ? cmd.title().trim() : "Build " + assembly.getIpn());
        build.setPartId(assembly.getId());
        build.setQuantity(cmd.quantity());
        build.setCompleted(BigDecimal.ZERO);
        build.setParentBuildId(cmd.parentBuildId());
        build.setStatus(BuildOrder.Status.PENDING.name());
        build.setSourceLocationId(cmd.sourceLocationId());
        build.setDestinationLocationId(cmd.destinationLocationId());
        build.setTargetDate(cmd.targetDate());
        build.setNotes(cmd.notes());

        BuildOrder saved = buildOrderRepository.saveAndFlush(build);

        List<BomService.BomItemView> bom = bomService.listBomItems(assembly.getId());
        for (BomService.BomItemView item : bom) {
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Build order must be PENDING or ON_HOLD to start");
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot allocate stock to a completed or cancelled build");
        }
        if (cmd.buildLineId() == null || cmd.stockItemId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "buildLineId and stockItemId are required");
        }
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocation quantity must be positive");
        }

        BuildLine line = buildLineRepository.findByIdAndBuildOrderIdForUpdate(cmd.buildLineId(), buildId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Build line not found"));

        // Pessimistic row lock on the candidate StockItem to prevent concurrent overallocation
        StockItem stockItem = stockService.requireStockItemForUpdate(cmd.stockItemId());
        if (!stockItem.getPartId().equals(line.getSubPartId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Stock item part does not match build line component");
        }
        if (!stockItem.countsTowardInStock()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Stock item is not available in stock");
        }

        BigDecimal alreadyAllocated = allocationRepository.sumUnconsumedAllocatedQuantity(stockItem.getId());
        BigDecimal available = stockItem.getQuantity().subtract(alreadyAllocated);
        if (cmd.quantity().compareTo(available) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INSUFFICIENT_UNALLOCATED_STOCK: Requested " + cmd.quantity() + " but only " + available + " is unallocated on this stock item"
            );
        }

        if (cmd.installIntoId() != null) {
            stockService.requireStockItem(cmd.installIntoId());
        }

        BuildAllocation allocation = new BuildAllocation();
        allocation.setBuildOrderId(build.getId());
        allocation.setBuildLineId(line.getId());
        allocation.setStockItemId(stockItem.getId());
        allocation.setInstallIntoId(cmd.installIntoId());
        allocation.setQuantity(cmd.quantity());
        allocation.setConsumed(false);
        BuildAllocation saved = allocationRepository.saveAndFlush(allocation);

        stockService.adjustAllocatedToBuildsDelta(stockItem.getPartId(), cmd.quantity());

        if (BuildOrder.Status.PENDING.name().equals(build.getStatus())) {
            build.setStatus(BuildOrder.Status.PRODUCTION.name());
            buildOrderRepository.saveAndFlush(build);
        }

        return new BuildAllocationView(
                saved.getId(),
                saved.getBuildOrderId(),
                saved.getBuildLineId(),
                saved.getStockItemId(),
                stockItem.getSerialNumber(),
                stockItem.getBatchCode(),
                saved.getInstallIntoId(),
                saved.getQuantity(),
                saved.isConsumed()
        );
    }

    @Transactional
    public BuildOrderView autoAllocateFifo(UUID buildId) {
        BuildOrder build = requireBuildOrderForUpdate(buildId);
        List<BuildLine> lines = buildLineRepository.findByBuildOrderIdOrderByCreatedAtAsc(buildId);
        for (BuildLine line : lines) {
            BigDecimal currentlyAllocated = allocationRepository.findByBuildLineIdOrderByCreatedAtAsc(line.getId()).stream()
                    .map(BuildAllocation::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal needed = line.getQuantity().subtract(currentlyAllocated);
            if (needed.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            List<StockItem> candidates = stockService.findAvailableFifoForUpdate(line.getSubPartId());
            for (StockItem candidate : candidates) {
                if (needed.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal alreadyAllocated = allocationRepository.sumUnconsumedAllocatedQuantity(candidate.getId());
                BigDecimal avail = candidate.getQuantity().subtract(alreadyAllocated);
                if (avail.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal take = needed.min(avail);
                allocateStock(buildId, new AllocateStockCommand(line.getId(), candidate.getId(), take, null));
                needed = needed.subtract(take);
            }
        }
        return toView(requireBuildOrder(buildId));
    }

    @Transactional
    public StockService.StockItemView createBuildOutput(UUID buildId, CreateBuildOutputCommand cmd) {
        BuildOrder build = requireBuildOrderForUpdate(buildId);
        if (BuildOrder.Status.COMPLETED.name().equals(build.getStatus())
                || BuildOrder.Status.CANCELLED.name().equals(build.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot create outputs on a completed or cancelled build");
        }
        BigDecimal qty = cmd.quantity() != null ? cmd.quantity() : BigDecimal.ONE;
        UUID locId = cmd.locationId() != null ? cmd.locationId() : build.getDestinationLocationId();

        if (BuildOrder.Status.PENDING.name().equals(build.getStatus())) {
            build.setStatus(BuildOrder.Status.PRODUCTION.name());
            buildOrderRepository.saveAndFlush(build);
        }

        return stockService.createStock(new StockService.CreateStockCommand(
                build.getPartId(),
                locId,
                qty,
                cmd.serialNumber(),
                cmd.batchCode(),
                "OK",
                null,
                null,
                null,
                null,
                true, // isBuilding = true -> updates PartStockSummary.inProduction
                null,
                "In-progress build output for " + build.getReference()
        ));
    }

    @Transactional
    public BuildOrderView completeBuildOutput(UUID buildId, CompleteBuildOutputCommand cmd) {
        BuildOrder build = requireBuildOrderForUpdate(buildId);
        if (cmd.outputStockItemId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outputStockItemId is required");
        }
        StockItem outputItem = stockService.requireStockItemForUpdate(cmd.outputStockItemId());
        if (!outputItem.getPartId().equals(build.getPartId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Output stock item belongs to a different part");
        }

        UUID destLoc = cmd.destinationLocationId() != null ? cmd.destinationLocationId() : build.getDestinationLocationId();
        stockService.completeBuildOutputItem(outputItem.getId(), destLoc, "Completed build " + build.getReference());

        // Consume allocations targeted to this output (or all unconsumed allocations if none specifically targeted)
        List<BuildAllocation> allAllocations = allocationRepository.findByBuildOrderIdOrderByCreatedAtAsc(buildId);
        List<BuildAllocation> toConsume = allAllocations.stream()
                .filter(a -> !a.isConsumed() && (a.getInstallIntoId() == null || a.getInstallIntoId().equals(outputItem.getId())))
                .toList();

        for (BuildAllocation alloc : toConsume) {
            StockItem compStock = stockService.requireStockItemForUpdate(alloc.getStockItemId());
            if (compStock.getSerialNumber() != null) {
                stockService.installStockItemIntoAssembly(
                        compStock.getId(),
                        outputItem.getId(),
                        "Installed into " + build.getReference() + " (" + outputItem.getId() + ")"
                );
            } else {
                stockService.removeStock(compStock.getId(), new StockService.StockQuantityOperationCommand(
                        alloc.getQuantity(),
                        "Consumed in build " + build.getReference()
                ));
            }
            stockService.adjustAllocatedToBuildsDelta(compStock.getPartId(), alloc.getQuantity().negate());
            alloc.setConsumed(true);
            allocationRepository.save(alloc);

            buildLineRepository.findByIdAndBuildOrderIdForUpdate(alloc.getBuildLineId(), buildId).ifPresent(line -> {
                line.setConsumed(line.getConsumed().add(alloc.getQuantity()));
                buildLineRepository.save(line);
            });
        }

        build.setCompleted(build.getCompleted().add(outputItem.getQuantity()));
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Build order is already " + oldStatus);
        }

        for (BuildAllocation alloc : allocationRepository.findByBuildOrderIdOrderByCreatedAtAsc(buildId)) {
            if (!alloc.isConsumed()) {
                StockItem comp = stockService.requireStockItem(alloc.getStockItemId());
                stockService.adjustAllocatedToBuildsDelta(comp.getPartId(), alloc.getQuantity().negate());
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Build order not found: " + id));
    }

    private BuildOrder requireBuildOrderForUpdate(UUID id) {
        return buildOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Build order not found: " + id));
    }

    private BuildOrderView toView(BuildOrder b) {
        Part part = partService.requirePart(b.getPartId());
        String destPath = b.getDestinationLocationId() != null
                ? locationService.getLocation(b.getDestinationLocationId()).pathString()
                : null;
        List<BuildLine> lines = buildLineRepository.findByBuildOrderIdOrderByCreatedAtAsc(b.getId());
        List<BuildLineView> lineViews = new ArrayList<>();
        for (BuildLine l : lines) {
            Part sub = partService.requirePart(l.getSubPartId());
            List<BuildAllocation> allocs = allocationRepository.findByBuildLineIdOrderByCreatedAtAsc(l.getId());
            BigDecimal allocSum = allocs.stream().map(BuildAllocation::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<BuildAllocationView> allocViews = allocs.stream().map(a -> {
                StockItem si = stockService.requireStockItem(a.getStockItemId());
                return new BuildAllocationView(
                        a.getId(), a.getBuildOrderId(), a.getBuildLineId(), a.getStockItemId(),
                        si.getSerialNumber(), si.getBatchCode(), a.getInstallIntoId(), a.getQuantity(), a.isConsumed()
                );
            }).toList();
            lineViews.add(new BuildLineView(
                    l.getId(), l.getBuildOrderId(), l.getBomItemId(), sub.getId(), sub.getIpn(),
                    sub.getName(), sub.getUnit(), sub.isTrackable(), l.getQuantity(), allocSum,
                    l.getConsumed(), l.getReference(), allocViews
            ));
        }

        return new BuildOrderView(
                b.getId(),
                b.getReference(),
                b.getTitle(),
                part.getId(),
                part.getIpn(),
                part.getName(),
                part.getUnit(),
                part.isTrackable(),
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
