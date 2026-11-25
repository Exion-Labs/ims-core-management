package com.ims.collaboration.spi;

import java.util.List;

/**
 * Public port for exporting tabular data to CSV and XLSX with formula-injection protection.
 */
public interface TabularExportPort {

    byte[] exportCsv(List<String> headers, List<List<String>> rows, String entityType);

    byte[] exportXlsx(String sheetName, List<String> headers, List<List<String>> rows, String entityType);
}
