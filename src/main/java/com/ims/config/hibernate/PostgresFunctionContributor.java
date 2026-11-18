package com.ims.config.hibernate;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.BasicType;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * Registers PostgreSQL ltree and pg_trgm operators/functions for use in HQL/JPQL
 * so that queries never require native SQL and always pass through Hibernate's @TenantId filter.
 */
public class PostgresFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        TypeConfiguration typeConfig = functionContributions.getTypeConfiguration();
        BasicType<Boolean> booleanType = typeConfig.getBasicTypeRegistry().resolve(StandardBasicTypes.BOOLEAN);
        BasicType<Integer> integerType = typeConfig.getBasicTypeRegistry().resolve(StandardBasicTypes.INTEGER);
        BasicType<Double> doubleType = typeConfig.getBasicTypeRegistry().resolve(StandardBasicTypes.DOUBLE);
        BasicType<String> stringType = typeConfig.getBasicTypeRegistry().resolve(StandardBasicTypes.STRING);

        var registry = functionContributions.getFunctionRegistry();

        // ltree functions
        registry.registerPattern(
                "ltree_descendant",
                "(?1::ltree <@ ?2::ltree)",
                booleanType
        );
        registry.registerPattern(
                "ltree_ancestor",
                "(?1::ltree @> ?2::ltree)",
                booleanType
        );
        registry.registerPattern(
                "nlevel",
                "nlevel(?1::ltree)",
                integerType
        );
        registry.registerPattern(
                "subpath",
                "subpath(?1::ltree, ?2)::text",
                stringType
        );
        registry.registerPattern(
                "ltree_concat",
                "(?1::ltree || ?2::ltree)::text",
                stringType
        );

        // pg_trgm functions
        registry.registerPattern(
                "trgm_match",
                "(?1 % ?2)",
                booleanType
        );
        registry.registerPattern(
                "similarity",
                "similarity(?1, ?2)",
                doubleType
        );
    }
}
