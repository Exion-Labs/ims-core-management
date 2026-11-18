package com.ims.common.sequence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Generates atomic, gap-free per-tenant reference codes (PO-0001, BO-0001, SO-0001, RMA-0001, TO-0001)
 * by acquiring a JPA PESSIMISTIC_WRITE row lock.
 */
@Service
public class ReferenceSequenceService {

    private final ReferenceSequenceRepository repository;

    public ReferenceSequenceService(ReferenceSequenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public String nextReference(String sequenceKey) {
        ReferenceSequence seq = repository.findBySequenceKeyForUpdate(sequenceKey)
                .orElseGet(() -> {
                    ReferenceSequence created = new ReferenceSequence();
                    created.setSequenceKey(sequenceKey);
                    created.setPrefix(sequenceKey + "-");
                    created.setPadding(4);
                    created.setNextValue(1L);
                    return repository.saveAndFlush(created);
                });
        String ref = seq.formatCurrentAndIncrement();
        repository.save(seq);
        return ref;
    }

    @Transactional(readOnly = true)
    public List<ReferenceSequence> listAll() {
        return repository.findAllByOrderBySequenceKeyAsc();
    }

    @Transactional
    public ReferenceSequence updateFormat(String sequenceKey, String prefix, int padding, long nextValue) {
        ReferenceSequence seq = repository.findBySequenceKeyForUpdate(sequenceKey)
                .orElseGet(() -> {
                    ReferenceSequence created = new ReferenceSequence();
                    created.setSequenceKey(sequenceKey);
                    return created;
                });
        seq.setPrefix(prefix);
        seq.setPadding(padding);
        seq.setNextValue(Math.max(1L, nextValue));
        return repository.save(seq);
    }

    @Transactional
    public void seedDefaultsIfEmpty() {
        String[][] defaults = {
                {"PO", "PO-"},
                {"BO", "BO-"},
                {"SO", "SO-"},
                {"RMA", "RMA-"},
                {"TO", "TO-"}
        };
        for (String[] def : defaults) {
            if (repository.findBySequenceKey(def[0]).isEmpty()) {
                ReferenceSequence seq = new ReferenceSequence();
                seq.setSequenceKey(def[0]);
                seq.setPrefix(def[1]);
                seq.setPadding(4);
                seq.setNextValue(1L);
                repository.save(seq);
            }
        }
    }
}
