package com.ims.collaboration.api;

import com.ims.collaboration.application.NoteService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {

    public record CreateNotePayload(
            @NotBlank String entityType,
            @NotNull UUID entityId,
            @NotBlank String content
    ) {}

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @GetMapping
    public List<NoteService.NoteView> listNotes(@RequestParam String entityType, @RequestParam UUID entityId) {
        return noteService.listNotes(entityType, entityId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteService.NoteView createNote(@Valid @RequestBody CreateNotePayload payload) {
        return noteService.createNote(payload.entityType(), payload.entityId(), payload.content());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable UUID id) {
        noteService.deleteNote(id);
    }
}
