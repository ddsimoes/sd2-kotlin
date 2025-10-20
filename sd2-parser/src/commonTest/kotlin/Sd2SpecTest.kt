import io.github.ddsimoes.sd2.ParseError
import io.github.ddsimoes.sd2.Sd2
import io.github.ddsimoes.sd2.Sd2Event
import io.github.ddsimoes.sd2.Sd2Value
import io.github.ddsimoes.sd2.StringSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Sd2SpecTest {
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
    fun documentAndElementAnnotations() {
        val input = """
            #![version("0.7")]
            #[deprecated(reason = "use v2")]
            api users {
              ok = true
            }
        """.trimIndent()
        val evs = collect(input)
        assertTrue(evs.any { it is Sd2Event.DocumentAnnotation })
        val start = evs.first { it is Sd2Event.StartElement } as Sd2Event.StartElement
        assertEquals(1, start.annotations.size)
        assertEquals(listOf("deprecated"), start.annotations[0].name.parts.map { it.text })
    }

    @Test
    fun qualifiersWithContinuationAndBodySameLine() {
        val input = """
            service auth : AuthService implements auth.OAuth2Provider
            | with monitoring.Metrics, logging.Structured {
              enabled = true
            }
        """.trimIndent()
        val start = collect(input).first { it is Sd2Event.StartElement } as Sd2Event.StartElement
        assertEquals(2, start.qualifiers.size)
        assertEquals("implements", start.qualifiers[0].name.text)
        assertEquals(listOf(listOf("auth","OAuth2Provider")), start.qualifiers[0].args.map { it.parts.map { p -> p.text } })
        assertEquals("with", start.qualifiers[1].name.text)
        assertEquals(listOf(listOf("monitoring","Metrics"), listOf("logging","Structured")), start.qualifiers[1].args.map { it.parts.map { p -> p.text } })
    }

    @Test
    fun bodyOnNextLineAfterQualifiersIsError() {
        val input = """
            task run : Runner
            | with base.Common
            {
              x = 1
            }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input) }
    }

    @Test
    fun constructorValueSameLine() {
        val input = (
            "config app {\n" +
            "  timeout = duration { seconds = 30\n" +
            " }\n" +
            "}\n"
        )
        val attr = collect(input).filterIsInstance<Sd2Event.Attribute>().first()
        val v = assertIs<Sd2Value.VConstructor>(attr.value)
        assertEquals(listOf("duration"), v.name.parts.map { it.text })
        assertEquals(1, v.attributes.size)
        assertEquals(30L, (v.attributes["seconds"] as Sd2Value.VInt).value)
    }

    @Test
    fun constructorNotSameLineIsError() {
        val input = """
            config app {
              timeout = duration\n{
                seconds = 30
              }
            }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input) }
    }

    @Test
    fun constructorRejectsNestedElementOrNamespace() {
        val badNs = (
            "config app {\n" +
            "  value = Type {\n" +
            "    .ns { }\n" +
            "  }\n" +
            "}\n"
        )
        assertFailsWith<ParseError> { collect(badNs) }

        val badEl = (
            "config app {\n" +
            "  value = Type {\n" +
            "    child x { }\n" +
            "  }\n" +
            "}\n"
        )
        assertFailsWith<ParseError> { collect(badEl) }
    }

    @Test
    fun listMapBracketedKeysAndTrailingCommas() {
        val input = """
            data X {
              ports = [8080, 8443, 9090,]
              responses = { [200] = "OK", [404] = "Not Found", }
            }
        """.trimIndent()
        val attrs = collect(input).filterIsInstance<Sd2Event.Attribute>()
        val ports = attrs.first { it.name.text == "ports" }.value as Sd2Value.VList
        assertEquals(listOf(8080L, 8443L, 9090L), ports.items.map { (it as Sd2Value.VInt).value })
        val responses = attrs.first { it.name.text == "responses" }.value as Sd2Value.VMap
        assertEquals("OK", (responses.entries["200"] as Sd2Value.VString).value)
        assertEquals("Not Found", (responses.entries["404"] as Sd2Value.VString).value)
    }

    @Test
    fun multilineStringNormalization() {
        val input = (
            "item X {\n" +
            "  text = \"\"\"\n" +
            "      Line1\n" +
            "      Line2\\\n" +
            "      Joined\n" +
            "      \"\"\"\n" +
            "}\n"
        )
        val attr = collect(input).filterIsInstance<Sd2Event.Attribute>().first()
        val s = (attr.value as Sd2Value.VString).value
        // Dedent removed leading spaces, line join removed the newline after Line2
        assertEquals("Line1\nLine2Joined\n", s)
    }

    @Test
    fun foreignCodeSingleAndTriple() {
        val input = (
            "script code {\n" +
            "  re = @\"^\\\\\\\\d{4}-\\\\\\\\d{2}-\\\\\\\\d{2}$\"\n" +
            "  tpl = @\"\"\"\n" +
            "    Hello\n" +
            "    World\n" +
            "\"\"\"\n" +
            "}\n"
        )
        val attrs = collect(input).filterIsInstance<Sd2Event.Attribute>()
        val re = attrs.first { it.name.text == "re" }.value as Sd2Value.VForeign
        assertTrue(re.content.isNotEmpty())
        val tpl = attrs.first { it.name.text == "tpl" }.value as Sd2Value.VForeign
        assertTrue(tpl.content.contains("Hello"))
        assertTrue(tpl.content.contains("World"))
    }

    @Test
    fun foreignCodeWithConstructorsSimpleQualifiedBacktick() {
        val input = (
            "server api {\n" +
            "  health = sh@'echo ok'\n" +
            "  query = db.postgresql@\"SELECT 1\"\n" +
            "  legacy = `my-shell`@'run'\n" +
            "}\n"
        )
        val attrs = collect(input).filterIsInstance<Sd2Event.Attribute>()
        val health = attrs.first { it.name.text == "health" }.value as Sd2Value.VForeign
        assertEquals(listOf("sh"), health.constructor!!.parts.map { it.text })
        assertEquals("echo ok", health.content)
        val query = attrs.first { it.name.text == "query" }.value as Sd2Value.VForeign
        assertEquals(listOf("db","postgresql"), query.constructor!!.parts.map { it.text })
        assertTrue(query.content.contains("SELECT"))
        val legacy = attrs.first { it.name.text == "legacy" }.value as Sd2Value.VForeign
        assertEquals(listOf("my-shell"), legacy.constructor!!.parts.map { it.text })
        assertEquals("run", legacy.content)
    }

    @Test
    fun temporalConstructorDatetime() {
        val input = """
            job now {
              start = datetime("2024-03-15T14:30:00Z")
            }
        """.trimIndent()
        val v = (collect(input).filterIsInstance<Sd2Event.Attribute>().first().value) as Sd2Value.VConstructorTuple
        assertEquals(listOf("datetime"), v.name.parts.map { it.text })
        val arg0 = v.args.first() as Sd2Value.VString
        assertEquals("2024-03-15T14:30:00Z", arg0.value)
    }

    @Test
    fun simpleTuples() {
        val input = """
            data P {
              a = ()
              b = (1)
              c = (1,)
              d = (1, "two")
              e = (1, "two",)
            }
        """.trimIndent()
        val attrs = collect(input).filterIsInstance<Sd2Event.Attribute>()
        val a = attrs.first { it.name.text == "a" }.value as Sd2Value.VTuple
        assertEquals(0, a.items.size)
        val b = attrs.first { it.name.text == "b" }.value as Sd2Value.VTuple
        assertEquals(1, b.items.size)
        val c = attrs.first { it.name.text == "c" }.value as Sd2Value.VTuple
        assertEquals(1, c.items.size)
        val d = attrs.first { it.name.text == "d" }.value as Sd2Value.VTuple
        assertEquals(2, d.items.size)
        val e = attrs.first { it.name.text == "e" }.value as Sd2Value.VTuple
        assertEquals(2, e.items.size)
    }

    @Test
    fun tupleLiteralAndConstructor() {
        val input = """
            data P {
              center = (-25.43, -49.27)
              one = (42,)
              point = Point(10, 20)
            }
        """.trimIndent()
        val attrs = collect(input).filterIsInstance<Sd2Event.Attribute>()
        val center = attrs.first { it.name.text == "center" }.value as Sd2Value.VTuple
        assertEquals(2, center.items.size)
        val one = attrs.first { it.name.text == "one" }.value as Sd2Value.VTuple
        assertEquals(1, one.items.size)
        val point = attrs.first { it.name.text == "point" }.value as Sd2Value.VConstructorTuple
        assertEquals(listOf("Point"), point.name.parts.map { it.text })
        assertEquals(2, point.args.size)
    }

    @Test
    fun mapsShouldNotSupportElements() {
        val input = """
            root {
              center = {
                key1 = "value"
                
                foo {
                  x = 1
                }
              }
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E1000", ex.code)
    }

    @Test
    fun mapsShouldNotSupportElements2() {
        val input = """
            root {
              center = {
                foo {
                  x = 1
                }
              }
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E1000", ex.code)
    }

    @Test
    fun mapsExpectCommaSeparator() {
        val input = """
            root {
              center = {
                key1 = "value"
                key2 = "value"
              }
            }
        """.trimIndent()
        val ex = assertFailsWith<ParseError> { collect(input) }
        assertEquals("E1000", ex.code)
    }


    @Test
    fun tupleErrors() {
        val bad2 = """
            x A {
              v = Name
              (1,2)
            }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(bad2) }
    }

    @Test
    fun backtickIdentifiers() {
        val input = """
            keyword `my id` {
              `null` = "ok"
            }
        """.trimIndent()
        val evs = collect(input)
        val start = evs.first { it is Sd2Event.StartElement } as Sd2Event.StartElement
        assertEquals("keyword", start.keyword.text)
        assertEquals("my id", start.id!!.text)
        val attr = evs.filterIsInstance<Sd2Event.Attribute>().first()
        assertEquals("null", attr.name.text)
        assertEquals("ok", (attr.value as Sd2Value.VString).value)
    }

    @Test
    fun backtickKeywordIsError() {
        val input = """
            `complex` name { }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input) }
    }

    @Test
    fun namespaceNameBacktickIsError() {
        val input = (
            "parent P {\n" +
            "  .`ns` { }\n" +
            "}\n"
        )
        assertFailsWith<ParseError> { collect(input) }
    }

    @Test
    fun qualifierNameBacktickIsError() {
        val input = """
            s x : T `with` a.b { y = 1 }
        """.trimIndent()
        assertFailsWith<ParseError> { collect(input) }
    }
}
