package com.ims.stock;

import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiresFeature(Feature.STOCK)
public class StockLocationService {

    public record LocationView(
            UUID id,
            String name,
            String description,
            UUID parentId,
            String path,
            String pathString,
            int depth,
            String locationType,
            boolean structural,
            boolean externalLocation,
            String customBarcode,
            long directItemCount,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateLocationCommand(
            String name,
            String description,
            UUID parentId,
            String locationType,
            Boolean structural,
            Boolean externalLocation,
            String customBarcode
    ) {}

    public record UpdateLocationCommand(
            String name,
            String description,
            String locationType,
            Boolean structural,
            Boolean externalLocation,
            String customBarcode
    ) {}

    private final StockLocationRepository locationRepository;
    private final StockItemRepository stockItemRepository;
    private final EntitlementService entitlementService;

    public StockLocationService(
            StockLocationRepository locationRepository,
            StockItemRepository stockItemRepository,
            EntitlementService entitlementService
    ) {
        this.locationRepository = locationRepository;
        this.stockItemRepository = stockItemRepository;
        this.entitlementService = entitlementService;
    }

    @Transactional(readOnly = true)
    public long countLocations() {
        return locationRepository.count();
    }

    @Transactional(readOnly = true)
    public List<LocationView> listLocations() {
        return locationRepository.findAllByOrderByPathStringAsc().stream()
                .map(l -> toView(l, stockItemRepository.countByLocationId(l.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public LocationView getLocation(UUID id) {
        StockLocation loc = requireLocation(id);
        return toView(loc, stockItemRepository.countByLocationId(loc.getId()));
    }

    @Transactional
    public LocationView createLocation(CreateLocationCommand cmd) {
        entitlementService.requireWithinLimit(Feature.MAX_LOCATIONS, locationRepository.count());

        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Location name is required");
        }
        String trimmedName = cmd.name().trim();

        StockLocation parent = null;
        if (cmd.parentId() != null) {
            parent = requireLocation(cmd.parentId());
        }

        StockLocation loc = new StockLocation();
        loc.setName(trimmedName);
        loc.setDescription(cmd.description());
        loc.setParentId(parent != null ? parent.getId() : null);
        loc.setLocationType(cmd.locationType() != null && !cmd.locationType().isBlank() ? cmd.locationType().trim().toUpperCase() : "BIN");
        loc.setStructural(Boolean.TRUE.equals(cmd.structural()));
        loc.setExternalLocation(Boolean.TRUE.equals(cmd.externalLocation()));
        loc.setCustomBarcode(cmd.customBarcode() != null && !cmd.customBarcode().isBlank() ? cmd.customBarcode().trim() : null);

        String label = StockLocation.compactLabelFor(loc.getId());
        if (parent != null) {
            loc.setPath(parent.getPath() + "." + label);
            loc.setPathString(parent.getPathString() + " / " + trimmedName);
        } else {
            loc.setPath(label);
            loc.setPathString(trimmedName);
        }

        StockLocation saved = locationRepository.save(loc);
        return toView(saved, 0L);
    }

    @Transactional
    public LocationView updateLocation(UUID id, UpdateLocationCommand cmd) {
        StockLocation loc = requireLocation(id);

        if (Boolean.TRUE.equals(cmd.structural()) && !loc.isStructural()) {
            if (stockItemRepository.existsByLocationId(id)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cannot mark location as structural while stock items are stored directly inside it"
                );
            }
        }

        boolean nameChanged = false;
        if (cmd.name() != null && !cmd.name().isBlank()) {
            String newName = cmd.name().trim();
            if (!newName.equals(loc.getName())) {
                loc.setName(newName);
                nameChanged = true;
            }
        }
        if (cmd.description() != null) {
            loc.setDescription(cmd.description());
        }
        if (cmd.locationType() != null && !cmd.locationType().isBlank()) {
            loc.setLocationType(cmd.locationType().trim().toUpperCase());
        }
        if (cmd.structural() != null) {
            loc.setStructural(cmd.structural());
        }
        if (cmd.externalLocation() != null) {
            loc.setExternalLocation(cmd.externalLocation());
        }
        if (cmd.customBarcode() != null) {
            loc.setCustomBarcode(cmd.customBarcode().isBlank() ? null : cmd.customBarcode().trim());
        }

        StockLocation saved = locationRepository.saveAndFlush(loc);
        if (nameChanged) {
            recomputeSubtreePathStrings(saved.getPath());
            saved = requireLocation(id);
        }
        return toView(saved, stockItemRepository.countByLocationId(id));
    }

    @Transactional
    public LocationView moveSubtree(UUID locationId, UUID newParentId) {
        StockLocation loc = requireLocation(locationId);
        if (newParentId != null && newParentId.equals(locationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot move a location under itself");
        }

        String oldPath = loc.getPath();
        int oldDepth = oldPath.split("\\.").length - 1;

        String newPrefix = "";
        if (newParentId != null) {
            StockLocation newParent = requireLocation(newParentId);
            if (newParent.getPath().equals(oldPath) || newParent.getPath().startsWith(oldPath + ".")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Cannot move a location into one of its own descendants"
                );
            }
            newPrefix = newParent.getPath();
        }

        loc.setParentId(newParentId);
        locationRepository.saveAndFlush(loc);
        locationRepository.moveSubtreePaths(newPrefix, oldDepth, oldPath);

        StockLocation movedRoot = requireLocation(locationId);
        recomputeSubtreePathStrings(movedRoot.getPath());
        movedRoot = requireLocation(locationId);
        return toView(movedRoot, stockItemRepository.countByLocationId(locationId));
    }

    @Transactional
    public void deleteLocation(UUID locationId, String mode) {
        StockLocation loc = requireLocation(locationId);
        boolean cascade = "CASCADE".equalsIgnoreCase(mode);

        if (!cascade) {
            UUID parentId = loc.getParentId();
            if (parentId != null) {
                StockLocation parent = requireLocation(parentId);
                if (parent.isStructural() && stockItemRepository.existsByLocationId(locationId)) {
                    stockItemRepository.reassignLocation(locationId, null);
                } else {
                    stockItemRepository.reassignLocation(locationId, parentId);
                }
            } else {
                stockItemRepository.reassignLocation(locationId, null);
            }

            for (StockLocation child : locationRepository.findByParentIdOrderByNameAsc(locationId)) {
                moveSubtree(child.getId(), parentId);
            }
            StockLocation reloaded = requireLocation(locationId);
            locationRepository.delete(reloaded);
        } else {
            List<StockLocation> subtree = locationRepository.findSubtree(loc.getPath());
            subtree.sort(Comparator.comparingInt((StockLocation l) -> l.getPath().split("\\.").length).reversed());
            for (StockLocation node : subtree) {
                stockItemRepository.reassignLocation(node.getId(), null);
                locationRepository.delete(node);
            }
        }
    }

    @Transactional(readOnly = true)
    public StockLocation requireLocation(UUID id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock location not found: " + id));
    }

    private void recomputeSubtreePathStrings(String rootPath) {
        Map<UUID, StockLocation> byId = new HashMap<>();
        for (StockLocation l : locationRepository.findAllByOrderByPathStringAsc()) {
            byId.put(l.getId(), l);
        }

        List<StockLocation> subtree = locationRepository.findSubtree(rootPath);
        subtree.sort(Comparator.comparingInt(l -> l.getPath().split("\\.").length));
        for (StockLocation node : subtree) {
            if (node.getParentId() == null) {
                node.setPathString(node.getName());
            } else {
                StockLocation parent = byId.get(node.getParentId());
                String parentPathString = parent != null ? parent.getPathString() : "";
                node.setPathString(parentPathString.isEmpty() ? node.getName() : parentPathString + " / " + node.getName());
            }
            byId.put(node.getId(), locationRepository.save(node));
        }
        locationRepository.flush();
    }

    public static LocationView toView(StockLocation l, long directItemCount) {
        int depth = l.getPath() == null || l.getPath().isBlank() ? 0 : l.getPath().split("\\.").length - 1;
        return new LocationView(
                l.getId(),
                l.getName(),
                l.getDescription(),
                l.getParentId(),
                l.getPath(),
                l.getPathString(),
                depth,
                l.getLocationType(),
                l.isStructural(),
                l.isExternalLocation(),
                l.getCustomBarcode(),
                directItemCount,
                l.getCreatedAt(),
                l.getUpdatedAt()
        );
    }
}
