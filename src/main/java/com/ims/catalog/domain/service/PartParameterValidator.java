package com.ims.catalog.domain.service;

import com.ims.catalog.domain.model.ParameterTemplate;

import org.springframework.stereotype.Component;
import com.ims.shared.exception.*;
import tech.units.indriya.quantity.Quantities;
import tech.units.indriya.unit.Units;

import javax.measure.MetricPrefix;
import javax.measure.Quantity;
import javax.measure.Unit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates part parameter values and normalizes physical quantities into SI base values
 * using JSR-385 Indriya (javax.measure) so range queries use the B-tree index on value_numeric.
 */
@Component
public class PartParameterValidator {

    public record ValidatedParameter(String normalizedText, Double numericValue) {}

    private static final Pattern QUANTITY_PATTERN = Pattern.compile(
            "^\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([^\\d\\s+-].*)?$"
    );

    private static final Map<String, Unit<?>> UNIT_REGISTRY = buildUnitRegistry();

    private static Map<String, Unit<?>> buildUnitRegistry() {
        Map<String, Unit<?>> map = new LinkedHashMap<>();

        // Base SI units & common engineering aliases
        registerWithMetricPrefixes(map, "F", Units.FARAD, "F", "farad", "farads");
        registerWithMetricPrefixes(map, "Ohm", Units.OHM, "Ohm", "ohm", "ohms", "ÃŽÂ©", "R");
        registerWithMetricPrefixes(map, "H", Units.HENRY, "H", "henry");
        registerWithMetricPrefixes(map, "A", Units.AMPERE, "A", "amp", "amps", "ampere");
        registerWithMetricPrefixes(map, "V", Units.VOLT, "V", "volt", "volts");
        registerWithMetricPrefixes(map, "W", Units.WATT, "W", "watt", "watts");
        registerWithMetricPrefixes(map, "Hz", Units.HERTZ, "Hz", "hz", "hertz");
        registerWithMetricPrefixes(map, "m", Units.METRE, "m", "meter", "metre");
        registerWithMetricPrefixes(map, "g", Units.GRAM, "g", "gram", "grams");
        registerWithMetricPrefixes(map, "s", Units.SECOND, "s", "sec", "second");
        registerWithMetricPrefixes(map, "N", Units.NEWTON, "N", "newton");
        registerWithMetricPrefixes(map, "Pa", Units.PASCAL, "Pa", "pascal");

        // Temperature
        map.put("K", Units.KELVIN);
        map.put("C", Units.CELSIUS);
        map.put("Ã‚Â°C", Units.CELSIUS);
        map.put("degC", Units.CELSIUS);

        // Dimensionless / ratios
        map.put("%", Units.PERCENT);
        map.put("pct", Units.PERCENT);

        return map;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerWithMetricPrefixes(Map<String, Unit<?>> map, String canonicalSymbol, Unit baseUnit, String... aliases) {
        for (String alias : aliases) {
            map.put(alias, baseUnit);
            map.put("f" + alias, MetricPrefix.FEMTO(baseUnit));
            map.put("p" + alias, MetricPrefix.PICO(baseUnit));
            map.put("n" + alias, MetricPrefix.NANO(baseUnit));
            map.put("u" + alias, MetricPrefix.MICRO(baseUnit));
            map.put("Ã‚Âµ" + alias, MetricPrefix.MICRO(baseUnit));
            map.put("ÃŽÂ¼" + alias, MetricPrefix.MICRO(baseUnit));
            map.put("m" + alias, MetricPrefix.MILLI(baseUnit));
            map.put("c" + alias, MetricPrefix.CENTI(baseUnit));
            map.put("k" + alias, MetricPrefix.KILO(baseUnit));
            map.put("K" + alias, MetricPrefix.KILO(baseUnit));
            map.put("M" + alias, MetricPrefix.MEGA(baseUnit));
            map.put("G" + alias, MetricPrefix.GIGA(baseUnit));
            map.put("T" + alias, MetricPrefix.TERA(baseUnit));
        }
    }

    /**
     * Validates a template's unit definition (if present).
     */
    public void validateTemplateUnits(String units) {
        if (units == null || units.isBlank()) {
            return;
        }
        String trimmed = units.trim();
        if (resolveUnit(trimmed) == null) {
            throw new ValidationException("Unsupported physical unit '" + trimmed + "'. Supported SI/engineering units include F, Ohm, V, A, W, Hz, H, m, g, s, C, K, % (with metric prefixes p, n, u, m, k, M, G)."
            );
        }
    }

    /**
     * Validates a raw parameter value against its ParameterTemplate and computes its SI-normalized numeric value.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ValidatedParameter validateAndNormalize(ParameterTemplate template, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new ValidationException("Parameter value must not be blank");
        }
        String trimmed = rawValue.trim();

        // 1. Checkbox boolean template (normalize true/false if recognized, otherwise allow free text)
        if (template.isCheckbox()) {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.equals("true") || lower.equals("1") || lower.equals("yes")) {
                return new ValidatedParameter("true", 1.0);
            }
            if (lower.equals("false") || lower.equals("0") || lower.equals("no")) {
                return new ValidatedParameter("false", 0.0);
            }
            return new ValidatedParameter(trimmed, tryParseDouble(trimmed));
        }

        // 2. Unit-aware template (best-effort Indriya JSR-385 normalization; falls back to free text)
        if (template.getUnits() != null && !template.getUnits().isBlank()) {
            Unit templateUnit = resolveUnit(template.getUnits().trim());
            Matcher matcher = QUANTITY_PATTERN.matcher(trimmed);
            if (templateUnit != null && matcher.matches()) {
                double number = Double.parseDouble(matcher.group(1));
                String unitPart = matcher.group(2) != null ? matcher.group(2).trim() : "";

                Unit inputUnit = unitPart.isEmpty() ? templateUnit : resolveUnit(unitPart);
                String displayUnit = unitPart.isEmpty() ? template.getUnits().trim() : unitPart;
                if (inputUnit != null && inputUnit.isCompatible(templateUnit)) {
                    Quantity quantity = Quantities.getQuantity(number, inputUnit);
                    Unit systemUnit = templateUnit.getSystemUnit();
                    Quantity siQuantity = quantity.to(systemUnit);
                    double siValue = siQuantity.getValue().doubleValue();
                    return new ValidatedParameter(matcher.group(1) + " " + displayUnit, siValue);
                }
            }
        }

        // 3. Free-form text or numeric value (choices are treated as suggestions, not strict restrictions)
        Matcher matcher = QUANTITY_PATTERN.matcher(trimmed);
        if (matcher.matches() && (matcher.group(2) == null || matcher.group(2).isBlank())) {
            return new ValidatedParameter(trimmed, Double.parseDouble(matcher.group(1)));
        }
        return new ValidatedParameter(trimmed, tryParseDouble(trimmed));
    }

    /**
     * Parses a user-supplied range filter bound (e.g. "1uF", "10 uF", or "0.000001") into its SI-normalized value.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Double parseRangeBound(ParameterTemplate template, String boundValue) {
        if (boundValue == null || boundValue.isBlank()) {
            return null;
        }
        String trimmed = boundValue.trim();
        if (template != null && template.getUnits() != null && !template.getUnits().isBlank()) {
            Unit templateUnit = resolveUnit(template.getUnits().trim());
            if (templateUnit != null) {
                Matcher matcher = QUANTITY_PATTERN.matcher(trimmed);
                if (!matcher.matches()) {
                    throw new ValidationException("Invalid range bound '" + trimmed + "'");
                }
                double number = Double.parseDouble(matcher.group(1));
                String unitPart = matcher.group(2) != null ? matcher.group(2).trim() : "";
                Unit inputUnit = unitPart.isEmpty() ? templateUnit : resolveUnit(unitPart);
                if (inputUnit == null || !inputUnit.isCompatible(templateUnit)) {
                    throw new ValidationException("Range filter unit '" + unitPart + "' is incompatible with '" + template.getUnits() + "'"
                    );
                }
                Quantity q = Quantities.getQuantity(number, inputUnit);
                return q.to(templateUnit.getSystemUnit()).getValue().doubleValue();
            }
        }
        Double d = tryParseDouble(trimmed);
        if (d == null) {
            throw new ValidationException("Range bound '" + trimmed + "' must be numeric");
        }
        return d;
    }

    private Unit<?> resolveUnit(String symbol) {
        Unit<?> exact = UNIT_REGISTRY.get(symbol);
        if (exact != null) {
            return exact;
        }
        // Case-insensitive fallback ONLY when prefix case is unambiguous (avoid m vs M confusion)
        for (Map.Entry<String, Unit<?>> entry : UNIT_REGISTRY.entrySet()) {
            if (entry.getKey().length() > 2 && entry.getKey().equalsIgnoreCase(symbol)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Double tryParseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
