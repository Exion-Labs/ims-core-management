package com.ims.part;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
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
        registerWithMetricPrefixes(map, "Ohm", Units.OHM, "Ohm", "ohm", "ohms", "Ω", "R");
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
        map.put("°C", Units.CELSIUS);
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
            map.put("µ" + alias, MetricPrefix.MICRO(baseUnit));
            map.put("μ" + alias, MetricPrefix.MICRO(baseUnit));
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
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported physical unit '" + trimmed + "'. Supported SI/engineering units include F, Ohm, V, A, W, Hz, H, m, g, s, C, K, % (with metric prefixes p, n, u, m, k, M, G)."
            );
        }
    }

    /**
     * Validates a raw parameter value against its ParameterTemplate and computes its SI-normalized numeric value.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ValidatedParameter validateAndNormalize(ParameterTemplate template, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter value must not be blank");
        }
        String trimmed = rawValue.trim();

        // 1. Checkbox boolean template
        if (template.isCheckbox()) {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.equals("true") || lower.equals("1") || lower.equals("yes")) {
                return new ValidatedParameter("true", 1.0);
            }
            if (lower.equals("false") || lower.equals("0") || lower.equals("no")) {
                return new ValidatedParameter("false", 0.0);
            }
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Parameter '" + template.getName() + "' is a boolean checkbox; expected true or false"
            );
        }

        // 2. Choice-constrained template
        if (template.getChoices() != null && !template.getChoices().isBlank()) {
            List<String> allowed = Arrays.stream(template.getChoices().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            String matchedChoice = allowed.stream()
                    .filter(c -> c.equalsIgnoreCase(trimmed))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Value '" + trimmed + "' for parameter '" + template.getName() + "' must be one of: " + String.join(", ", allowed)
                    ));
            Double maybeNum = tryParseDouble(matchedChoice);
            return new ValidatedParameter(matchedChoice, maybeNum);
        }

        // 3. Unit-aware template (Indriya JSR-385 normalization)
        if (template.getUnits() != null && !template.getUnits().isBlank()) {
            Unit templateUnit = resolveUnit(template.getUnits().trim());
            if (templateUnit == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template unit '" + template.getUnits() + "' is not recognized");
            }

            Matcher matcher = QUANTITY_PATTERN.matcher(trimmed);
            if (!matcher.matches()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid numeric quantity '" + trimmed + "' for parameter '" + template.getName() + "' (" + template.getUnits() + ")"
                );
            }

            double number = Double.parseDouble(matcher.group(1));
            String unitPart = matcher.group(2) != null ? matcher.group(2).trim() : "";

            Unit inputUnit;
            String displayUnit;
            if (unitPart.isEmpty()) {
                inputUnit = templateUnit;
                displayUnit = template.getUnits().trim();
            } else {
                inputUnit = resolveUnit(unitPart);
                if (inputUnit == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Unrecognized unit '" + unitPart + "' in value '" + trimmed + "' for parameter '" + template.getName() + "'"
                    );
                }
                if (!inputUnit.isCompatible(templateUnit)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Unit '" + unitPart + "' is incompatible with template unit '" + template.getUnits() + "' for parameter '" + template.getName() + "'"
                    );
                }
                displayUnit = unitPart;
            }

            Quantity quantity = Quantities.getQuantity(number, inputUnit);
            Unit systemUnit = templateUnit.getSystemUnit();
            Quantity siQuantity = quantity.to(systemUnit);
            double siValue = siQuantity.getValue().doubleValue();

            String formattedNumber = matcher.group(1);
            return new ValidatedParameter(formattedNumber + " " + displayUnit, siValue);
        }

        // 4. Unconstrained text or plain number template
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
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid range bound '" + trimmed + "'");
                }
                double number = Double.parseDouble(matcher.group(1));
                String unitPart = matcher.group(2) != null ? matcher.group(2).trim() : "";
                Unit inputUnit = unitPart.isEmpty() ? templateUnit : resolveUnit(unitPart);
                if (inputUnit == null || !inputUnit.isCompatible(templateUnit)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Range filter unit '" + unitPart + "' is incompatible with '" + template.getUnits() + "'"
                    );
                }
                Quantity q = Quantities.getQuantity(number, inputUnit);
                return q.to(templateUnit.getSystemUnit()).getValue().doubleValue();
            }
        }
        Double d = tryParseDouble(trimmed);
        if (d == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Range bound '" + trimmed + "' must be numeric");
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
