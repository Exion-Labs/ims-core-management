package com.ims.part;

import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.common.events.PartCreatedEvent;
import com.ims.common.io.ImportExportService;
import com.ims.common.outbox.OutboxWriter;
import com.ims.config.tenant.TenantContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
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
@RequiresFeature(Feature.PARTS)
public class PartService {

    public record ParameterTemplateView(
            UUID id,
            String name,
            String units,
            String description,
            String choices,
            boolean checkbox,
            UUID defaultCategoryId,
            String defaultCategoryPath,
            Instant createdAt
    ) {}

    public record PartParameterView(
            UUID id,
            UUID partId,
            UUID templateId,
            String templateName,
            String templateUnits,
            boolean checkbox,
            String valueText,
            Double valueNumeric,
            Instant updatedAt
    ) {}

    public record PartView(
            UUID id,
            String ipn,
            String name,
            String description,
            UUID categoryId,
            String categoryName,
            String categoryPathString,
            String revision,
            UUID revisionOfId,
            UUID variantOfId,
            String unit,
            BigDecimal minimumStock,
            boolean active,
            boolean locked,
            boolean assembly,
            boolean component,
            boolean trackable,
            boolean purchaseable,
            boolean salable,
            boolean virtual,
            Map<String, Object> metadata,
            List<PartParameterView> parameters,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record InitialParameterInput(
            UUID templateId,
            String value
    ) {}

    public record CreatePartCommand(
            String ipn,
            String name,
            String description,
            UUID categoryId,
            String revision,
            UUID revisionOfId,
            UUID variantOfId,
            String unit,
            BigDecimal minimumStock,
            Boolean active,
            Boolean locked,
            Boolean assembly,
            Boolean component,
            Boolean trackable,
            Boolean purchaseable,
            Boolean salable,
            Boolean virtual,
            Map<String, Object> metadata,
            List<InitialParameterInput> parameters
    ) {}

    public record UpdatePartCommand(
            String ipn,
            String name,
            String description,
            UUID categoryId,
            String revision,
            UUID revisionOfId,
            UUID variantOfId,
            String unit,
            BigDecimal minimumStock,
            Boolean active,
            Boolean locked,
            Boolean assembly,
            Boolean component,
            Boolean trackable,
            Boolean purchaseable,
            Boolean salable,
            Boolean virtual,
            Map<String, Object> metadata
    ) {}

    public record CreateParameterTemplateCommand(
            String name,
            String units,
            String description,
            String choices,
            Boolean checkbox,
            UUID defaultCategoryId
    ) {}

    public record UpsertPartParameterCommand(
            UUID templateId,
            String value
    ) {}

    public record PartSearchFilter(
            String q,
            UUID categoryId,
            boolean includeSubcategories,
            Boolean active,
            Boolean assembly,
            Boolean component,
            Boolean trackable,
            Boolean purchaseable,
            Boolean salable,
            Boolean virtual,
            UUID variantOfId,
            UUID revisionOfId,
            UUID paramTemplateId,
            String paramMin,
            String paramMax,
            String paramValueText
    ) {}

    private final PartRepository partRepository;
    private final PartCategoryRepository categoryRepository;
    private final PartCategoryService categoryService;
    private final ParameterTemplateRepository templateRepository;
    private final PartParameterRepository parameterRepository;
    private final PartParameterValidator parameterValidator;
    private final EntitlementService entitlementService;
    private final OutboxWriter outboxWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final ImportExportService importExportService;

    public PartService(
            PartRepository partRepository,
            PartCategoryRepository categoryRepository,
            PartCategoryService categoryService,
            ParameterTemplateRepository templateRepository,
            PartParameterRepository parameterRepository,
            PartParameterValidator parameterValidator,
            EntitlementService entitlementService,
            OutboxWriter outboxWriter,
            ApplicationEventPublisher eventPublisher,
            ImportExportService importExportService
    ) {
        this.partRepository = partRepository;
        this.categoryRepository = categoryRepository;
        this.categoryService = categoryService;
        this.templateRepository = templateRepository;
        this.parameterRepository = parameterRepository;
        this.parameterValidator = parameterValidator;
        this.entitlementService = entitlementService;
        this.outboxWriter = outboxWriter;
        this.eventPublisher = eventPublisher;
        this.importExportService = importExportService;
    }

    @Transactional(readOnly = true)
    public Page<PartView> searchParts(PartSearchFilter filter, Pageable pageable) {
        final String subtreePrefix;
        if (filter.categoryId() != null && filter.includeSubcategories()) {
            PartCategory cat = categoryService.requireCategory(filter.categoryId());
            subtreePrefix = cat.getPath();
        } else if (filter.categoryId() != null) {
            categoryService.requireCategory(filter.categoryId());
            subtreePrefix = null;
        } else {
            subtreePrefix = null;
        }

        final ParameterTemplate filterTemplate;
        final Double minSi;
        final Double maxSi;
        if (filter.paramTemplateId() != null) {
            filterTemplate = requireTemplate(filter.paramTemplateId());
            minSi = parameterValidator.parseRangeBound(filterTemplate, filter.paramMin());
            maxSi = parameterValidator.parseRangeBound(filterTemplate, filter.paramMax());
        } else {
            filterTemplate = null;
            minSi = null;
            maxSi = null;
        }

        Specification<Part> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.q() != null && !filter.q().isBlank()) {
                String pattern = "%" + filter.q().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("ipn")), pattern),
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern)
                ));
            }

            if (filter.categoryId() != null) {
                if (filter.includeSubcategories() && subtreePrefix != null && query != null) {
                    Subquery<UUID> catSub = query.subquery(UUID.class);
                    Root<PartCategory> catRoot = catSub.from(PartCategory.class);
                    catSub.select(catRoot.get("id"))
                            .where(cb.isTrue(cb.function(
                                    "ltree_descendant",
                                    Boolean.class,
                                    catRoot.get("path"),
                                    cb.literal(subtreePrefix)
                            )));
                    predicates.add(root.get("categoryId").in(catSub));
                } else {
                    predicates.add(cb.equal(root.get("categoryId"), filter.categoryId()));
                }
            }

            if (filter.active() != null) {
                predicates.add(cb.equal(root.get("active"), filter.active()));
            }
            if (filter.assembly() != null) {
                predicates.add(cb.equal(root.get("assembly"), filter.assembly()));
            }
            if (filter.component() != null) {
                predicates.add(cb.equal(root.get("component"), filter.component()));
            }
            if (filter.trackable() != null) {
                predicates.add(cb.equal(root.get("trackable"), filter.trackable()));
            }
            if (filter.purchaseable() != null) {
                predicates.add(cb.equal(root.get("purchaseable"), filter.purchaseable()));
            }
            if (filter.salable() != null) {
                predicates.add(cb.equal(root.get("salable"), filter.salable()));
            }
            if (filter.virtual() != null) {
                predicates.add(cb.equal(root.get("virtual"), filter.virtual()));
            }
            if (filter.variantOfId() != null) {
                predicates.add(cb.equal(root.get("variantOfId"), filter.variantOfId()));
            }
            if (filter.revisionOfId() != null) {
                predicates.add(cb.equal(root.get("revisionOfId"), filter.revisionOfId()));
            }

            if (filterTemplate != null && query != null) {
                Subquery<UUID> paramSub = query.subquery(UUID.class);
                Root<PartParameter> paramRoot = paramSub.from(PartParameter.class);
                List<Predicate> paramPreds = new ArrayList<>();
                paramPreds.add(cb.equal(paramRoot.get("partId"), root.get("id")));
                paramPreds.add(cb.equal(paramRoot.get("templateId"), filterTemplate.getId()));
                if (minSi != null) {
                    paramPreds.add(cb.greaterThanOrEqualTo(paramRoot.get("valueNumeric"), minSi));
                }
                if (maxSi != null) {
                    paramPreds.add(cb.lessThanOrEqualTo(paramRoot.get("valueNumeric"), maxSi));
                }
                if (filter.paramValueText() != null && !filter.paramValueText().isBlank()) {
                    String textPat = "%" + filter.paramValueText().trim().toLowerCase(Locale.ROOT) + "%";
                    paramPreds.add(cb.like(cb.lower(paramRoot.get("valueText")), textPat));
                }
                paramSub.select(paramRoot.get("partId")).where(paramPreds.toArray(new Predicate[0]));
                predicates.add(cb.exists(paramSub));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Part> page = partRepository.findAll(spec, pageable);
        return enrichPartsPage(page);
    }

    @Transactional(readOnly = true)
    public PartView getPart(UUID id) {
        Part part = requirePart(id);
        return enrichSinglePart(part);
    }

    @Transactional
    public PartView createPart(CreatePartCommand cmd) {
        entitlementService.requireWithinLimit(Feature.MAX_PARTS, partRepository.count());

        if (cmd.ipn() == null || cmd.ipn().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Part IPN is required");
        }
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Part name is required");
        }

        String ipn = cmd.ipn().trim();
        String name = cmd.name().trim();
        String revision = (cmd.revision() == null || cmd.revision().isBlank()) ? "A" : cmd.revision().trim();

        boolean isVirtual = Boolean.TRUE.equals(cmd.virtual());
        boolean isPurchaseable = cmd.purchaseable() != null ? cmd.purchaseable() : !isVirtual;

        // Domain Rule: Parts with virtual = true cannot have purchaseable = true
        if (isVirtual && isPurchaseable) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "VIRTUAL_PART_CANNOT_BE_PURCHASEABLE: Virtual parts cannot be marked as purchaseable"
            );
        }

        // Domain Rule: Categories with structural = true reject direct part assignments
        if (cmd.categoryId() != null) {
            PartCategory category = categoryService.requireCategory(cmd.categoryId());
            if (category.isStructural()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "STRUCTURAL_CATEGORY_FORBIDS_PARTS: Cannot assign a part directly to structural category '" + category.getName() + "'"
                );
            }
        }

        if (cmd.revisionOfId() != null) {
            requirePart(cmd.revisionOfId());
        }
        if (cmd.variantOfId() != null) {
            requirePart(cmd.variantOfId());
        }

        if (partRepository.existsByIpnAndRevision(ipn, revision)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A part with IPN '" + ipn + "' and revision '" + revision + "' already exists"
            );
        }

        Part part = new Part();
        part.setIpn(ipn);
        part.setName(name);
        part.setDescription(cmd.description());
        part.setCategoryId(cmd.categoryId());
        part.setRevision(revision);
        part.setRevisionOfId(cmd.revisionOfId());
        part.setVariantOfId(cmd.variantOfId());
        part.setUnit(cmd.unit() != null && !cmd.unit().isBlank() ? cmd.unit().trim() : "pcs");
        part.setMinimumStock(cmd.minimumStock() != null ? cmd.minimumStock() : BigDecimal.ZERO);
        part.setActive(cmd.active() != null ? cmd.active() : true);
        part.setLocked(Boolean.TRUE.equals(cmd.locked()));
        part.setAssembly(Boolean.TRUE.equals(cmd.assembly()));
        part.setComponent(cmd.component() != null ? cmd.component() : true);
        part.setTrackable(Boolean.TRUE.equals(cmd.trackable()));
        part.setPurchaseable(isPurchaseable);
        part.setSalable(Boolean.TRUE.equals(cmd.salable()));
        part.setVirtual(isVirtual);
        if (cmd.metadata() != null) {
            part.setMetadata(new HashMap<>(cmd.metadata()));
        }

        Part saved = partRepository.saveAndFlush(part);

        if (cmd.parameters() != null) {
            for (InitialParameterInput paramInput : cmd.parameters()) {
                if (paramInput.templateId() != null && paramInput.value() != null && !paramInput.value().isBlank()) {
                    upsertPartParameter(saved.getId(), new UpsertPartParameterCommand(paramInput.templateId(), paramInput.value()));
                }
            }
        }

        UUID tenantId = TenantContext.require();
        PartCreatedEvent event = new PartCreatedEvent(
                tenantId,
                saved.getId(),
                saved.getIpn(),
                saved.getName(),
                saved.getRevision(),
                saved.getVariantOfId(),
                Instant.now()
        );
        eventPublisher.publishEvent(event);
        Map<String, Object> outboxPayload = new LinkedHashMap<>();
        outboxPayload.put("partId", saved.getId().toString());
        outboxPayload.put("ipn", saved.getIpn());
        outboxPayload.put("name", saved.getName());
        outboxPayload.put("revision", saved.getRevision());
        if (saved.getVariantOfId() != null) {
            outboxPayload.put("variantOfId", saved.getVariantOfId().toString());
        }
        outboxWriter.enqueue(event.eventType(), event.aggregateType(), event.aggregateId(), outboxPayload);

        return enrichSinglePart(saved);
    }

    @Transactional
    public PartView updatePart(UUID id, UpdatePartCommand cmd) {
        Part part = requirePart(id);

        if (part.isLocked() && (cmd.locked() == null || cmd.locked())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Part '" + part.getIpn() + "' is locked and cannot be modified until unlocked"
            );
        }

        boolean nextVirtual = cmd.virtual() != null ? cmd.virtual() : part.isVirtual();
        boolean nextPurchaseable = cmd.purchaseable() != null ? cmd.purchaseable() : part.isPurchaseable();
        if (nextVirtual && nextPurchaseable) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "VIRTUAL_PART_CANNOT_BE_PURCHASEABLE: Virtual parts cannot be marked as purchaseable"
            );
        }

        if (cmd.categoryId() != null) {
            PartCategory category = categoryService.requireCategory(cmd.categoryId());
            if (category.isStructural()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "STRUCTURAL_CATEGORY_FORBIDS_PARTS: Cannot assign a part directly to structural category '" + category.getName() + "'"
                );
            }
            part.setCategoryId(cmd.categoryId());
        }

        String nextIpn = (cmd.ipn() != null && !cmd.ipn().isBlank()) ? cmd.ipn().trim() : part.getIpn();
        String nextRev = (cmd.revision() != null && !cmd.revision().isBlank()) ? cmd.revision().trim() : part.getRevision();
        if ((!nextIpn.equals(part.getIpn()) || !nextRev.equals(part.getRevision()))
                && partRepository.existsByIpnAndRevision(nextIpn, nextRev)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A part with IPN '" + nextIpn + "' and revision '" + nextRev + "' already exists"
            );
        }

        part.setIpn(nextIpn);
        part.setRevision(nextRev);
        if (cmd.name() != null && !cmd.name().isBlank()) {
            part.setName(cmd.name().trim());
        }
        if (cmd.description() != null) {
            part.setDescription(cmd.description());
        }
        if (cmd.revisionOfId() != null) {
            requirePart(cmd.revisionOfId());
            part.setRevisionOfId(cmd.revisionOfId());
        }
        if (cmd.variantOfId() != null) {
            requirePart(cmd.variantOfId());
            part.setVariantOfId(cmd.variantOfId());
        }
        if (cmd.unit() != null && !cmd.unit().isBlank()) {
            part.setUnit(cmd.unit().trim());
        }
        if (cmd.minimumStock() != null) {
            part.setMinimumStock(cmd.minimumStock());
        }
        if (cmd.active() != null) {
            part.setActive(cmd.active());
        }
        if (cmd.locked() != null) {
            part.setLocked(cmd.locked());
        }
        if (cmd.assembly() != null) {
            part.setAssembly(cmd.assembly());
        }
        if (cmd.component() != null) {
            part.setComponent(cmd.component());
        }
        if (cmd.trackable() != null) {
            part.setTrackable(cmd.trackable());
        }
        part.setPurchaseable(nextPurchaseable);
        if (cmd.salable() != null) {
            part.setSalable(cmd.salable());
        }
        part.setVirtual(nextVirtual);
        if (cmd.metadata() != null) {
            part.setMetadata(new HashMap<>(cmd.metadata()));
        }

        Part saved = partRepository.saveAndFlush(part);
        return enrichSinglePart(saved);
    }

    @Transactional
    public void deletePart(UUID id) {
        Part part = requirePart(id);
        partRepository.delete(part);
    }

    @Transactional(readOnly = true)
    public List<PartView> listVariants(UUID templatePartId) {
        requirePart(templatePartId);
        return partRepository.findByVariantOfIdOrderByIpnAsc(templatePartId).stream()
                .map(this::enrichSinglePart)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PartView> listRevisions(UUID partId) {
        Part part = requirePart(partId);
        UUID rootId = part.getRevisionOfId() != null ? part.getRevisionOfId() : part.getId();
        List<Part> list = new ArrayList<>();
        partRepository.findById(rootId).ifPresent(list::add);
        list.addAll(partRepository.findByRevisionOfIdOrderByRevisionAsc(rootId));
        return list.stream()
                .map(this::enrichSinglePart)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countParts() {
        return partRepository.count();
    }

    @Transactional(readOnly = true)
    public List<Part> searchPartsByKeyword(String q, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return partRepository.searchByKeyword(q.trim()).stream()
                .limit(limit)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PartCategory> searchCategoriesByKeyword(String q, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return categoryRepository.searchByKeyword(q.trim()).stream()
                .limit(limit)
                .toList();
    }

    // =========================================================================
    // Parameter Templates & Typed Part Parameters
    // =========================================================================

    @Transactional(readOnly = true)
    public List<ParameterTemplateView> listParameterTemplates(UUID categoryId) {
        List<ParameterTemplate> templates = categoryId != null
                ? templateRepository.findByDefaultCategoryIdOrderByNameAsc(categoryId)
                : templateRepository.findAllByOrderByNameAsc();

        Map<UUID, String> categoryPaths = new HashMap<>();
        for (PartCategory c : categoryRepository.findAllByOrderByPathStringAsc()) {
            categoryPaths.put(c.getId(), c.getPathString());
        }

        return templates.stream()
                .map(t -> new ParameterTemplateView(
                        t.getId(),
                        t.getName(),
                        t.getUnits(),
                        t.getDescription(),
                        t.getChoices(),
                        t.isCheckbox(),
                        t.getDefaultCategoryId(),
                        t.getDefaultCategoryId() != null ? categoryPaths.get(t.getDefaultCategoryId()) : null,
                        t.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public ParameterTemplateView createParameterTemplate(CreateParameterTemplateCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter template name is required");
        }
        String name = cmd.name().trim();
        if (templateRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Parameter template '" + name + "' already exists");
        }
        parameterValidator.validateTemplateUnits(cmd.units());

        String catPath = null;
        if (cmd.defaultCategoryId() != null) {
            PartCategory cat = categoryService.requireCategory(cmd.defaultCategoryId());
            catPath = cat.getPathString();
        }

        ParameterTemplate template = new ParameterTemplate();
        template.setName(name);
        template.setUnits(cmd.units() != null && !cmd.units().isBlank() ? cmd.units().trim() : null);
        template.setDescription(cmd.description());
        template.setChoices(cmd.choices() != null && !cmd.choices().isBlank() ? cmd.choices().trim() : null);
        template.setCheckbox(Boolean.TRUE.equals(cmd.checkbox()));
        template.setDefaultCategoryId(cmd.defaultCategoryId());

        ParameterTemplate saved = templateRepository.saveAndFlush(template);
        return new ParameterTemplateView(
                saved.getId(),
                saved.getName(),
                saved.getUnits(),
                saved.getDescription(),
                saved.getChoices(),
                saved.isCheckbox(),
                saved.getDefaultCategoryId(),
                catPath,
                saved.getCreatedAt()
        );
    }

    @Transactional
    public void deleteParameterTemplate(UUID id) {
        ParameterTemplate template = requireTemplate(id);
        templateRepository.delete(template);
    }

    @Transactional(readOnly = true)
    public List<PartParameterView> listPartParameters(UUID partId) {
        requirePart(partId);
        List<PartParameter> params = parameterRepository.findByPartIdOrderByCreatedAtAsc(partId);
        Map<UUID, ParameterTemplate> templatesById = new HashMap<>();
        for (ParameterTemplate t : templateRepository.findAllByOrderByNameAsc()) {
            templatesById.put(t.getId(), t);
        }
        return params.stream()
                .map(p -> toParameterView(p, templatesById.get(p.getTemplateId())))
                .toList();
    }

    @Transactional
    public PartParameterView upsertPartParameter(UUID partId, UpsertPartParameterCommand cmd) {
        Part part = requirePart(partId);
        if (part.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Part is locked and parameters cannot be modified");
        }
        if (cmd.templateId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "templateId is required");
        }
        ParameterTemplate template = requireTemplate(cmd.templateId());
        PartParameterValidator.ValidatedParameter validated = parameterValidator.validateAndNormalize(template, cmd.value());

        PartParameter param = parameterRepository.findByPartIdAndTemplateId(partId, template.getId())
                .orElseGet(() -> {
                    PartParameter np = new PartParameter();
                    np.setPartId(partId);
                    np.setTemplateId(template.getId());
                    return np;
                });

        param.setValueText(validated.normalizedText());
        param.setValueNumeric(validated.numericValue());
        PartParameter saved = parameterRepository.saveAndFlush(param);
        return toParameterView(saved, template);
    }

    @Transactional
    public void deletePartParameter(UUID partId, UUID parameterId) {
        Part part = requirePart(partId);
        if (part.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Part is locked and parameters cannot be modified");
        }
        PartParameter param = parameterRepository.findById(parameterId)
                .filter(p -> p.getPartId().equals(partId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Part parameter not found"));
        parameterRepository.delete(param);
    }

    // =========================================================================
    // Bulk CSV / XLSX Importers & Exporters (Phase 0 Framework Integration)
    // =========================================================================

    @Transactional(readOnly = true)
    public List<ImportExportService.RowValidationError> validatePartImportRow(Map<String, String> mappedRow) {
        List<ImportExportService.RowValidationError> errors = new ArrayList<>();
        String ipn = mappedRow.getOrDefault("ipn", "").trim();
        String name = mappedRow.getOrDefault("name", "").trim();
        boolean virtual = Boolean.parseBoolean(mappedRow.getOrDefault("virtual", "false").trim());
        String purchaseableStr = mappedRow.getOrDefault("purchaseable", "").trim();
        boolean purchaseable = purchaseableStr.isEmpty() ? !virtual : Boolean.parseBoolean(purchaseableStr);

        if (ipn.isEmpty()) {
            errors.add(new ImportExportService.RowValidationError(0, "ipn", "IPN is required"));
        }
        if (name.isEmpty()) {
            errors.add(new ImportExportService.RowValidationError(0, "name", "Part name is required"));
        }
        if (virtual && purchaseable) {
            errors.add(new ImportExportService.RowValidationError(0, "purchaseable", "Virtual parts cannot be purchaseable"));
        }
        String categoryIdStr = mappedRow.getOrDefault("categoryId", "").trim();
        if (!categoryIdStr.isEmpty()) {
            try {
                UUID catId = UUID.fromString(categoryIdStr);
                Optional<PartCategory> catOpt = categoryRepository.findById(catId);
                if (catOpt.isEmpty()) {
                    errors.add(new ImportExportService.RowValidationError(0, "categoryId", "Category not found: " + categoryIdStr));
                } else if (catOpt.get().isStructural()) {
                    errors.add(new ImportExportService.RowValidationError(0, "categoryId", "Cannot assign part to structural category"));
                }
            } catch (IllegalArgumentException e) {
                errors.add(new ImportExportService.RowValidationError(0, "categoryId", "Invalid category UUID: " + categoryIdStr));
            }
        }
        return errors;
    }

    @Transactional
    public boolean commitImportedPartRow(Map<String, String> mappedRow) {
        String ipn = mappedRow.getOrDefault("ipn", "").trim();
        String name = mappedRow.getOrDefault("name", "").trim();
        if (ipn.isEmpty() || name.isEmpty()) {
            return false;
        }
        String revision = mappedRow.getOrDefault("revision", "A").trim();
        if (revision.isEmpty()) {
            revision = "A";
        }
        String description = mappedRow.getOrDefault("description", "").trim();
        String unit = mappedRow.getOrDefault("unit", "pcs").trim();
        if (unit.isEmpty()) {
            unit = "pcs";
        }
        BigDecimal minStock = BigDecimal.ZERO;
        String minStockStr = mappedRow.getOrDefault("minimumStock", "0").trim();
        if (!minStockStr.isEmpty()) {
            try {
                minStock = new BigDecimal(minStockStr);
            } catch (NumberFormatException ignored) {
            }
        }

        UUID categoryId = null;
        String categoryIdStr = mappedRow.getOrDefault("categoryId", "").trim();
        if (!categoryIdStr.isEmpty()) {
            categoryId = UUID.fromString(categoryIdStr);
        } else {
            String categoryName = mappedRow.getOrDefault("category", "").trim();
            if (!categoryName.isEmpty()) {
                List<PartCategory> matches = categoryRepository.searchByKeyword(categoryName);
                Optional<PartCategory> nonStructural = matches.stream()
                        .filter(c -> !c.isStructural() && (c.getName().equalsIgnoreCase(categoryName) || c.getPathString().equalsIgnoreCase(categoryName)))
                        .findFirst();
                if (nonStructural.isPresent()) {
                    categoryId = nonStructural.get().getId();
                }
            }
        }

        boolean virtual = Boolean.parseBoolean(mappedRow.getOrDefault("virtual", "false").trim());
        String purchaseableStr = mappedRow.getOrDefault("purchaseable", "").trim();
        boolean purchaseable = purchaseableStr.isEmpty() ? !virtual : Boolean.parseBoolean(purchaseableStr);
        boolean assembly = Boolean.parseBoolean(mappedRow.getOrDefault("assembly", "false").trim());
        String componentStr = mappedRow.getOrDefault("component", "").trim();
        boolean component = componentStr.isEmpty() || Boolean.parseBoolean(componentStr);
        boolean trackable = Boolean.parseBoolean(mappedRow.getOrDefault("trackable", "false").trim());
        boolean salable = Boolean.parseBoolean(mappedRow.getOrDefault("salable", "false").trim());

        Optional<Part> existing = partRepository.findByIpnAndRevision(ipn, revision);
        if (existing.isPresent()) {
            updatePart(existing.get().getId(), new UpdatePartCommand(
                    ipn, name, description, categoryId, revision, null, null,
                    unit, minStock, true, null, assembly, component, trackable, purchaseable, salable, virtual, null
            ));
        } else {
            createPart(new CreatePartCommand(
                    ipn, name, description, categoryId, revision, null, null,
                    unit, minStock, true, false, assembly, component, trackable, purchaseable, salable, virtual, null, null
            ));
        }
        return true;
    }

    @Transactional
    public boolean commitImportedPartParameterRow(Map<String, String> mappedRow) {
        String ipn = mappedRow.getOrDefault("ipn", "").trim();
        String rawRev = mappedRow.getOrDefault("revision", "A").trim();
        final String revision = rawRev.isEmpty() ? "A" : rawRev;
        String templateName = mappedRow.getOrDefault("templateName", "").trim();
        String value = mappedRow.getOrDefault("value", "").trim();
        if (ipn.isEmpty() || templateName.isEmpty() || value.isEmpty()) {
            return false;
        }
        Part part = partRepository.findByIpnAndRevision(ipn, revision)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Part not found for IPN " + ipn + " rev " + revision));
        ParameterTemplate template = templateRepository.findByNameIgnoreCase(templateName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parameter template not found: " + templateName));
        upsertPartParameter(part.getId(), new UpsertPartParameterCommand(template.getId(), value));
        return true;
    }

    @Transactional(readOnly = true)
    @RequiresFeature(Feature.IMPORT_EXPORT)
    public byte[] exportParts(String format) {
        List<Part> parts = partRepository.findAll();
        Map<UUID, PartCategory> categoriesById = new HashMap<>();
        for (PartCategory c : categoryRepository.findAllByOrderByPathStringAsc()) {
            categoriesById.put(c.getId(), c);
        }

        List<String> headers = List.of(
                "ipn", "revision", "name", "description", "category", "unit", "minimumStock",
                "active", "assembly", "component", "trackable", "purchaseable", "salable", "virtual"
        );
        List<List<String>> rows = parts.stream()
                .map(p -> {
                    PartCategory cat = p.getCategoryId() != null ? categoriesById.get(p.getCategoryId()) : null;
                    return List.of(
                            p.getIpn(),
                            p.getRevision(),
                            p.getName(),
                            p.getDescription() != null ? p.getDescription() : "",
                            cat != null ? cat.getPathString() : "",
                            p.getUnit(),
                            p.getMinimumStock().toPlainString(),
                            String.valueOf(p.isActive()),
                            String.valueOf(p.isAssembly()),
                            String.valueOf(p.isComponent()),
                            String.valueOf(p.isTrackable()),
                            String.valueOf(p.isPurchaseable()),
                            String.valueOf(p.isSalable()),
                            String.valueOf(p.isVirtual())
                    );
                })
                .toList();

        if ("xlsx".equalsIgnoreCase(format)) {
            return importExportService.exportXlsx("Parts", headers, rows, "Part");
        }
        return importExportService.exportCsv(headers, rows, "Part");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Transactional(readOnly = true)
    public Part requirePart(UUID id) {
        return partRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Part not found: " + id));
    }

    @Transactional(readOnly = true)
    public ParameterTemplate requireTemplate(UUID id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parameter template not found: " + id));
    }

    private Page<PartView> enrichPartsPage(Page<Part> page) {
        List<Part> content = page.getContent();
        if (content.isEmpty()) {
            return page.map(p -> toPartView(p, null, List.of()));
        }

        Map<UUID, PartCategory> categoriesById = new HashMap<>();
        for (PartCategory c : categoryRepository.findAllByOrderByPathStringAsc()) {
            categoriesById.put(c.getId(), c);
        }

        Map<UUID, ParameterTemplate> templatesById = new HashMap<>();
        for (ParameterTemplate t : templateRepository.findAllByOrderByNameAsc()) {
            templatesById.put(t.getId(), t);
        }

        Collection<UUID> partIds = content.stream().map(Part::getId).toList();
        Map<UUID, List<PartParameterView>> paramsByPartId = new HashMap<>();
        for (PartParameter pp : parameterRepository.findByPartIdIn(partIds)) {
            paramsByPartId.computeIfAbsent(pp.getPartId(), k -> new ArrayList<>())
                    .add(toParameterView(pp, templatesById.get(pp.getTemplateId())));
        }

        return page.map(p -> toPartView(
                p,
                p.getCategoryId() != null ? categoriesById.get(p.getCategoryId()) : null,
                paramsByPartId.getOrDefault(p.getId(), List.of())
        ));
    }

    private PartView enrichSinglePart(Part part) {
        PartCategory cat = part.getCategoryId() != null
                ? categoryRepository.findById(part.getCategoryId()).orElse(null)
                : null;
        Map<UUID, ParameterTemplate> templatesById = new HashMap<>();
        for (ParameterTemplate t : templateRepository.findAllByOrderByNameAsc()) {
            templatesById.put(t.getId(), t);
        }
        List<PartParameterView> params = parameterRepository.findByPartIdOrderByCreatedAtAsc(part.getId()).stream()
                .map(p -> toParameterView(p, templatesById.get(p.getTemplateId())))
                .toList();
        return toPartView(part, cat, params);
    }

    private PartView toPartView(Part p, PartCategory cat, List<PartParameterView> params) {
        return new PartView(
                p.getId(),
                p.getIpn(),
                p.getName(),
                p.getDescription(),
                p.getCategoryId(),
                cat != null ? cat.getName() : null,
                cat != null ? cat.getPathString() : null,
                p.getRevision(),
                p.getRevisionOfId(),
                p.getVariantOfId(),
                p.getUnit(),
                p.getMinimumStock(),
                p.isActive(),
                p.isLocked(),
                p.isAssembly(),
                p.isComponent(),
                p.isTrackable(),
                p.isPurchaseable(),
                p.isSalable(),
                p.isVirtual(),
                p.getMetadata(),
                params,
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    private PartParameterView toParameterView(PartParameter pp, ParameterTemplate t) {
        return new PartParameterView(
                pp.getId(),
                pp.getPartId(),
                pp.getTemplateId(),
                t != null ? t.getName() : "Unknown",
                t != null ? t.getUnits() : null,
                t != null && t.isCheckbox(),
                pp.getValueText(),
                pp.getValueNumeric(),
                pp.getUpdatedAt()
        );
    }
}
