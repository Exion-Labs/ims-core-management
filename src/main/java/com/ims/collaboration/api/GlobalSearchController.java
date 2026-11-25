package com.ims.collaboration.api;

import com.ims.collaboration.application.GlobalSearchService;
import com.ims.collaboration.spi.SearchContributor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
public class GlobalSearchController {

    private final GlobalSearchService searchService;

    public GlobalSearchController(GlobalSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public List<SearchContributor.SearchHit> globalCommandPaletteSearch(@RequestParam(defaultValue = "") String q) {
        return searchService.search(q);
    }
}
