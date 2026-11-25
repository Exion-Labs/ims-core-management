package com.ims.catalog.infrastructure.adapter;

import com.ims.catalog.application.*;
import com.ims.catalog.domain.model.*;

import com.ims.collaboration.spi.ImportModels;
import com.ims.collaboration.spi.ImportTargetHandler;
import com.ims.collaboration.spi.SearchContributor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PartCollaborationAdapter implements SearchContributor {

    private final PartService partService;

    public PartCollaborationAdapter(PartService partService) {
        this.partService = partService;
    }

    @Override
    public List<SearchHit> search(String query) {
        List<SearchHit> hits = new ArrayList<>();
        for (Part p : partService.searchPartsByKeyword(query, 8)) {
            hits.add(new SearchHit(
                    "Part",
                    p.getId().toString(),
                    p.getIpn() + " Â· " + p.getName(),
                    "Rev " + p.getRevision() + " Â· Unit: " + p.getUnit(),
                    "/parts/" + p.getId()
            ));
        }
        for (PartCategory c : partService.searchCategoriesByKeyword(query, 5)) {
            hits.add(new SearchHit(
                    "Category",
                    c.getId().toString(),
                    c.getName(),
                    c.getPathString(),
                    "/parts?categoryId=" + c.getId()
            ));
        }
        return hits;
    }

    @Component
    public static class PartImportHandler implements ImportTargetHandler {
        private final PartService partService;

        public PartImportHandler(PartService partService) {
            this.partService = partService;
        }

        @Override
        public boolean supports(String targetType) {
            return "PART".equalsIgnoreCase(targetType)
                    || "PARTS".equalsIgnoreCase(targetType)
                    || "PartCatalogPreview".equalsIgnoreCase(targetType);
        }

        @Override
        public List<ImportModels.RowValidationError> validateRow(Map<String, String> mappedRow) {
            return partService.validatePartImportRow(mappedRow);
        }

        @Override
        public boolean commitRow(Map<String, String> mappedRow) {
            return partService.commitImportedPartRow(mappedRow);
        }
    }

    @Component
    public static class PartParameterImportHandler implements ImportTargetHandler {
        private final PartService partService;

        public PartParameterImportHandler(PartService partService) {
            this.partService = partService;
        }

        @Override
        public boolean supports(String targetType) {
            return "PART_PARAMETER".equalsIgnoreCase(targetType);
        }

        @Override
        public boolean commitRow(Map<String, String> mappedRow) {
            return partService.commitImportedPartParameterRow(mappedRow);
        }
    }

    @Component
    public static class BomImportHandler implements ImportTargetHandler {
        private final BomService bomService;

        public BomImportHandler(BomService bomService) {
            this.bomService = bomService;
        }

        @Override
        public boolean supports(String targetType) {
            return "BOM".equalsIgnoreCase(targetType) || "BOM_ITEM".equalsIgnoreCase(targetType);
        }

        @Override
        public boolean commitRow(Map<String, String> mappedRow) {
            return bomService.commitImportedBomRow(mappedRow);
        }
    }
}
