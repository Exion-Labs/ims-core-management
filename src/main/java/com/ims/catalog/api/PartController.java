package com.ims.catalog.api;

import com.ims.catalog.application.*;

import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiresFeature(Feature.PARTS)
public class PartController {

    public record MoveCategoryPayload(UUID newParentId) {}

    private final PartCategoryService categoryService;
    private final PartService partService;

    public PartController(PartCategoryService categoryService, PartService partService) {
        this.categoryService = categoryService;
        this.partService = partService;
    }

    // =========================================================================
    // Part Categories (ltree)
    // =========================================================================

    @GetMapping("/part-categories")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public List<PartCategoryService.CategoryView> listCategories() {
        return categoryService.listCategories();
    }

    @GetMapping("/part-categories/{id}")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public PartCategoryService.CategoryView getCategory(@PathVariable UUID id) {
        return categoryService.getCategory(id);
    }

    @PostMapping("/part-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PART:CREATE')")
    public PartCategoryService.CategoryView createCategory(
            @Valid @RequestBody PartCategoryService.CreateCategoryCommand command
    ) {
        return categoryService.createCategory(command);
    }

    @PutMapping("/part-categories/{id}")
    @PreAuthorize("hasAuthority('PART:UPDATE')")
    public PartCategoryService.CategoryView updateCategory(
            @PathVariable UUID id,
            @Valid @RequestBody PartCategoryService.UpdateCategoryCommand command
    ) {
        return categoryService.updateCategory(id, command);
    }

    @PostMapping("/part-categories/{id}/move")
    @PreAuthorize("hasAuthority('PART:UPDATE')")
    public PartCategoryService.CategoryView moveCategorySubtree(
            @PathVariable UUID id,
            @RequestBody MoveCategoryPayload payload
    ) {
        return categoryService.moveSubtree(id, payload != null ? payload.newParentId() : null);
    }

    @DeleteMapping("/part-categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PART:DELETE')")
    public void deleteCategory(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "REPARENT") String mode
    ) {
        categoryService.deleteCategory(id, mode);
    }

    // =========================================================================
    // Parts Catalog
    // =========================================================================

    @GetMapping("/parts")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public Page<PartService.PartView> searchParts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "true") boolean includeSubcategories,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean assembly,
            @RequestParam(required = false) Boolean component,
            @RequestParam(required = false) Boolean trackable,
            @RequestParam(required = false) Boolean purchaseable,
            @RequestParam(required = false) Boolean salable,
            @RequestParam(required = false) Boolean virtual,
            @RequestParam(required = false) UUID variantOfId,
            @RequestParam(required = false) UUID revisionOfId,
            @RequestParam(required = false) UUID paramTemplateId,
            @RequestParam(required = false) String paramMin,
            @RequestParam(required = false) String paramMax,
            @RequestParam(required = false) String paramValueText,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        PartService.PartSearchFilter filter = new PartService.PartSearchFilter(
                q, categoryId, includeSubcategories, active, assembly, component,
                trackable, purchaseable, salable, virtual, variantOfId, revisionOfId,
                paramTemplateId, paramMin, paramMax, paramValueText
        );
        return partService.searchParts(
                filter,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.ASC, "ipn"))
        );
    }

    @GetMapping("/parts/{id}")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public PartService.PartView getPart(@PathVariable UUID id) {
        return partService.getPart(id);
    }

    @PostMapping("/parts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PART:CREATE')")
    public PartService.PartView createPart(@Valid @RequestBody PartService.CreatePartCommand command) {
        return partService.createPart(command);
    }

    @PutMapping("/parts/{id}")
    @PreAuthorize("hasAuthority('PART:UPDATE')")
    public PartService.PartView updatePart(
            @PathVariable UUID id,
            @Valid @RequestBody PartService.UpdatePartCommand command
    ) {
        return partService.updatePart(id, command);
    }

    @DeleteMapping("/parts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PART:DELETE')")
    public void deletePart(@PathVariable UUID id) {
        partService.deletePart(id);
    }

    @GetMapping("/parts/{id}/variants")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public List<PartService.PartView> listVariants(@PathVariable UUID id) {
        return partService.listVariants(id);
    }

    @GetMapping("/parts/{id}/revisions")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public List<PartService.PartView> listRevisions(@PathVariable UUID id) {
        return partService.listRevisions(id);
    }

    @GetMapping("/parts/export")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    @RequiresFeature(Feature.IMPORT_EXPORT)
    public ResponseEntity<byte[]> exportParts(@RequestParam(defaultValue = "csv") String format) {
        byte[] bytes = partService.exportParts(format);
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"parts-export." + (xlsx ? "xlsx" : "csv") + "\"")
                .contentType(xlsx
                        ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        : MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    // =========================================================================
    // Parameter Templates & Typed Part Parameters
    // =========================================================================

    @GetMapping("/parameter-templates")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public List<PartService.ParameterTemplateView> listParameterTemplates(
            @RequestParam(required = false) UUID categoryId
    ) {
        return partService.listParameterTemplates(categoryId);
    }

    @PostMapping("/parameter-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PART:CREATE')")
    public PartService.ParameterTemplateView createParameterTemplate(
            @Valid @RequestBody PartService.CreateParameterTemplateCommand command
    ) {
        return partService.createParameterTemplate(command);
    }

    @DeleteMapping("/parameter-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PART:DELETE')")
    public void deleteParameterTemplate(@PathVariable UUID id) {
        partService.deleteParameterTemplate(id);
    }

    @GetMapping("/parts/{id}/parameters")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public List<PartService.PartParameterView> listPartParameters(@PathVariable UUID id) {
        return partService.listPartParameters(id);
    }

    @PostMapping("/parts/{id}/parameters")
    @PreAuthorize("hasAuthority('PART:UPDATE')")
    public PartService.PartParameterView upsertPartParameter(
            @PathVariable UUID id,
            @Valid @RequestBody PartService.UpsertPartParameterCommand command
    ) {
        return partService.upsertPartParameter(id, command);
    }

    @DeleteMapping("/parts/{id}/parameters/{parameterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PART:UPDATE')")
    public void deletePartParameter(@PathVariable UUID id, @PathVariable UUID parameterId) {
        partService.deletePartParameter(id, parameterId);
    }
}
