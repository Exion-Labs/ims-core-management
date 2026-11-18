package com.ims.part;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PartCategoryRepository extends JpaRepository<PartCategory, UUID> {

    List<PartCategory> findAllByOrderByPathStringAsc();

    List<PartCategory> findByParentIdOrderByNameAsc(UUID parentId);

    boolean existsByParentId(UUID parentId);

    @Query("select c from PartCategory c where ltree_descendant(c.path, :prefixPath) = true order by c.path asc")
    List<PartCategory> findSubtree(@Param("prefixPath") String prefixPath);

    @Query("select c from PartCategory c where ltree_ancestor(c.path, :childPath) = true order by c.path asc")
    List<PartCategory> findAncestors(@Param("childPath") String childPath);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PartCategory c set c.path = ltree_concat(:newPrefix, subpath(c.path, :oldDepth)) where ltree_descendant(c.path, :oldPath) = true")
    int moveSubtreePaths(
            @Param("newPrefix") String newPrefix,
            @Param("oldDepth") int oldDepth,
            @Param("oldPath") String oldPath
    );

    @Query("select c from PartCategory c where lower(c.name) like lower(concat('%', :q, '%')) or lower(c.pathString) like lower(concat('%', :q, '%')) order by c.pathString asc")
    List<PartCategory> searchByKeyword(@Param("q") String q);
}
