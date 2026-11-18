package com.ims.common.note;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NoteRepository extends JpaRepository<Note, UUID> {

    List<Note> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId);

    @Query("""
            SELECT n FROM Note n
            WHERE trgm_match(n.content, :query) = true
               OR LOWER(n.content) LIKE LOWER(CONCAT('%', :query, '%'))
            ORDER BY similarity(n.content, :query) DESC
            """)
    List<Note> searchByTrigram(@Param("query") String query);
}
