package com.ims.collaboration.application;

import com.ims.collaboration.spi.SearchContributor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class GlobalSearchService {

    private final List<SearchContributor> contributors;

    public GlobalSearchService(List<SearchContributor> contributors) {
        this.contributors = contributors;
    }

    @Transactional(readOnly = true)
    public List<SearchContributor.SearchHit> search(String q) {
        String trimmed = q == null ? "" : q.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<SearchContributor.SearchHit> results = new ArrayList<>();
        for (SearchContributor contributor : contributors) {
            results.addAll(contributor.search(trimmed));
        }
        return results;
    }
}
