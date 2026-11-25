package com.ims.stock.application.adapter;

import com.ims.stock.application.*;

import com.ims.collaboration.spi.ImportTargetHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StockImportHandler implements ImportTargetHandler {

    private final StockService stockService;

    public StockImportHandler(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public boolean supports(String targetType) {
        return "STOCK".equalsIgnoreCase(targetType) || "STOCK_ITEM".equalsIgnoreCase(targetType);
    }

    @Override
    public boolean commitRow(Map<String, String> mappedRow) {
        return stockService.commitImportedStockRow(mappedRow);
    }
}
