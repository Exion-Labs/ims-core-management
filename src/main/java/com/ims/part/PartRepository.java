package com.ims.part;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartRepository extends JpaRepository<Part, UUID>, JpaSpecificationExecutor<Part> {

    Optional<Part> findByIpnAndRevision(String ipn, String revision);

    boolean existsByIpnAndRevision(String ipn, String revision);

    boolean existsByCategoryId(UUID categoryId);

    long countByCategoryId(UUID categoryId);

    List<Part> findByVariantOfIdOrderByIpnAsc(UUID variantOfId);

    List<Part> findByRevisionOfIdOrderByRevisionAsc(UUID revisionOfId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Part p set p.categoryId = :newCategoryId where p.categoryId = :oldCategoryId")
    int reassignCategory(@Param("oldCategoryId") UUID oldCategoryId, @Param("newCategoryId") UUID newCategoryId);

    @Query("select p from Part p where lower(p.ipn) like lower(concat('%', :q, '%')) or lower(p.name) like lower(concat('%', :q, '%')) or trgm_match(p.name, :q) = true order by similarity(p.name, :q) desc")
    List<Part> searchByKeyword(@Param("q") String q);
}
