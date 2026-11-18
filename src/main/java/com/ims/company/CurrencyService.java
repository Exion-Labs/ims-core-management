package com.ims.company;

import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.tenant.TenantManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CurrencyService {

    private static final Map<String, BigDecimal> DEFAULT_USD_RATES = Map.of(
            "USD", BigDecimal.ONE,
            "EUR", new BigDecimal("0.920000"),
            "GBP", new BigDecimal("0.790000"),
            "JPY", new BigDecimal("152.500000"),
            "CAD", new BigDecimal("1.380000"),
            "CHF", new BigDecimal("0.880000"),
            "AUD", new BigDecimal("1.520000"),
            "SGD", new BigDecimal("1.340000")
    );

    private final ExchangeRateRepository exchangeRateRepository;
    private final TenantManagementService tenantManagementService;

    public CurrencyService(
            ExchangeRateRepository exchangeRateRepository,
            TenantManagementService tenantManagementService
    ) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.tenantManagementService = tenantManagementService;
    }

    @Transactional
    public void ensureDefaultRatesSeeded() {
        for (Map.Entry<String, BigDecimal> entry : DEFAULT_USD_RATES.entrySet()) {
            if (exchangeRateRepository.findByBaseCurrencyAndTargetCurrency("USD", entry.getKey()).isEmpty()) {
                ExchangeRate er = new ExchangeRate();
                er.setBaseCurrency("USD");
                er.setTargetCurrency(entry.getKey());
                er.setRate(entry.getValue());
                er.setUpdatedAt(Instant.now());
                exchangeRateRepository.save(er);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ExchangeRate> listExchangeRates() {
        return exchangeRateRepository.findAllByOrderByTargetCurrencyAsc();
    }

    @Transactional
    @RequiresFeature(Feature.MULTI_CURRENCY)
    public ExchangeRate upsertExchangeRate(String targetCurrency, BigDecimal rate) {
        if (targetCurrency == null || targetCurrency.trim().length() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Currency code must be a 3-letter ISO code");
        }
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exchange rate must be positive");
        }
        String upperTarget = targetCurrency.trim().toUpperCase(Locale.ROOT);
        ExchangeRate er = exchangeRateRepository.findByBaseCurrencyAndTargetCurrency("USD", upperTarget)
                .orElseGet(() -> {
                    ExchangeRate created = new ExchangeRate();
                    created.setBaseCurrency("USD");
                    created.setTargetCurrency(upperTarget);
                    return created;
                });
        er.setRate(rate);
        er.setUpdatedAt(Instant.now());
        return exchangeRateRepository.saveAndFlush(er);
    }

    @Transactional(readOnly = true)
    public String getTenantBaseCurrency() {
        return tenantManagementService.getCurrentTenantBaseCurrency();
    }

    /**
     * Converts an amount from fromCurrency to toCurrency at read/rollup time using USD as pivot currency.
     */
    @Transactional(readOnly = true)
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        String from = (fromCurrency == null || fromCurrency.isBlank()) ? "USD" : fromCurrency.trim().toUpperCase(Locale.ROOT);
        String to = (toCurrency == null || toCurrency.isBlank()) ? "USD" : toCurrency.trim().toUpperCase(Locale.ROOT);
        if (from.equals(to)) {
            return amount;
        }
        BigDecimal fromRate = rateFromUsd(from);
        BigDecimal toRate = rateFromUsd(to);
        BigDecimal inUsd = amount.divide(fromRate, 8, RoundingMode.HALF_UP);
        return inUsd.multiply(toRate).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal rateFromUsd(String currency) {
        if ("USD".equals(currency)) {
            return BigDecimal.ONE;
        }
        return exchangeRateRepository.findByBaseCurrencyAndTargetCurrency("USD", currency)
                .map(ExchangeRate::getRate)
                .orElseGet(() -> DEFAULT_USD_RATES.getOrDefault(currency, BigDecimal.ONE));
    }
}
