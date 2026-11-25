package com.ims.stock.infrastructure.persistence;

import com.ims.stock.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockLocationRepository extends JpaRepository<StockLocation, UUID> {

    List<StockLocation> findAllByOrderByPathStringAsc();

    List<StockLocation> findByParentIdOrderByNameAsc(UUID parentId);

    Optional<StockLocation> findByCustomBarcode(String customBarcode);

    @Query("select l from StockLocation l where ltree_descendant(l.path, :prefixPath) = true order by l.path asc")
    List<StockLocation> findSubtree(@Param("prefixPath") String prefixPath);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StockLocation l set l.path = ltree_concat(:newPrefix, subpath(l.path, :oldDepth)) where ltree_descendant(l.path, :oldPath) = true")
    int moveSubtreePaths(
            @Param("newPrefix") String newPrefix,
            @Param("oldDepth") int oldDepth,
            @Param("oldPath") String oldPath
    );

    @Query("select l from StockLocation l where lower(l.name) like lower(concat('%', :q, '%')) or lower(l.pathString) like lower(concat('%', :q, '%')) order by l.pathString asc")
    List<StockLocation> searchByKeyword(@Param("q") String q);
}
