package com.ims.stock;

import com.ims.common.audit.AuditContext;
import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.part.Part;
import com.ims.part.PartService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiresFeature(Feature.STOCK)
public class StockService {

    public record StockSummaryView(
            UUID partId,
            BigDecimal inStock,
            BigDecimal allocatedToBuilds,
            BigDecimal allocatedToSales,
            BigDecimal availableStock,
            BigDecimal onOrder,
            BigDecimal inProduction
    ) {}

    public record StockItemView(
            UUID id,
            UUID partId,
            String partIpn,
            String partName,
            String partUnit,
            boolean partTrackable,
            UUID locationId,
            String locationName,
            String locationPathString,
            UUID parentItemId,
            UUID installedInId,
            UUID customerId,
            BigDecimal quantity,
            String serialNumber,
            String batchCode,
            String status,
            LocalDate expiryDate,
            BigDecimal purchasePrice,
            String currency,
            String packaging,
            boolean building,
            String customBarcode,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record StockTrackingView(
            UUID id,
            UUID stockItemId,
            UUID partId,
            String trackingType,
            BigDecimal quantityDelta,
            BigDecimal quantityAfter,
            UUID locationId,
            String locationPathString,
            String notes,
            UUID userId,
            Instant createdAt
    ) {}

    public record PartTestTemplateView(
            UUID id,
            UUID partId,
            String testName,
            String description,
            boolean required,
            boolean requiresValue,
            boolean requiresAttachment,
            Instant createdAt
    ) {}

    public record StockItemTestResultView(
            UUID id,
            UUID stockItemId,
            UUID templateId,
            String testName,
            boolean passed,
            String value,
            String notes,
            UUID attachmentId,
            UUID testedBy,
            Instant createdAt
    ) {}

    public record CreateStockCommand(
            UUID partId,
            UUID locationId,
            BigDecimal quantity,
            String serialNumber,
            String batchCode,
            String status,
            LocalDate expiryDate,
            BigDecimal purchasePrice,
            String currency,
            String packaging,
            Boolean building,
            String customBarcode,
            String notes
    ) {}

    public record StockQuantityOperationCommand(
            BigDecimal quantity,
            String notes
    ) {}

    public record TransferStockCommand(
            UUID targetLocationId,
            BigDecimal quantity,
            String notes
    ) {}

    public record SplitStockCommand(
            BigDecimal splitQuantity,
            UUID targetLocationId,
            String batchCode,
            String notes
    ) {}

    public record MergeStockCommand(
            List<UUID> stockItemIds,
            UUID targetLocationId,
            String notes
    ) {}

    public record SerializeStockCommand(
            List<String> serialNumbers,
            UUID targetLocationId,
            String notes
    ) {}

    public record CreateTestTemplateCommand(
            String testName,
            String description,
            Boolean required,
            Boolean requiresValue,
            Boolean requiresAttachment
    ) {}

    public record RecordTestResultCommand(
            UUID templateId,
            String testName,
            Boolean passed,
            String value,
            String notes,
            UUID attachmentId
    ) {}

    public record StockSearchFilter(
            UUID partId,
            UUID locationId,
            boolean includeSublocations,
            String status,
            String batchCode,
            String serialNumber,
            String q
    ) {}

    private final StockItemRepository stockItemRepository;
    private final StockLocationRepository locationRepository;
    private final StockLocationService locationService;
    private final StockItemTrackingRepository trackingRepository;
    private final PartStockSummaryRepository summaryRepository;
    private final PartTestTemplateRepository testTemplateRepository;
    private final StockItemTestResultRepository testResultRepository;
    private final StockDeltaAccumulator deltaAccumulator;
    private final PartService partService;
    private final EntitlementService entitlementService;

    public StockService(
            StockItemRepository stockItemRepository,
            StockLocationRepository locationRepository,
            StockLocationService locationService,
            StockItemTrackingRepository trackingRepository,
            PartStockSummaryRepository summaryRepository,
            PartTestTemplateRepository testTemplateRepository,
            StockItemTestResultRepository testResultRepository,
            StockDeltaAccumulator deltaAccumulator,
            PartService partService,
            EntitlementService entitlementService
    ) {
        this.stockItemRepository = stockItemRepository;
        this.locationRepository = locationRepository;
        this.locationService = locationService;
        this.trackingRepository = trackingRepository;
        this.summaryRepository = summaryRepository;
        this.testTemplateRepository = testTemplateRepository;
        this.testResultRepository = testResultRepository;
        this.deltaAccumulator = deltaAccumulator;
        this.partService = partService;
        this.entitlementService = entitlementService;
    }

    // =========================================================================
    // Stock Search & PartStockSummary Queries
    // =========================================================================

    @Transactional(readOnly = true)
    public Page<StockItemView> searchStockItems(StockSearchFilter filter, Pageable pageable) {
        final String subtreePrefix;
        if (filter.locationId() != null && filter.includeSublocations()) {
            StockLocation loc = locationService.requireLocation(filter.locationId());
            subtreePrefix = loc.getPath();
        } else if (filter.locationId() != null) {
            locationService.requireLocation(filter.locationId());
            subtreePrefix = null;
        } else {
            subtreePrefix = null;
        }

        Specification<StockItem> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.greaterThan(root.get("quantity"), BigDecimal.ZERO));

            if (filter.partId() != null) {
                preds.add(cb.equal(root.get("partId"), filter.partId()));
            }

            if (filter.locationId() != null) {
                if (filter.includeSublocations() && subtreePrefix != null && query != null) {
                    Subquery<UUID> locSub = query.subquery(UUID.class);
                    Root<StockLocation> locRoot = locSub.from(StockLocation.class);
                    locSub.select(locRoot.get("id"))
                            .where(cb.isTrue(cb.function(
                                    "ltree_descendant",
                                    Boolean.class,
                                    locRoot.get("path"),
                                    cb.literal(subtreePrefix)
                            )));
                    preds.add(root.get("locationId").in(locSub));
                } else {
                    preds.add(cb.equal(root.get("locationId"), filter.locationId()));
                }
            }

            if (filter.status() != null && !filter.status().isBlank()) {
                preds.add(cb.equal(root.get("status"), filter.status().trim().toUpperCase(Locale.ROOT)));
            }
            if (filter.batchCode() != null && !filter.batchCode().isBlank()) {
                preds.add(cb.like(cb.lower(cb.coalesce(root.get("batchCode"), "")),
                        "%" + filter.batchCode().trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (filter.serialNumber() != null && !filter.serialNumber().isBlank()) {
                preds.add(cb.like(cb.lower(cb.coalesce(root.get("serialNumber"), "")),
                        "%" + filter.serialNumber().trim().toLowerCase(Locale.ROOT) + "%"));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                String pat = "%" + filter.q().trim().toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(cb.coalesce(root.get("batchCode"), "")), pat),
                        cb.like(cb.lower(cb.coalesce(root.get("serialNumber"), "")), pat),
                        cb.like(cb.lower(cb.coalesce(root.get("customBarcode"), "")), pat)
                ));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };

        Page<StockItem> page = stockItemRepository.findAll(spec, pageable);
        return enrichStockPage(page);
    }

    @Transactional(readOnly = true)
    public StockItemView getStockItem(UUID id) {
        StockItem item = requireStockItem(id);
        return enrichSingleStockItem(item);
    }

    @Transactional(readOnly = true)
    public StockSummaryView getPartStockSummary(UUID partId) {
        partService.requirePart(partId);
        PartStockSummary own = summaryRepository.findByPartId(partId).orElse(null);

        BigDecimal inStock = own != null ? own.getInStock() : BigDecimal.ZERO;
        BigDecimal builds = own != null ? own.getAllocatedToBuilds() : BigDecimal.ZERO;
        BigDecimal sales = own != null ? own.getAllocatedToSales() : BigDecimal.ZERO;
        BigDecimal onOrder = own != null ? own.getOnOrder() : BigDecimal.ZERO;
        BigDecimal inProd = own != null ? own.getInProduction() : BigDecimal.ZERO;

        // Roll up variant stock totals onto template parts at read time
        List<PartService.PartView> variants = partService.listVariants(partId);
        if (!variants.isEmpty()) {
            List<UUID> variantIds = variants.stream().map(PartService.PartView::id).toList();
            for (PartStockSummary vs : summaryRepository.findByPartIdIn(variantIds)) {
                inStock = inStock.add(vs.getInStock());
                builds = builds.add(vs.getAllocatedToBuilds());
                sales = sales.add(vs.getAllocatedToSales());
                onOrder = onOrder.add(vs.getOnOrder());
                inProd = inProd.add(vs.getInProduction());
            }
        }

        BigDecimal available = inStock.subtract(builds).subtract(sales).max(BigDecimal.ZERO);
        return new StockSummaryView(partId, inStock, builds, sales, available, onOrder, inProd);
    }

    @Transactional(readOnly = true)
    public Map<UUID, StockSummaryView> getStockSummariesForParts(Collection<UUID> partIds) {
        Map<UUID, StockSummaryView> result = new LinkedHashMap<>();
        if (partIds == null || partIds.isEmpty()) {
            return result;
        }
        Map<UUID, PartStockSummary> byPartId = new HashMap<>();
        for (PartStockSummary s : summaryRepository.findByPartIdIn(partIds)) {
            byPartId.put(s.getPartId(), s);
        }
        for (UUID partId : partIds) {
            PartStockSummary s = byPartId.get(partId);
            if (s != null) {
                result.put(partId, new StockSummaryView(
                        partId,
                        s.getInStock(),
                        s.getAllocatedToBuilds(),
                        s.getAllocatedToSales(),
                        s.getAvailableStock(),
                        s.getOnOrder(),
                        s.getInProduction()
                ));
            } else {
                result.put(partId, new StockSummaryView(
                        partId,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                ));
            }
        }
        return result;
    }

    // =========================================================================
    // 2.4 Atomic Stock Operations (Row-locked & Delta-batched)
    // =========================================================================

    @Transactional
    public StockItemView createStock(CreateStockCommand cmd) {
        if (cmd.partId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId is required");
        }
        Part part = partService.requirePart(cmd.partId());

        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock quantity must be positive");
        }

        // Domain Rule: StockLocation with structural = true cannot hold StockItems directly
        if (cmd.locationId() != null) {
            StockLocation location = locationService.requireLocation(cmd.locationId());
            if (location.isStructural()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "STRUCTURAL_LOCATION_FORBIDS_STOCK: Cannot store stock directly in structural location '" + location.getName() + "'"
                );
            }
        }

        String serial = cmd.serialNumber() != null && !cmd.serialNumber().isBlank() ? cmd.serialNumber().trim() : null;
        if (serial != null) {
            entitlementService.requireFeature(Feature.SERIAL_TRACKING);
            if (!part.isTrackable()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "PART_NOT_TRACKABLE: Cannot assign serial number to untracked part '" + part.getIpn() + "'"
                );
            }
            if (cmd.quantity().compareTo(BigDecimal.ONE) != 0) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "SERIALIZED_STOCK_QUANTITY_MUST_BE_ONE: Serialized stock items must have quantity == 1"
                );
            }
            if (stockItemRepository.existsByPartIdAndSerialNumber(part.getId(), serial)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Serial number '" + serial + "' already exists for part '" + part.getIpn() + "'"
                );
            }
        }

        deltaAccumulator.ensureSummaryRowExists(part.getId());

        StockItem item = new StockItem();
        item.setPartId(part.getId());
        item.setLocationId(cmd.locationId());
        item.setQuantity(cmd.quantity());
        item.setSerialNumber(serial);
        item.setBatchCode(cmd.batchCode() != null && !cmd.batchCode().isBlank() ? cmd.batchCode().trim() : null);
        item.setStatus(cmd.status() != null && !cmd.status().isBlank() ? cmd.status().trim().toUpperCase(Locale.ROOT) : StockItem.Status.OK.name());
        item.setExpiryDate(cmd.expiryDate());
        item.setPurchasePrice(cmd.purchasePrice());
        item.setCurrency(cmd.currency() != null && !cmd.currency().isBlank() ? cmd.currency().trim().toUpperCase(Locale.ROOT) : null);
        item.setPackaging(cmd.packaging());
        item.setBuilding(Boolean.TRUE.equals(cmd.building()));
        item.setCustomBarcode(cmd.customBarcode() != null && !cmd.customBarcode().isBlank() ? cmd.customBarcode().trim() : null);

        StockItem saved = stockItemRepository.saveAndFlush(item);
        recordTracking(saved, "CREATED", saved.getQuantity(), saved.getQuantity(), saved.getLocationId(), cmd.notes());

        if (saved.countsTowardInStock()) {
            deltaAccumulator.addInStockDelta(saved.getPartId(), saved.getQuantity());
        } else if (saved.isBuilding()) {
            deltaAccumulator.addInProductionDelta(saved.getPartId(), saved.getQuantity());
        }

        return enrichSingleStockItem(saved);
    }

    @Transactional
    public StockItemView countStock(UUID stockItemId, StockQuantityOperationCommand cmd) {
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Counted quantity cannot be negative");
        }
        StockItem item = requireStockItemForUpdate(stockItemId);
        if (item.getSerialNumber() != null
                && cmd.quantity().compareTo(BigDecimal.ZERO) != 0
                && cmd.quantity().compareTo(BigDecimal.ONE) != 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Serialized stock items can only be counted as 0 or 1"
            );
        }

        BigDecimal oldQty = item.getQuantity();
        BigDecimal delta = cmd.quantity().subtract(oldQty);
        item.setQuantity(cmd.quantity());
        StockItem saved = stockItemRepository.saveAndFlush(item);

        recordTracking(saved, "COUNTED", delta, saved.getQuantity(), saved.getLocationId(), cmd.notes());
        if (saved.countsTowardInStock() && delta.compareTo(BigDecimal.ZERO) != 0) {
            deltaAccumulator.addInStockDelta(saved.getPartId(), delta);
        }
        return enrichSingleStockItem(saved);
    }

    @Transactional
    public StockItemView addStock(UUID stockItemId, StockQuantityOperationCommand cmd) {
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Added quantity must be positive");
        }
        StockItem item = requireStockItemForUpdate(stockItemId);
        if (item.getSerialNumber() != null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cannot add quantity to a serialized stock item"
            );
        }

        BigDecimal newQty = item.getQuantity().add(cmd.quantity());
        item.setQuantity(newQty);
        StockItem saved = stockItemRepository.saveAndFlush(item);

        recordTracking(saved, "ADDED", cmd.quantity(), newQty, saved.getLocationId(), cmd.notes());
        if (saved.countsTowardInStock()) {
            deltaAccumulator.addInStockDelta(saved.getPartId(), cmd.quantity());
        }
        return enrichSingleStockItem(saved);
    }

    @Transactional
    public StockItemView removeStock(UUID stockItemId, StockQuantityOperationCommand cmd) {
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Removed quantity must be positive");
        }
        StockItem item = requireStockItemForUpdate(stockItemId);
        if (item.getQuantity().compareTo(cmd.quantity()) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INSUFFICIENT_STOCK_QUANTITY: Cannot remove " + cmd.quantity() + " from item with quantity " + item.getQuantity()
            );
        }

        BigDecimal newQty = item.getQuantity().subtract(cmd.quantity());
        item.setQuantity(newQty);
        StockItem saved = stockItemRepository.saveAndFlush(item);

        recordTracking(saved, "REMOVED", cmd.quantity().negate(), newQty, saved.getLocationId(), cmd.notes());
        if (saved.countsTowardInStock()) {
            deltaAccumulator.addInStockDelta(saved.getPartId(), cmd.quantity().negate());
        }
        return enrichSingleStockItem(saved);
    }

    /**
     * Transfers a stock item (or partial quantity, automatically splitting into a child item) to targetLocationId.
     */
    @Transactional
    public StockItemView transferStock(UUID stockItemId, TransferStockCommand cmd) {
        if (cmd.targetLocationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetLocationId is required");
        }
        StockLocation targetLocation = locationService.requireLocation(cmd.targetLocationId());
        if (targetLocation.isStructural()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "STRUCTURAL_LOCATION_FORBIDS_STOCK: Cannot transfer stock into structural location '" + targetLocation.getName() + "'"
            );
        }

        StockItem item = requireStockItemForUpdate(stockItemId);
        BigDecimal transferQty = cmd.quantity() != null ? cmd.quantity() : item.getQuantity();
        if (transferQty.compareTo(BigDecimal.ZERO) <= 0 || transferQty.compareTo(item.getQuantity()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Transfer quantity must be between 0 and " + item.getQuantity()
            );
        }

        if (transferQty.compareTo(item.getQuantity()) == 0) {
            item.setLocationId(targetLocation.getId());
            StockItem saved = stockItemRepository.saveAndFlush(item);
            recordTracking(saved, "TRANSFERRED", BigDecimal.ZERO, saved.getQuantity(), targetLocation.getId(), cmd.notes());
            return enrichSingleStockItem(saved);
        }

        // Auto partial split + transfer
        return splitStock(stockItemId, new SplitStockCommand(
                transferQty,
                targetLocation.getId(),
                item.getBatchCode(),
                cmd.notes() != null ? cmd.notes() : "Partial transfer split"
        ));
    }

    @Transactional
    public StockItemView splitStock(UUID stockItemId, SplitStockCommand cmd) {
        if (cmd.splitQuantity() == null || cmd.splitQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "splitQuantity must be positive");
        }
        StockItem parent = requireStockItemForUpdate(stockItemId);
        if (parent.getSerialNumber() != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot split a serialized stock item");
        }
        if (cmd.splitQuantity().compareTo(parent.getQuantity()) >= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "splitQuantity must be strictly less than current quantity (" + parent.getQuantity() + ")"
            );
        }

        UUID destLocationId = cmd.targetLocationId() != null ? cmd.targetLocationId() : parent.getLocationId();
        if (destLocationId != null) {
            StockLocation destLoc = locationService.requireLocation(destLocationId);
            if (destLoc.isStructural()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "STRUCTURAL_LOCATION_FORBIDS_STOCK: Cannot place split stock in structural location"
                );
            }
        }

        BigDecimal remaining = parent.getQuantity().subtract(cmd.splitQuantity());
        parent.setQuantity(remaining);
        stockItemRepository.saveAndFlush(parent);
        recordTracking(parent, "SPLIT_FROM", cmd.splitQuantity().negate(), remaining, parent.getLocationId(), cmd.notes());

        StockItem child = new StockItem();
        child.setPartId(parent.getPartId());
        child.setLocationId(destLocationId);
        child.setParentItemId(parent.getId());
        child.setQuantity(cmd.splitQuantity());
        child.setBatchCode(cmd.batchCode() != null && !cmd.batchCode().isBlank() ? cmd.batchCode().trim() : parent.getBatchCode());
        child.setStatus(parent.getStatus());
        child.setExpiryDate(parent.getExpiryDate());
        child.setPurchasePrice(parent.getPurchasePrice());
        child.setCurrency(parent.getCurrency());
        child.setPackaging(parent.getPackaging());

        StockItem savedChild = stockItemRepository.saveAndFlush(child);
        recordTracking(savedChild, "SPLIT_CHILD", cmd.splitQuantity(), cmd.splitQuantity(), destLocationId, cmd.notes());

        // Net delta is 0 (-splitQty + splitQty), but record both for completeness
        if (parent.countsTowardInStock()) {
            deltaAccumulator.addInStockDelta(parent.getPartId(), cmd.splitQuantity().negate());
            deltaAccumulator.addInStockDelta(savedChild.getPartId(), cmd.splitQuantity());
        }

        return enrichSingleStockItem(savedChild);
    }

    @Transactional
    public StockItemView mergeStock(MergeStockCommand cmd) {
        if (cmd.stockItemIds() == null || cmd.stockItemIds().size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least 2 stock items are required to merge");
        }

        // Lock rows in deterministic ascending ID order to prevent deadlocks
        List<StockItem> locked = stockItemRepository.findAllByIdInForUpdateOrderByIdAsc(cmd.stockItemIds());
        if (locked.size() != cmd.stockItemIds().stream().distinct().count()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more stock items to merge were not found");
        }

        StockItem primary = locked.getFirst();
        UUID partId = primary.getPartId();
        for (StockItem item : locked) {
            if (!item.getPartId().equals(partId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot merge stock items of different parts");
            }
            if (item.getSerialNumber() != null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot merge serialized stock items");
            }
        }

        UUID destLocationId = cmd.targetLocationId() != null ? cmd.targetLocationId() : primary.getLocationId();
        if (destLocationId != null) {
            StockLocation destLoc = locationService.requireLocation(destLocationId);
            if (destLoc.isStructural()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "STRUCTURAL_LOCATION_FORBIDS_STOCK: Cannot merge stock into structural location"
                );
            }
        }

        BigDecimal addedFromOthers = BigDecimal.ZERO;
        for (int i = 1; i < locked.size(); i++) {
            StockItem donor = locked.get(i);
            BigDecimal donorQty = donor.getQuantity();
            addedFromOthers = addedFromOthers.add(donorQty);
            donor.setQuantity(BigDecimal.ZERO);
            stockItemRepository.save(donor);
            recordTracking(donor, "MERGED", donorQty.negate(), BigDecimal.ZERO, donor.getLocationId(),
                    "Merged into stock item " + primary.getId());
            if (donor.countsTowardInStock()) {
                deltaAccumulator.addInStockDelta(partId, donorQty.negate());
            }
        }

        BigDecimal newPrimaryQty = primary.getQuantity().add(addedFromOthers);
        primary.setQuantity(newPrimaryQty);
        primary.setLocationId(destLocationId);
        StockItem savedPrimary = stockItemRepository.saveAndFlush(primary);
        recordTracking(savedPrimary, "MERGED", addedFromOthers, newPrimaryQty, destLocationId, cmd.notes());

        if (savedPrimary.countsTowardInStock()) {
            deltaAccumulator.addInStockDelta(partId, addedFromOthers);
        }

        return enrichSingleStockItem(savedPrimary);
    }

    /**
     * Converts N units of an untracked batch StockItem into N individual serialized StockItems
     * in a single transaction with one batched PartStockSummary counter flush.
     */
    @Transactional
    @RequiresFeature(Feature.SERIAL_TRACKING)
    public List<StockItemView> serializeStock(UUID stockItemId, SerializeStockCommand cmd) {
        if (cmd.serialNumbers() == null || cmd.serialNumbers().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serialNumbers list must not be empty");
        }
        List<String> serials = cmd.serialNumbers().stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (serials.size() != cmd.serialNumbers().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serialNumbers must be non-blank and unique");
        }

        StockItem batch = requireStockItemForUpdate(stockItemId);
        if (batch.getSerialNumber() != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Stock item is already serialized");
        }

        Part part = partService.requirePart(batch.getPartId());
        if (!part.isTrackable()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "PART_NOT_TRACKABLE: Part '" + part.getIpn() + "' must have trackable = true to serialize stock"
            );
        }

        BigDecimal countToSerialize = BigDecimal.valueOf(serials.size());
        if (batch.getQuantity().compareTo(countToSerialize) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cannot serialize " + serials.size() + " items from batch with quantity " + batch.getQuantity()
            );
        }

        for (String sn : serials) {
            if (stockItemRepository.existsByPartIdAndSerialNumber(part.getId(), sn)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Serial number '" + sn + "' already exists for part " + part.getIpn());
            }
        }

        UUID destLocationId = cmd.targetLocationId() != null ? cmd.targetLocationId() : batch.getLocationId();
        if (destLocationId != null) {
            StockLocation loc = locationService.requireLocation(destLocationId);
            if (loc.isStructural()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot serialize into structural location");
            }
        }

        BigDecimal remaining = batch.getQuantity().subtract(countToSerialize);
        batch.setQuantity(remaining);
        stockItemRepository.saveAndFlush(batch);
        recordTracking(batch, "SERIALIZED", countToSerialize.negate(), remaining, batch.getLocationId(), cmd.notes());
        if (batch.countsTowardInStock()) {
            deltaAccumulator.addInStockDelta(part.getId(), countToSerialize.negate());
        }

        List<StockItem> createdItems = new ArrayList<>(serials.size());
        for (String sn : serials) {
            StockItem serialized = new StockItem();
            serialized.setPartId(part.getId());
            serialized.setLocationId(destLocationId);
            serialized.setParentItemId(batch.getId());
            serialized.setQuantity(BigDecimal.ONE);
            serialized.setSerialNumber(sn);
            serialized.setBatchCode(batch.getBatchCode());
            serialized.setStatus(batch.getStatus());
            serialized.setExpiryDate(batch.getExpiryDate());
            serialized.setPurchasePrice(batch.getPurchasePrice());
            serialized.setCurrency(batch.getCurrency());
            serialized.setPackaging(batch.getPackaging());
            createdItems.add(serialized);
        }

        List<StockItem> savedItems = stockItemRepository.saveAllAndFlush(createdItems);
        for (StockItem s : savedItems) {
            recordTracking(s, "SERIALIZED", BigDecimal.ONE, BigDecimal.ONE, destLocationId, cmd.notes());
            if (s.countsTowardInStock()) {
                deltaAccumulator.addInStockDelta(part.getId(), BigDecimal.ONE);
            }
        }

        return savedItems.stream().map(this::enrichSingleStockItem).toList();
    }

    @Transactional(readOnly = true)
    public List<StockTrackingView> listTrackingHistory(UUID stockItemId) {
        requireStockItem(stockItemId);
        Map<UUID, String> locPaths = new HashMap<>();
        for (StockLocation l : locationRepository.findAllByOrderByPathStringAsc()) {
            locPaths.put(l.getId(), l.getPathString());
        }
        return trackingRepository.findByStockItemIdOrderByCreatedAtDesc(stockItemId).stream()
                .map(t -> new StockTrackingView(
                        t.getId(),
                        t.getStockItemId(),
                        t.getPartId(),
                        t.getTrackingType(),
                        t.getQuantityDelta(),
                        t.getQuantityAfter(),
                        t.getLocationId(),
                        t.getLocationId() != null ? locPaths.get(t.getLocationId()) : null,
                        t.getNotes(),
                        t.getUserId(),
                        t.getCreatedAt()
                ))
                .toList();
    }

    // =========================================================================
    // 2.5 Quality Control (PartTestTemplate & StockItemTestResult)
    // =========================================================================

    @Transactional(readOnly = true)
    @RequiresFeature(Feature.QUALITY_TESTS)
    public List<PartTestTemplateView> listTestTemplates(UUID partId) {
        partService.requirePart(partId);
        return testTemplateRepository.findByPartIdOrderByTestNameAsc(partId).stream()
                .map(t -> new PartTestTemplateView(
                        t.getId(),
                        t.getPartId(),
                        t.getTestName(),
                        t.getDescription(),
                        t.isRequired(),
                        t.isRequiresValue(),
                        t.isRequiresAttachment(),
                        t.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    @RequiresFeature(Feature.QUALITY_TESTS)
    public PartTestTemplateView createTestTemplate(UUID partId, CreateTestTemplateCommand cmd) {
        partService.requirePart(partId);
        if (cmd.testName() == null || cmd.testName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "testName is required");
        }
        String name = cmd.testName().trim();
        if (testTemplateRepository.existsByPartIdAndTestNameIgnoreCase(partId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Test template '" + name + "' already exists for this part");
        }
        PartTestTemplate tpl = new PartTestTemplate();
        tpl.setPartId(partId);
        tpl.setTestName(name);
        tpl.setDescription(cmd.description());
        tpl.setRequired(cmd.required() == null || cmd.required());
        tpl.setRequiresValue(Boolean.TRUE.equals(cmd.requiresValue()));
        tpl.setRequiresAttachment(Boolean.TRUE.equals(cmd.requiresAttachment()));
        PartTestTemplate saved = testTemplateRepository.saveAndFlush(tpl);
        return new PartTestTemplateView(
                saved.getId(),
                saved.getPartId(),
                saved.getTestName(),
                saved.getDescription(),
                saved.isRequired(),
                saved.isRequiresValue(),
                saved.isRequiresAttachment(),
                saved.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    @RequiresFeature(Feature.QUALITY_TESTS)
    public List<StockItemTestResultView> listTestResults(UUID stockItemId) {
        requireStockItem(stockItemId);
        return testResultRepository.findByStockItemIdOrderByCreatedAtDesc(stockItemId).stream()
                .map(r -> new StockItemTestResultView(
                        r.getId(),
                        r.getStockItemId(),
                        r.getTemplateId(),
                        r.getTestName(),
                        r.isPassed(),
                        r.getValue(),
                        r.getNotes(),
                        r.getAttachmentId(),
                        r.getTestedBy(),
                        r.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    @RequiresFeature(Feature.QUALITY_TESTS)
    public StockItemTestResultView recordTestResult(UUID stockItemId, RecordTestResultCommand cmd) {
        StockItem item = requireStockItemForUpdate(stockItemId);
        if (cmd.passed() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "passed (true/false) is required");
        }

        String testName = cmd.testName() != null ? cmd.testName().trim() : null;
        if (cmd.templateId() != null) {
            PartTestTemplate tpl = testTemplateRepository.findById(cmd.templateId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Test template not found"));
            testName = tpl.getTestName();
            if (tpl.isRequiresValue() && (cmd.value() == null || cmd.value().isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test '" + testName + "' requires a measured value");
            }
        }
        if (testName == null || testName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "testName or templateId is required");
        }

        StockItemTestResult result = new StockItemTestResult();
        result.setStockItemId(item.getId());
        result.setTemplateId(cmd.templateId());
        result.setTestName(testName);
        result.setPassed(cmd.passed());
        result.setValue(cmd.value());
        result.setNotes(cmd.notes());
        result.setAttachmentId(cmd.attachmentId());
        result.setTestedBy(AuditContext.currentActorId());

        StockItemTestResult saved = testResultRepository.saveAndFlush(result);
        recordTracking(
                item,
                "TEST_RECORDED",
                BigDecimal.ZERO,
                item.getQuantity(),
                item.getLocationId(),
                "Test '" + testName + "': " + (cmd.passed() ? "PASS" : "FAIL") + (cmd.value() != null ? " (" + cmd.value() + ")" : "")
        );

        return new StockItemTestResultView(
                saved.getId(),
                saved.getStockItemId(),
                saved.getTemplateId(),
                saved.getTestName(),
                saved.isPassed(),
                saved.getValue(),
                saved.getNotes(),
                saved.getAttachmentId(),
                saved.getTestedBy(),
                saved.getCreatedAt()
        );
    }

    // =========================================================================
    // 2.7 Stock CSV / XLSX Importer Integration
    // =========================================================================

    @Transactional
    public boolean commitImportedStockRow(Map<String, String> mappedRow) {
        String ipn = mappedRow.getOrDefault("ipn", "").trim();
        String rawRev = mappedRow.getOrDefault("revision", "A").trim();
        String revision = rawRev.isEmpty() ? "A" : rawRev;
        String qtyStr = mappedRow.getOrDefault("quantity", "0").trim();
        if (ipn.isEmpty() || qtyStr.isEmpty()) {
            return false;
        }
        BigDecimal quantity = new BigDecimal(qtyStr);
        List<Part> matchingParts = partService.searchPartsByKeyword(ipn, 20);
        Part part = matchingParts.stream()
                .filter(p -> p.getIpn().equalsIgnoreCase(ipn) && p.getRevision().equalsIgnoreCase(revision))
                .findFirst()
                .orElseGet(() -> matchingParts.stream()
                        .filter(p -> p.getIpn().equalsIgnoreCase(ipn))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Part not found for IPN: " + ipn)));

        UUID locationId = null;
        String locationName = mappedRow.getOrDefault("location", "").trim();
        if (!locationName.isEmpty()) {
            Optional<StockLocation> locOpt = locationRepository.searchByKeyword(locationName).stream()
                    .filter(l -> !l.isStructural() && (l.getName().equalsIgnoreCase(locationName) || l.getPathString().equalsIgnoreCase(locationName)))
                    .findFirst();
            if (locOpt.isPresent()) {
                locationId = locOpt.get().getId();
            }
        }

        String serial = mappedRow.getOrDefault("serialNumber", "").trim();
        String batchCode = mappedRow.getOrDefault("batchCode", "").trim();

        createStock(new CreateStockCommand(
                part.getId(),
                locationId,
                quantity,
                serial.isEmpty() ? null : serial,
                batchCode.isEmpty() ? null : batchCode,
                "OK",
                null,
                null,
                null,
                null,
                false,
                null,
                "Imported via CSV/XLSX"
        ));
        return true;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Transactional(readOnly = true)
    public StockItem requireStockItem(UUID id) {
        return stockItemRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock item not found: " + id));
    }

    public StockItem requireStockItemForUpdate(UUID id) {
        return stockItemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock item not found: " + id));
    }

    public void recordTracking(
            StockItem item,
            String trackingType,
            BigDecimal quantityDelta,
            BigDecimal quantityAfter,
            UUID locationId,
            String notes
    ) {
        StockItemTracking tracking = new StockItemTracking();
        tracking.setStockItemId(item.getId());
        tracking.setPartId(item.getPartId());
        tracking.setTrackingType(trackingType);
        tracking.setQuantityDelta(quantityDelta);
        tracking.setQuantityAfter(quantityAfter);
        tracking.setLocationId(locationId);
        tracking.setNotes(notes);
        tracking.setUserId(AuditContext.currentActorId());
        trackingRepository.save(tracking);
    }

    private Page<StockItemView> enrichStockPage(Page<StockItem> page) {
        Map<UUID, StockLocation> locationsById = new HashMap<>();
        for (StockLocation l : locationRepository.findAllByOrderByPathStringAsc()) {
            locationsById.put(l.getId(), l);
        }
        Map<UUID, Part> partsCache = new HashMap<>();
        return page.map(item -> {
            Part part = partsCache.computeIfAbsent(item.getPartId(), partService::requirePart);
            StockLocation loc = item.getLocationId() != null ? locationsById.get(item.getLocationId()) : null;
            return toStockItemView(item, part, loc);
        });
    }

    public StockItemView enrichSingleStockItem(StockItem item) {
        Part part = partService.requirePart(item.getPartId());
        StockLocation loc = item.getLocationId() != null
                ? locationRepository.findById(item.getLocationId()).orElse(null)
                : null;
        return toStockItemView(item, part, loc);
    }

    private StockItemView toStockItemView(StockItem item, Part part, StockLocation loc) {
        return new StockItemView(
                item.getId(),
                item.getPartId(),
                part.getIpn(),
                part.getName(),
                part.getUnit(),
                part.isTrackable(),
                item.getLocationId(),
                loc != null ? loc.getName() : null,
                loc != null ? loc.getPathString() : null,
                item.getParentItemId(),
                item.getInstalledInId(),
                item.getCustomerId(),
                item.getQuantity(),
                item.getSerialNumber(),
                item.getBatchCode(),
                item.getStatus(),
                item.getExpiryDate(),
                item.getPurchasePrice(),
                item.getCurrency(),
                item.getPackaging(),
                item.isBuilding(),
                item.getCustomBarcode(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
