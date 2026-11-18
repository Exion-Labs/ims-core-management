package com.ims.part;

import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiresFeature(Feature.PARTS)
public class PartCategoryService {

    public record CategoryView(
            UUID id,
            String name,
            String description,
            UUID parentId,
            String path,
            String pathString,
            int depth,
            boolean structural,
            UUID defaultLocationId,
            String icon,
            long directPartCount,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateCategoryCommand(
            String name,
            String description,
            UUID parentId,
            Boolean structural,
            UUID defaultLocationId,
            String icon
    ) {}

    public record UpdateCategoryCommand(
            String name,
            String description,
            Boolean structural,
            UUID defaultLocationId,
            String icon
    ) {}

    private final PartCategoryRepository categoryRepository;
    private final PartRepository partRepository;

    public PartCategoryService(PartCategoryRepository categoryRepository, PartRepository partRepository) {
        this.categoryRepository = categoryRepository;
        this.partRepository = partRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> listCategories() {
        List<PartCategory> all = categoryRepository.findAllByOrderByPathStringAsc();
        return all.stream()
                .map(c -> toView(c, partRepository.countByCategoryId(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryView getCategory(UUID id) {
        PartCategory category = requireCategory(id);
        return toView(category, partRepository.countByCategoryId(category.getId()));
    }

    @Transactional
    public CategoryView createCategory(CreateCategoryCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category name is required");
        }
        String trimmedName = cmd.name().trim();

        PartCategory parent = null;
        if (cmd.parentId() != null) {
            parent = requireCategory(cmd.parentId());
        }

        PartCategory category = new PartCategory();
        category.setName(trimmedName);
        category.setDescription(cmd.description());
        category.setParentId(parent != null ? parent.getId() : null);
        category.setStructural(Boolean.TRUE.equals(cmd.structural()));
        category.setDefaultLocationId(cmd.defaultLocationId());
        category.setIcon(cmd.icon());

        String label = PartCategory.compactLabelFor(category.getId());
        if (parent != null) {
            category.setPath(parent.getPath() + "." + label);
            category.setPathString(parent.getPathString() + " / " + trimmedName);
        } else {
            category.setPath(label);
            category.setPathString(trimmedName);
        }

        PartCategory saved = categoryRepository.save(category);
        return toView(saved, 0L);
    }

    @Transactional
    public CategoryView updateCategory(UUID id, UpdateCategoryCommand cmd) {
        PartCategory category = requireCategory(id);

        if (Boolean.TRUE.equals(cmd.structural()) && !category.isStructural()) {
            if (partRepository.existsByCategoryId(id)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cannot mark category as structural while parts are directly assigned to it"
                );
            }
        }

        boolean nameChanged = false;
        if (cmd.name() != null && !cmd.name().isBlank()) {
            String newName = cmd.name().trim();
            if (!newName.equals(category.getName())) {
                category.setName(newName);
                nameChanged = true;
            }
        }
        if (cmd.description() != null) {
            category.setDescription(cmd.description());
        }
        if (cmd.structural() != null) {
            category.setStructural(cmd.structural());
        }
        if (cmd.defaultLocationId() != null) {
            category.setDefaultLocationId(cmd.defaultLocationId());
        }
        if (cmd.icon() != null) {
            category.setIcon(cmd.icon());
        }

        PartCategory saved = categoryRepository.saveAndFlush(category);
        if (nameChanged) {
            recomputeSubtreePathStrings(saved.getPath());
            saved = requireCategory(id);
        }
        return toView(saved, partRepository.countByCategoryId(id));
    }

    /**
     * Moves a category and all of its descendants under a new parent (or to root when newParentId is null)
     * using a single HQL bulk update with registered ltree_concat, subpath, and ltree_descendant functions.
     */
    @Transactional
    public CategoryView moveSubtree(UUID categoryId, UUID newParentId) {
        PartCategory category = requireCategory(categoryId);
        if (newParentId != null && newParentId.equals(categoryId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot move a category under itself");
        }

        String oldPath = category.getPath();
        int oldDepth = oldPath.split("\\.").length - 1;

        PartCategory newParent = null;
        String newPrefix = "";
        if (newParentId != null) {
            newParent = requireCategory(newParentId);
            if (newParent.getPath().equals(oldPath) || newParent.getPath().startsWith(oldPath + ".")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Cannot move a category into one of its own descendants"
                );
            }
            newPrefix = newParent.getPath();
        }

        category.setParentId(newParentId);
        categoryRepository.saveAndFlush(category);

        // HQL bulk update using registered ltree_concat, subpath, and ltree_descendant functions
        categoryRepository.moveSubtreePaths(newPrefix, oldDepth, oldPath);

        PartCategory movedRoot = requireCategory(categoryId);
        recomputeSubtreePathStrings(movedRoot.getPath());
        movedRoot = requireCategory(categoryId);
        return toView(movedRoot, partRepository.countByCategoryId(categoryId));
    }

    /**
     * Deletes a category using either REPARENT (reassigns direct child categories & parts to parent)
     * or CASCADE (deletes all descendant categories and unassigns parts).
     */
    @Transactional
    public void deleteCategory(UUID categoryId, String mode) {
        PartCategory category = requireCategory(categoryId);
        boolean cascade = "CASCADE".equalsIgnoreCase(mode);

        if (!cascade) {
            UUID parentId = category.getParentId();
            if (parentId != null) {
                PartCategory parent = requireCategory(parentId);
                if (parent.isStructural() && partRepository.existsByCategoryId(categoryId)) {
                    // Unassign parts to null rather than violating structural parent rule
                    partRepository.reassignCategory(categoryId, null);
                } else {
                    partRepository.reassignCategory(categoryId, parentId);
                }
            } else {
                partRepository.reassignCategory(categoryId, null);
            }

            List<PartCategory> directChildren = categoryRepository.findByParentIdOrderByNameAsc(categoryId);
            for (PartCategory child : directChildren) {
                moveSubtree(child.getId(), parentId);
            }
            PartCategory reloaded = requireCategory(categoryId);
            categoryRepository.delete(reloaded);
        } else {
            List<PartCategory> subtree = categoryRepository.findSubtree(category.getPath());
            // Delete from deepest to shallowest so composite FK (tenant_id, parent_id) is satisfied
            subtree.sort(Comparator.comparingInt((PartCategory c) -> c.getPath().split("\\.").length).reversed());
            for (PartCategory node : subtree) {
                partRepository.reassignCategory(node.getId(), null);
                categoryRepository.delete(node);
            }
        }
    }

    @Transactional(readOnly = true)
    public PartCategory requireCategory(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Part category not found: " + id));
    }

    private void recomputeSubtreePathStrings(String rootPath) {
        List<PartCategory> allCategories = categoryRepository.findAllByOrderByPathStringAsc();
        Map<UUID, PartCategory> byId = new HashMap<>();
        for (PartCategory c : allCategories) {
            byId.put(c.getId(), c);
        }

        List<PartCategory> subtree = categoryRepository.findSubtree(rootPath);
        subtree.sort(Comparator.comparingInt(c -> c.getPath().split("\\.").length));
        for (PartCategory node : subtree) {
            if (node.getParentId() == null) {
                node.setPathString(node.getName());
            } else {
                PartCategory parent = byId.get(node.getParentId());
                String parentPathString = parent != null ? parent.getPathString() : "";
                node.setPathString(parentPathString.isEmpty() ? node.getName() : parentPathString + " / " + node.getName());
            }
            byId.put(node.getId(), categoryRepository.save(node));
        }
        categoryRepository.flush();
    }

    public static CategoryView toView(PartCategory c, long directPartCount) {
        int depth = c.getPath() == null || c.getPath().isBlank() ? 0 : c.getPath().split("\\.").length - 1;
        return new CategoryView(
                c.getId(),
                c.getName(),
                c.getDescription(),
                c.getParentId(),
                c.getPath(),
                c.getPathString(),
                depth,
                c.isStructural(),
                c.getDefaultLocationId(),
                c.getIcon(),
                directPartCount,
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
