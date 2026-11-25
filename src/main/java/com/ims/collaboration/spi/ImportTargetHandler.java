package com.ims.collaboration.spi;

import java.util.List;
import java.util.Map;

/**
 * SPI implemented by domain modules to validate and commit tabular import rows for a target entity type.
 */
public interface ImportTargetHandler {

    boolean supports(String targetType);

    default List<ImportModels.RowValidationError> validateRow(Map<String, String> mappedRow) {
        return List.of();
    }

    boolean commitRow(Map<String, String> mappedRow);
}
