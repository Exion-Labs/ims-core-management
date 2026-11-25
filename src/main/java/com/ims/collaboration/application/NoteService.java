package com.ims.collaboration.application;

import com.ims.collaboration.domain.model.Note;
import com.ims.collaboration.infrastructure.persistence.NoteRepository;
import com.ims.collaboration.spi.SearchContributor;
import com.ims.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NoteService implements SearchContributor {

    public record NoteView(
            UUID id,
            String entityType,
            UUID entityId,
            String content,
            Instant createdAt,
            Instant updatedAt
    ) {}

    private final NoteRepository noteRepository;

    public NoteService(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    @Transactional(readOnly = true)
    public List<NoteView> listNotes(String entityType, UUID entityId) {
        return noteRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public NoteView createNote(String entityType, UUID entityId, String content) {
        Note note = new Note();
        note.setEntityType(entityType);
        note.setEntityId(entityId);
        note.setContent(content);
        return toView(noteRepository.save(note));
    }

    @Transactional
    public void deleteNote(UUID id) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Note not found"));
        noteRepository.delete(note);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchHit> search(String query) {
        return noteRepository.searchByTrigram(query).stream()
                .limit(5)
                .map(n -> {
                    String href = "Part".equalsIgnoreCase(n.getEntityType())
                            ? "/parts/" + n.getEntityId()
                            : "/settings";
                    String preview = n.getContent().length() > 80
                            ? n.getContent().substring(0, 80) + "…"
                            : n.getContent();
                    return new SearchHit(
                            "Note (" + n.getEntityType() + ")",
                            n.getId().toString(),
                            preview,
                            n.getEntityType() + " · " + n.getEntityId(),
                            href
                    );
                })
                .toList();
    }

    private NoteView toView(Note note) {
        return new NoteView(
                note.getId(),
                note.getEntityType(),
                note.getEntityId(),
                note.getContent(),
                note.getCreatedAt(),
                note.getUpdatedAt()
        );
    }
}
