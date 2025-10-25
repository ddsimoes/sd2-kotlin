import io.github.ddsimoes.sd2.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Sd2IdentifiersTest {
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
    fun validHyphenInKeywordIdAndAttribute() {
        val input = """
            my-service api-v2 {
              attr-name = 1
            }
        """.trimIndent()
        val evs = collect(input)
        val start = evs.first { it is Sd2Event.StartElement } as Sd2Event.StartElement
        assertEquals("my-service", start.keyword.text)
        assertEquals("api-v2", start.id!!.text)
        val attr = evs.filterIsInstance<Sd2Event.Attribute>().first()
        assertEquals("attr-name", attr.name.text)
        assertEquals(1L, (attr.value as Sd2Value.VInt).value)
    }

    @Test
    fun validHyphenInQualifiedNamesForTypeAndConstructor() {
        val input = """
            service svc : pkg.my-lib.Type {
              value = pkg.my-lib.Thing(1)
            }
        """.trimIndent()
        val evs = collect(input)
        val start = evs.filterIsInstance<Sd2Event.StartElement>().first()
        assertEquals(listOf("pkg","my-lib","Type"), start.type!!.name.parts.map { it.text })
        val value = evs.filterIsInstance<Sd2Event.Attribute>().first { it.name.text == "value" }.value
        val ctor = assertIs<Sd2Value.VConstructorTuple>(value)
        assertEquals(listOf("pkg","my-lib","Thing"), ctor.name.parts.map { it.text })
        assertEquals(1L, (ctor.args.first() as Sd2Value.VInt).value)
    }

    @Test
    fun invalidIdentifierStartingWithHyphen() {
        val input = """
            -api x { }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input) }
    }

    @Test
    fun reservedWordsCannotBeSimpleIdentifiers() {
        val input = """
            service x {
              true = 1
            }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input) }
    }

    @Test
    fun reservedWordsAllowedWithBackticks() {
        val input = """
            service x {
              `true` = 1
              `false` = 2
              `null` = 3
            }
        """.trimIndent()
        val attrs = collect(input).filterIsInstance<Sd2Event.Attribute>()
        assertEquals(setOf("true","false","null"), attrs.map { it.name.text }.toSet())
        assertEquals(1L, (attrs.first { it.name.text == "true" }.value as Sd2Value.VInt).value)
        assertEquals(2L, (attrs.first { it.name.text == "false" }.value as Sd2Value.VInt).value)
        assertEquals(3L, (attrs.first { it.name.text == "null" }.value as Sd2Value.VInt).value)
    }

    @Test
    fun reservedWordNotAllowedAsConstructorSimple() {
        val input = """
            service x {
              v = true@"ok"
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E4004", ex.code)
    }

    @Test
    fun reservedWordAllowedAsConstructorWhenBackticked() {
        val input = """
            service x {
              v = `true`@"ok"
            }
        """.trimIndent()
        val attr = collect(input).filterIsInstance<Sd2Event.Attribute>().first()
        val f = assertIs<Sd2Value.VForeign>(attr.value)
        assertEquals(listOf("true"), f.constructor!!.parts.map { it.text })
        assertEquals("ok", f.content)
    }

    @Test
    fun namesWithTrailingHyphenAndLeadingUnderscoreAreValid() {
        val input = """
            service svc {
              name- = 1
              _x = 2
            }
        """.trimIndent()
        val attrs = collect(input).filterIsInstance<Sd2Event.Attribute>()
        assertTrue(attrs.any { it.name.text == "name-" && (it.value as Sd2Value.VInt).value == 1L })
        assertTrue(attrs.any { it.name.text == "_x" && (it.value as Sd2Value.VInt).value == 2L })
    }
}

