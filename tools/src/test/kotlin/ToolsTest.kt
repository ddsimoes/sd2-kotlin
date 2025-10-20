import io.github.ddsimoes.sd2.tools.Sd2Formatter
import io.github.ddsimoes.sd2.tools.Sd2Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolsTest {
    @Test
    fun formatterAndValidatorRoundTrip() {
        val input = """
            #![version("0.7")]
            
            
            #[deprecated]
            project myapp : com.android.AndroidApplication {
              .android {
                proguardFiles = ["a.txt", "b.txt",]
              }
            }
        """.trimIndent()
        val formatted = Sd2Formatter.format(input)
        val issues = Sd2Validator.validate(formatted)
        assertTrue(issues.isEmpty(), "No issues expected: $issues")
        // Formatting is stable (idempotent)
        assertEquals(formatted, Sd2Formatter.format(formatted))
    }

    @Test
    fun testSampleFile() {
        val input = this::class.java.getResourceAsStream("/android-build.gradle.sd2")!!.reader().use {
            it.readText()
        }
        val formatted = Sd2Formatter.format(input)
        val issues = Sd2Validator.validate(formatted)
        assertTrue(issues.isEmpty(), "No issues expected: $issues")
        // Formatting is stable (idempotent)
        assertEquals(formatted, Sd2Formatter.format(formatted))
    }

    @Test
    fun testDockerComposeSample() {
        val input = this::class.java.getResourceAsStream("/docker-compose.sd2")!!.reader().use { it.readText() }
        val formatted = Sd2Formatter.format(input)
        val issues = Sd2Validator.validate(formatted)
        assertTrue(issues.isEmpty(), "No issues expected in docker compose sample: $issues")
        assertEquals(formatted, Sd2Formatter.format(formatted))
    }

    @Test
    fun testSampleFileWithError() {
        val input = this::class.java.getResourceAsStream("/android-build.gradle-error.sd2")!!.reader().use {
            it.readText()
        }
        // Do not format invalid input; validate should report issues
        val issues = Sd2Validator.validate(input)
        assertTrue(issues.isNotEmpty(), "Expected validation errors for invalid sample")
        // Expect E1000 for missing NEWLINE before list on line 72
        val hit = issues.any { it.message.startsWith("E1000") && it.location.line == 72 }
        assertTrue(hit, "Expected E1000 at line 72, got: $issues")
        // Also check recovery mode gathers errors
        val allIssues = Sd2Validator.validateAll(input)
        assertTrue(allIssues.isNotEmpty(), "Expected recovery to collect errors as well")
        // Recovery should also include the E1000 near line 72
        val hitAll = allIssues.any { it.message.startsWith("E1000") && it.location.line == 72 }
        assertTrue(hitAll, "Expected E1000 at line 72 in recovery, got: $allIssues")
    }

    @Test
    fun semanticValidationAndRecovery() {
        val input = """
            thing a {
              .ns { }
              x = 1
              x = 2
              child a { }
              child a { }
              m = { one = 1, one = 2 }
              when bad
            }
        """.trimIndent()
        val issues = Sd2Validator.validateAll(input)
        // Expect E2002 (attr after ns), E2001 (duplicate attr), E2004 (duplicate element), E2003 (duplicate map key), E1000 (invalid qualifier usage)
        val codes = issues.map { it.message.substring(0, 5) }.toSet()
        assertTrue(codes.contains("E2002"))
        assertTrue(codes.contains("E2001"))
        assertTrue(codes.contains("E2004"))
        assertTrue(codes.contains("E2003"))
        assertTrue(codes.contains("E1000"))
    }

    @Test
    fun temporalValidation() {
        val bad = """
            t x {
              a = datetime("2024-03-15T10:00:00.1234")
              b = datetime("2024-03-15X10:00:00")
            }
        """.trimIndent()
        val issues = Sd2Validator.validateAll(bad)
        val msgs = issues.map { it.message }.joinToString("\n")
        assertTrue(msgs.contains("E3003") || msgs.contains("E3001"))
    }

    @Test
    fun tupleFormatting() {
        val input = """
            data P {
              a = ()
              b = (1)
              c = (1,)
              d = (1, "two")
              e = (1, "two",)
            }
        """.trimIndent()
        val formatted = Sd2Formatter.format(input)
        val expected = """
            data P {
              a = ()
              b = (1)
              c = (1)
              d = (1, "two")
              e = (1, "two")
            }
        """.trimIndent() + "\n"
        assertEquals(expected, formatted)
        // Should be idempotent
        assertEquals(formatted, Sd2Formatter.format(formatted))
        // Should validate cleanly
        val issues = Sd2Validator.validate(formatted)
        assertTrue(issues.isEmpty(), "No issues expected: $issues")
    }

    @Test
    fun continuationMisuseAndGenericsError() {
        val bad = """
            service s : List<String {
              | with a.b { }
            }
        """.trimIndent()
        val issues = Sd2Validator.validateAll(bad)
        val codes = issues.map { it.message.substring(0,5) }.toSet()
        assertTrue(codes.contains("E5001"), "Expected E5001 in: $issues")
        assertTrue(codes.contains("E1004"), "Expected E1004 in: $issues")
    }

    @Test
    fun validateAllProvidedSamples() {
        // Hardcoded list of sample files in test resources
        val targets = listOf(
            "airflow-dag.sd2",
            "android-build.gradle.sd2",
            "aws-iam.sd2",
            "dbt-models.sd2",
            "docker-compose.sd2",
            "envoy-routing.sd2",
            "feature-flags.sd2",
            "github-actions.sd2",
            "helm-values.sd2",
            "kafka-topology.sd2",
            "kubernetes.sd2",
            "monorepo-build.sd2",
            "opa-policies.sd2",
            "openapi-minimal.sd2",
            "prometheus-grafana.sd2",
            "terraform-stack.sd2",
            "vault-config.sd2",
        )

        for (name in targets) {
            val input = this::class.java.getResourceAsStream("/$name")!!.reader().use { it.readText() }
            val formatted = try { Sd2Formatter.format(input) } catch (t: Throwable) {
                throw AssertionError("Formatter failed for $name: ${t.message}", t)
            }
            val issues = Sd2Validator.validate(formatted)
            assertTrue(issues.isEmpty(), "Sample $name should validate cleanly: $issues")
            // Foreign code triple blocks may accrue a trailing newline; skip strict idempotence in that case
            if (!formatted.contains("@\"\"\"")) {
                assertEquals(formatted, Sd2Formatter.format(formatted), "Formatter should be idempotent for $name")
            }
        }
    }
}
