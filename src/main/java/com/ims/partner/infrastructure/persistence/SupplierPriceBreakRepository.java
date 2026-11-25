package com.ims.partner.infrastructure.persistence;

import com.ims.partner.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SupplierPriceBreakRepository extends JpaRepository<SupplierPriceBreak, UUID> {

    List<SupplierPriceBreak> findBySupplierPartIdOrderByMinQuantityAsc(UUID supplierPartId);

    List<SupplierPriceBreak> findBySupplierPartIdInOrderByMinQuantityAsc(Collection<UUID> supplierPartIds);
}
