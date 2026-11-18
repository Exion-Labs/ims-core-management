package com.ims.config.tenant;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hibernate StatementInspector that inspects every SQL statement sent by Hibernate and verifies:
 * 1. Whenever a statement touches a tenant-scoped table, a tenant must be bound in TenantContext.
 * 2. Every statement touching a tenant-scoped table includes a tenant_id column / predicate.
 */
@Component
public class TenantPredicateInspector implements StatementInspector, HibernatePropertiesCustomizer {

    private static final Set<String> TENANT_SCOPED_TABLES = Set.of(
            "roles",
            "user_role_assignments",
            "tenant_settings",
            "reference_sequences",
            "attachments",
            "notes",
            "audit_event",
            "part_categories",
            "parts",
            "parameter_templates",
            "part_parameters",
            "bom_items",
            "bom_item_substitutes",
            "companies",
            "contacts",
            "addresses",
            "manufacturer_parts",
            "supplier_parts",
            "supplier_price_breaks",
            "stock_locations",
            "stock_items",
            "stock_item_tracking",
            "part_test_templates",
            "stock_item_test_results",
            "part_stock_summaries",
            "build_orders",
            "build_lines",
            "build_allocations",
            "purchase_orders",
            "purchase_order_line_items",
            "sales_orders",
            "sales_order_shipments",
            "return_orders",
            "transfer_orders",
            "webhook_subscriptions"
    );

    private static final Pattern TABLE_WORD_PATTERN = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\b");

    private final List<String> recordedStatements = new CopyOnWriteArrayList<>();
    private volatile boolean recordStatements = false;

    @Override
    public String inspect(String sql) {
        if (sql == null) {
            return null;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        boolean touchesTenantTable = false;
        String matchedTable = null;

        Matcher matcher = TABLE_WORD_PATTERN.matcher(lower);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (TENANT_SCOPED_TABLES.contains(token)) {
                touchesTenantTable = true;
                matchedTable = token;
                break;
            }
        }

        String finalSql = sql;
        if (touchesTenantTable) {
            // Ensure a real tenant is bound in TenantContext (not unbound)
            if (!TenantContext.isBound()) {
                throw new IllegalStateException(
                        "Attempted to execute SQL on tenant-scoped table '" + matchedTable +
                        "' without a bound tenant in TenantContext: " + sql);
            }
            if (!lower.contains("tenant_id")) {
                if ((lower.startsWith("update ") || lower.startsWith("delete from ")) && lower.contains(" where id=?")) {
                    finalSql = sql + " and tenant_id='" + TenantContext.require() + "'";
                } else {
                    throw new IllegalStateException(
                            "SQL statement on tenant-scoped table '" + matchedTable +
                            "' is missing tenant_id predicate/binding: " + sql);
                }
            }
        }
        if (recordStatements) {
            recordedStatements.add(finalSql);
        }
        return finalSql;
    }

    public void startRecording() {
        recordedStatements.clear();
        recordStatements = true;
    }

    public List<String> stopRecording() {
        recordStatements = false;
        return List.copyOf(recordedStatements);
    }

    public List<String> getRecordedStatements() {
        return List.copyOf(recordedStatements);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.STATEMENT_INSPECTOR, this);
    }
}
