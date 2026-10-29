package com.finance.tracker.backend.codegen;

import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.ForcedType;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Logging;
import org.jooq.meta.jaxb.Target;
import org.jooq.meta.jaxb.VisibilityModifier;

public final class GenerateJooq {
    public static void main(String[] args) throws Exception {
        System.setProperty("org.jooq.no-logo", "true");
        System.setProperty("org.jooq.no-tips", "true");
        String url = System.getenv("FT_JOOQ_CODEGEN_URL");
        if (url == null || !url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("FT_JOOQ_CODEGEN_URL must explicitly name a PostgreSQL test database");
        URI uri;
        try {
            uri = URI.create(url.substring(5));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("FT_JOOQ_CODEGEN_URL must be a valid PostgreSQL test database URL");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().matches("/[a-zA-Z0-9_]+_test"))
            throw new IllegalArgumentException("Code generation requires an explicit _test database without URL credentials or parameters");
        Properties properties = new Properties();
        String user = System.getenv("FT_JOOQ_CODEGEN_USER");
        String password = System.getenv("FT_JOOQ_CODEGEN_PASSWORD");
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        try (var connection = DriverManager.getConnection(url, properties)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            if (connection.getMetaData().getDatabaseMajorVersion() < 18)
                throw new IllegalArgumentException("Code generation requires PostgreSQL 18 or later");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version_num FROM alembic_version")) {
                if (!rows.next() || !"20261028_51".equals(rows.getString(1)) || rows.next())
                    throw new IllegalArgumentException("Migrate the dedicated test database to the current schema before code generation");
            }
            var database = new Database().withName("org.jooq.meta.postgres.PostgresDatabase")
                .withInputSchema("public").withOutputSchemaToDefault(true).withExcludes("alembic_version")
                .withIncludeSequences(false).withIncludeRoutines(false).withIncludeSystemTables(false)
                .withForcedTypes(
                    new ForcedType().withName("BIGINT").withIncludeTypes("(?i:INTEGER|INT4|SMALLINT|INT2)"),
                    new ForcedType().withUserType("com.finance.tracker.backend.CashStoredDecimal")
                        .withBinding("com.finance.tracker.backend.persistence.CashDecimalBinding")
                        .withIncludeTypes("(?i:NUMERIC|DECIMAL)(\\(.*\\))?"),
                    new ForcedType().withUserType("java.time.Instant")
                        .withBinding("com.finance.tracker.backend.persistence.UtcInstantBinding")
                        .withIncludeTypes("(?i:TIMESTAMP.*WITH TIME ZONE|TIMESTAMPTZ)"),
                    new ForcedType().withUserType("kotlinx.serialization.json.JsonElement")
                        .withConverter("com.finance.tracker.backend.persistence.JsonElementConverter")
                        .withIncludeTypes("(?i:JSON)"));
            var config = new Configuration().withLogging(Logging.WARN).withGenerator(new Generator()
                .withName("org.jooq.codegen.KotlinGenerator").withDatabase(database)
                .withGenerate(new Generate().withDaos(false).withPojos(false).withRecords(true)
                    .withGeneratedAnnotation(false).withComments(false).withJavadoc(false)
                    .withVisibilityModifier(VisibilityModifier.INTERNAL)
                    .withImplicitJoinPathsToOne(false).withImplicitJoinPathsToMany(false)
                    .withImplicitJoinPathsManyToMany(false)
                    .withGlobalObjectReferences(true).withKotlinNotNullRecordAttributes(false))
                .withTarget(new Target().withPackageName("com.finance.tracker.backend.persistence.generated")
                    .withDirectory(Path.of(args[0]).toAbsolutePath().toString()).withEncoding("UTF-8")));
            var tool = new GenerationTool();
            tool.setConnection(connection);
            tool.run(config);
            connection.rollback();
        }
    }
}
