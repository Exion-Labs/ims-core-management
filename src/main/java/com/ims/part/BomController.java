package com.ims.part;

import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiresFeature(Feature.MANUFACTURING)
public class BomController {

    public record AddSubstitutePayload(UUID partId) {}

    private final BomService bomService;

    public BomController(BomService bomService) {
        this.bomService = bomService;
    }

    @GetMapping("/parts/{partId}/bom")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public List<BomService.BomItemView> listBom(@PathVariable UUID partId) {
        return bomService.listBomItems(partId);
    }

    @GetMapping("/parts/{partId}/where-used")
    @PreAuthorize("hasAuthority('PART:VIEW')")
    public List<BomService.BomItemView> listWhereUsed(@PathVariable UUID partId) {
        return bomService.listWhereUsed(partId);
    }

    @PostMapping("/parts/{partId}/bom")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PART:UPDATE')")
    public BomService.BomItemView addBomItem(
            @PathVariable UUID partId,
            @Valid @RequestBody BomService.AddBomItemCommand command
    ) {
        return bomService.addBomItem(partId, command);
    }

    @DeleteMapping("/bom-items/{bomItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PART:UPDATE')")
    public void deleteBomItem(@PathVariable UUID bomItemId) {
        bomService.deleteBomItem(bomItemId);
    }

    @PostMapping("/bom-items/{bomItemId}/substitutes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PART:UPDATE')")
    public BomService.BomSubstituteView addSubstitute(
            @PathVariable UUID bomItemId,
            @RequestBody AddSubstitutePayload payload
    ) {
        return bomService.addSubstitute(bomItemId, payload.partId());
    }
}
