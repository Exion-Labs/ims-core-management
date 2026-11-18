package com.ims.common.sequence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferenceSequenceRepository extends JpaRepository<ReferenceSequence, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReferenceSequence r WHERE r.sequenceKey = :sequenceKey")
    Optional<ReferenceSequence> findBySequenceKeyForUpdate(@Param("sequenceKey") String sequenceKey);

    Optional<ReferenceSequence> findBySequenceKey(String sequenceKey);

    List<ReferenceSequence> findAllByOrderBySequenceKeyAsc();
}
