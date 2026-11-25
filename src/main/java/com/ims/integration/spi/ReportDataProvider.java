package com.ims.integration.spi;

import java.util.Map;
import java.util.UUID;

/**
 * SPI implemented by domain modules to supply Handlebars template data models for PDF report rendering.
 */
public interface ReportDataProvider {

    String entityType();

    Map<String, Object> buildModel(UUID entityId);
}
