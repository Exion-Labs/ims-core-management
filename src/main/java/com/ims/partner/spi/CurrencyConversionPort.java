package com.ims.partner.spi;

import java.math.BigDecimal;

/**
 * Public port for tenant base currency lookup and FX conversion.
 */
public interface CurrencyConversionPort {

    String getTenantBaseCurrency();

    BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency);
}
