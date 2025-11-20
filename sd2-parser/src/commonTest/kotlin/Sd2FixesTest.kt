import io.github.ddsimoes.sd2.ParseError
import io.github.ddsimoes.sd2.Sd2
import io.github.ddsimoes.sd2.Sd2Event
import io.github.ddsimoes.sd2.Sd2Value
import io.github.ddsimoes.sd2.StringSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Sd2FixesTest {
    private fun collect(input: String): List<Sd2Event> {
        val r = Sd2.reader(StringSource(input))
        val out = mutableListOf<Sd2Event>()
        var guard = 0
        while (guard++ < 10000) {
            val e = r.next()
            out += e
            if (e is Sd2Event.EndDocument) break
        }
        return out
    }

    @Test
    fun mapEntryRequiresEquals() {
        val input = """
            data X {
              m = { a 1 }
            }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input) }

        val input2 = """
            data X {
              responses = { [200] "OK" }
            }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input2) }
    }

    @Test
    fun qualifierWithoutArgumentsIsErrorE2101() {
        val input = """
            field email : String unique {
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E2101", ex.code)
    }

    @Test
    fun continuationMustBeColumn1E1002() {
        val input = """
            service x : T
              | with a.b {
              ok = true
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E1002", ex.code)
    }

    @Test
    fun strayContinuationInBodyIsE1004() {
        val input = """
            a x {
              ok = true
            | with a.b
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E1004", ex.code)
    }

    @Test
    fun documentAnnotationOnlyAtTop() {
        val input = """
            api x {}
            ##[version("0.7")]
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input) }
    }

    @Test
    fun constructorBraceNextLineIsE1001() {
        val input = """
            x A {
              v = Type
              {
                a = 1
              }
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E1001", ex.code)
    }

    @Test
    fun backtickIdentifierNewlineIsE6002() {
        val input = """
            keyword `unterminated
            name { }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E6002", ex.code)
    }

    @Test
    fun binaryIntegerAndSignedHexBinaryRules() {
        val good = """
            item X {
              b = 0b1010
            }
        """.trimIndent()
        val evs = collect(good)
        val attr = evs.filterIsInstance<Sd2Event.Attribute>().first()
        val v = attr.value as Sd2Value.VInt
        assertEquals(10L, v.value)

        val badHex = """
            x A {
              v = +0x1
            }
        """.trimIndent()
        val ex1 = assertFailsWith<ParseError> { collect(badHex) }
        assertEquals("E7001", ex1.code)

        val badBin = """
            x A {
              v = -0b1
            }
        """.trimIndent()
        val ex2 = assertFailsWith<ParseError> { collect(badBin) }
        assertEquals("E7001", ex2.code)
    }

    @Test
    fun foreignTripleBracketsAndBracesCloseProperly() {
        val input = """
            sample S {
              json = @{{{
                {"a":1}
              }}}
              sql = @[[[
                SELECT 1;
              ]]]
            }
        """.trimIndent()
        val attrs = collect(input).filterIsInstance<Sd2Event.Attribute>()
        val json = attrs.first { it.name.text == "json" }.value as Sd2Value.VForeign
        val sql = attrs.first { it.name.text == "sql" }.value as Sd2Value.VForeign
        assertTrue(json.content.contains("\"a\""))
        assertTrue(sql.content.contains("SELECT 1"))
    }

    @Test
    fun foreignInvalidDelimiterAfterAtYieldsE4002() {
        val input = """
            s X {
              bad = @x
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E4002", ex.code)
    }

    @Test
    fun foreignConstructorWhitespaceBeforeAtIsE4003() {
        val input = """
            s X {
              a = sh @'echo'
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E4003", ex.code)
    }

    @Test
    fun foreignConstructorReservedWordIsE4004() {
        val input = """
            s X {
              a = true@'x'
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E4004", ex.code)
    }
}
