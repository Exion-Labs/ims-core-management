package com.ims.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManufacturerPartRepository extends JpaRepository<ManufacturerPart, UUID> {

    List<ManufacturerPart> findByPartIdOrderByMpnAsc(UUID partId);

    List<ManufacturerPart> findByManufacturerIdOrderByMpnAsc(UUID manufacturerId);

    Optional<ManufacturerPart> findByManufacturerIdAndMpnIgnoreCase(UUID manufacturerId, String mpn);

    boolean existsByManufacturerIdAndMpnIgnoreCase(UUID manufacturerId, String mpn);
}
