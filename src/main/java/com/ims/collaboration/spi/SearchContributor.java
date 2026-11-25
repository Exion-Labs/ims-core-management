package com.ims.collaboration.spi;

import java.util.List;

/**
 * SPI implemented by domain modules to contribute entries to the global command-palette search.
 */
public interface SearchContributor {

    record SearchHit(
            String category,
            String id,
            String title,
            String subtitle,
            String href
    ) {}

    List<SearchHit> search(String query);
}
