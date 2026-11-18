package com.ims.part;

import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@Service
@RequiresFeature(Feature.MANUFACTURING)
public class BomService {

    public record BomSubstituteView(
            UUID id,
            UUID bomItemId,
            UUID partId,
            String partIpn,
            String partName
    ) {}

    public record BomItemView(
            UUID id,
            UUID partId,
            UUID subPartId,
            String subPartIpn,
            String subPartName,
            String subPartUnit,
            boolean subPartAssembly,
            boolean subPartTrackable,
            BigDecimal quantity,
            String reference,
            boolean optional,
            boolean consumable,
            boolean allowVariants,
            boolean inherited,
            String notes,
            List<BomSubstituteView> substitutes,
            Instant createdAt
    ) {}

    public record AddBomItemCommand(
            UUID subPartId,
            BigDecimal quantity,
            String reference,
            Boolean optional,
            Boolean consumable,
            Boolean allowVariants,
            Boolean inherited,
            String notes
    ) {}

    private final BomItemRepository bomItemRepository;
    private final BomItemSubstituteRepository substituteRepository;
    private final PartService partService;

    public BomService(
            BomItemRepository bomItemRepository,
            BomItemSubstituteRepository substituteRepository,
            PartService partService
    ) {
        this.bomItemRepository = bomItemRepository;
        this.substituteRepository = substituteRepository;
        this.partService = partService;
    }

    @Transactional(readOnly = true)
    public List<BomItemView> listBomItems(UUID partId) {
        partService.requirePart(partId);
        return bomItemRepository.findByPartIdOrderByCreatedAtAsc(partId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BomItemView> listWhereUsed(UUID subPartId) {
        partService.requirePart(subPartId);
        return bomItemRepository.findBySubPartIdOrderByCreatedAtAsc(subPartId).stream()
                .map(item -> {
                    Part parent = partService.requirePart(item.getPartId());
                    return new BomItemView(
                            item.getId(),
                            item.getPartId(),
                            item.getSubPartId(),
                            parent.getIpn(),
                            parent.getName(),
                            parent.getUnit(),
                            parent.isAssembly(),
                            parent.isTrackable(),
                            item.getQuantity(),
                            item.getReference(),
                            item.isOptional(),
                            item.isConsumable(),
                            item.isAllowVariants(),
                            item.isInherited(),
                            item.getNotes(),
                            List.of(),
                            item.getCreatedAt()
                    );
                })
                .toList();
    }

    @Transactional
    public BomItemView addBomItem(UUID partId, AddBomItemCommand cmd) {
        Part assembly = partService.requirePart(partId);
        if (!assembly.isAssembly()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "PART_NOT_AN_ASSEMBLY: Part '" + assembly.getIpn() + "' must have assembly = true to define a BOM"
            );
        }
        if (cmd.subPartId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subPartId is required");
        }
        Part subPart = partService.requirePart(cmd.subPartId());
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BOM quantity must be positive");
        }

        // Cycle Detection: Prevent A -> A or A -> B -> ... -> A
        ensureNoBomCycle(partId, subPart.getId());

        if (bomItemRepository.existsByPartIdAndSubPartId(partId, subPart.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Part '" + subPart.getIpn() + "' is already in the BOM of '" + assembly.getIpn() + "'"
            );
        }

        BomItem item = new BomItem();
        item.setPartId(partId);
        item.setSubPartId(subPart.getId());
        item.setQuantity(cmd.quantity());
        item.setReference(cmd.reference());
        item.setOptional(Boolean.TRUE.equals(cmd.optional()));
        item.setConsumable(Boolean.TRUE.equals(cmd.consumable()));
        item.setAllowVariants(cmd.allowVariants == null || cmd.allowVariants);
        item.setInherited(Boolean.TRUE.equals(cmd.inherited));
        item.setNotes(cmd.notes());

        BomItem saved = bomItemRepository.saveAndFlush(item);
        return toView(saved);
    }

    @Transactional
    public void deleteBomItem(UUID bomItemId) {
        BomItem item = bomItemRepository.findById(bomItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOM item not found"));
        bomItemRepository.delete(item);
    }

    @Transactional
    public BomSubstituteView addSubstitute(UUID bomItemId, UUID substitutePartId) {
        BomItem item = bomItemRepository.findById(bomItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "BOM item not found"));
        Part sub = partService.requirePart(substitutePartId);
        if (substitutePartId.equals(item.getPartId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Assembly cannot be its own BOM substitute");
        }
        if (substituteRepository.existsByBomItemIdAndPartId(bomItemId, substitutePartId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Substitute already registered on this BOM item");
        }
        BomItemSubstitute s = new BomItemSubstitute();
        s.setBomItemId(bomItemId);
        s.setPartId(substitutePartId);
        BomItemSubstitute saved = substituteRepository.saveAndFlush(s);
        return new BomSubstituteView(saved.getId(), bomItemId, sub.getId(), sub.getIpn(), sub.getName());
    }

    @Transactional
    public boolean commitImportedBomRow(Map<String, String> mappedRow) {
        String assemblyIpn = mappedRow.getOrDefault("assemblyIpn", "").trim();
        String componentIpn = mappedRow.getOrDefault("componentIpn", "").trim();
        String qtyStr = mappedRow.getOrDefault("quantity", "1").trim();
        if (assemblyIpn.isEmpty() || componentIpn.isEmpty()) {
            return false;
        }
        Part assembly = partService.searchPartsByKeyword(assemblyIpn, 10).stream()
                .filter(p -> p.getIpn().equalsIgnoreCase(assemblyIpn))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assembly part not found: " + assemblyIpn));
        Part component = partService.searchPartsByKeyword(componentIpn, 10).stream()
                .filter(p -> p.getIpn().equalsIgnoreCase(componentIpn))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Component part not found: " + componentIpn));

        BigDecimal qty = new BigDecimal(qtyStr);
        addBomItem(assembly.getId(), new AddBomItemCommand(
                component.getId(), qty, mappedRow.getOrDefault("reference", "").trim(),
                false, false, true, false, mappedRow.getOrDefault("notes", "").trim()
        ));
        return true;
    }

    private void ensureNoBomCycle(UUID assemblyId, UUID proposedSubPartId) {
        if (assemblyId.equals(proposedSubPartId)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BOM_CYCLE_DETECTED: A part cannot be added to its own Bill of Materials"
            );
        }
        // Walk upward from assemblyId through all parent assemblies; if proposedSubPartId is an ancestor, adding it creates a cycle.
        Set<UUID> visited = new HashSet<>();
        Queue<UUID> queue = new ArrayDeque<>();
        queue.add(assemblyId);
        visited.add(assemblyId);

        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (BomItem parentLink : bomItemRepository.findBySubPartIdOrderByCreatedAtAsc(current)) {
                UUID parentId = parentLink.getPartId();
                if (parentId.equals(proposedSubPartId)) {
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "BOM_CYCLE_DETECTED: Adding this component would create a circular BOM dependency"
                    );
                }
                if (visited.add(parentId)) {
                    queue.add(parentId);
                }
            }
        }
    }

    private BomItemView toView(BomItem item) {
        Part sub = partService.requirePart(item.getSubPartId());
        List<BomSubstituteView> subs = new ArrayList<>();
        for (BomItemSubstitute s : substituteRepository.findByBomItemIdOrderByCreatedAtAsc(item.getId())) {
            Part sp = partService.requirePart(s.getPartId());
            subs.add(new BomSubstituteView(s.getId(), item.getId(), sp.getId(), sp.getIpn(), sp.getName()));
        }
        return new BomItemView(
                item.getId(),
                item.getPartId(),
                item.getSubPartId(),
                sub.getIpn(),
                sub.getName(),
                sub.getUnit(),
                sub.isAssembly(),
                sub.isTrackable(),
                item.getQuantity(),
                item.getReference(),
                item.isOptional(),
                item.isConsumable(),
                item.isAllowVariants(),
                item.isInherited(),
                item.getNotes(),
                subs,
                item.getCreatedAt()
        );
    }
}
