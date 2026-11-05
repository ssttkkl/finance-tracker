package com.finance.tracker.backend.codegen;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class GenerateJooqTest {
    private void assertRejected(String url, String sensitiveValue) throws Exception {
        Path temporary = Files.createTempDirectory("finance-jooq-codegen-test-");
        Path output = temporary.resolve("generated");
        Path log = temporary.resolve("process.log");
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("jooq.codegen.classpath"),
            "com.finance.tracker.backend.codegen.GenerateJooq", output.toString()
        ).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("FT_JOOQ_CODEGEN_"));
        if (url != null) builder.environment().put("FT_JOOQ_CODEGEN_URL", url);
        Process process = builder.start();
        try {
            assertTrue("Code generation must reject the URL before connecting", process.waitFor(60, TimeUnit.SECONDS));
            String text = Files.readString(log, StandardCharsets.UTF_8);
            assertNotEquals("Invalid database URL must fail", 0, process.exitValue());
            assertFalse("Rejected input must not produce generated files", Files.exists(output));
            assertTrue("Rejection must be an explicit input error", text.contains("IllegalArgumentException"));
            if (sensitiveValue != null) assertFalse("Diagnostics must not expose URL contents", text.contains(sensitiveValue));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            Files.deleteIfExists(log);
            Files.deleteIfExists(temporary);
        }
    }

    @Test public void unsafeDatabaseLocationsAreRejectedBeforeConnection() throws Exception {
        assertRejected(null, null);
        for (String url : List.of(
            "jdbc:sqlite:/tmp/finance_test.db",
            "jdbc:postgresql://127.0.0.1/production",
            "jdbc:postgresql://127.0.0.1/finance_test?password=synthetic-secret",
            "jdbc:postgresql://127.0.0.1/finance_test#synthetic-secret",
            "jdbc:postgresql://user:synthetic-secret@127.0.0.1/finance_test",
            "jdbc:postgresql://127.0.0.1/../finance_test"
        )) assertRejected(url, "synthetic-secret");
    }

    @Test public void malformedUrlsDoNotExposeCredentialsInExceptionCauses() throws Exception {
        for (String url : List.of(
            "jdbc:postgresql://user:synthetic-secret%zz@127.0.0.1/finance_test",
            "jdbc:postgresql://user:synthetic-secret @127.0.0.1/finance_test",
            "jdbc:postgresql://127.0.0.1/finance_test?password=synthetic-secret%zz",
            "jdbc:postgresql://127.0.0.1/finance_test#synthetic-secret["
        )) assertRejected(url, "synthetic-secret");
    }
}
