package com.ims.partner.infrastructure.adapter;

import com.ims.partner.application.CompanyService;

import com.ims.collaboration.spi.ImportTargetHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CompanyImportHandler implements ImportTargetHandler {

    private final CompanyService companyService;

    public CompanyImportHandler(CompanyService companyService) {
        this.companyService = companyService;
    }

    @Override
    public boolean supports(String targetType) {
        return "SUPPLIER_PART".equalsIgnoreCase(targetType);
    }

    @Override
    public boolean commitRow(Map<String, String> mappedRow) {
        return companyService.commitImportedSupplierPartRow(mappedRow);
    }
}
