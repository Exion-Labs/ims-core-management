package com.ims.common.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class NoteAndSearchController {

    public record CreateNotePayload(
            @NotBlank String entityType,
            @NotNull UUID entityId,
            @NotBlank String content
    ) {}

    public record SearchResultItem(
            String category,
            String id,
            String title,
            String subtitle,
            String href
    ) {}

    private final NoteRepository noteRepository;

    public NoteAndSearchController(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    @GetMapping("/notes")
    public List<Note> listNotes(@RequestParam String entityType, @RequestParam UUID entityId) {
        return noteRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public Note createNote(@Valid @RequestBody CreateNotePayload payload) {
        Note note = new Note();
        note.setEntityType(payload.entityType());
        note.setEntityId(payload.entityId());
        note.setContent(payload.content());
        return noteRepository.save(note);
    }

    @DeleteMapping("/notes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable UUID id) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found"));
        noteRepository.delete(note);
    }

    @GetMapping("/search")
    public List<SearchResultItem> globalCommandPaletteSearch(@RequestParam(defaultValue = "") String q) {
        String trimmed = q.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return noteRepository.searchByTrigram(trimmed).stream()
                .limit(10)
                .map(n -> new SearchResultItem(
                        "Note (" + n.getEntityType() + ")",
                        n.getId().toString(),
                        n.getContent().length() > 80 ? n.getContent().substring(0, 80) + "…" : n.getContent(),
                        n.getEntityType() + " · " + n.getEntityId(),
                        "/settings"
                ))
                .toList();
    }
}
