package com.ims.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierPartRepository extends JpaRepository<SupplierPart, UUID> {

    List<SupplierPart> findByPartIdOrderBySkuAsc(UUID partId);

    List<SupplierPart> findBySupplierIdOrderBySkuAsc(UUID supplierId);

    Optional<SupplierPart> findBySupplierIdAndSkuIgnoreCase(UUID supplierId, String sku);

    boolean existsBySupplierIdAndSkuIgnoreCase(UUID supplierId, String sku);

    Optional<SupplierPart> findByCustomBarcode(String customBarcode);
}
