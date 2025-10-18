import io.github.ddsimoes.sd2.Sd2
import io.github.ddsimoes.sd2.Sd2Event
import io.github.ddsimoes.sd2.Sd2Value
import io.github.ddsimoes.sd2.*
import kotlin.test.Test
import kotlin.test.assertEquals

class Sd2BasicTest {
    @Test
    fun simpleElementAndAttributes() {
        val input = """
            widget Button {
              text = "Click me"
              width = 120
              theme = dark.primary
            }
        """.trimIndent()

        val r = Sd2.reader(StringSource(input))
        val events = mutableListOf<Sd2Event>()
        var guard = 0
        while (guard++ < 100) {
            val ev = r.next()
            events += ev
            if (ev is Sd2Event.EndDocument) break
        }

        // Expect at least StartDocument, StartElement, attributes, EndElement, EndDocument
        assertEquals(true, events.first() is Sd2Event.StartDocument)
        val startEl = events.first { it is Sd2Event.StartElement } as Sd2Event.StartElement
        assertEquals("widget", startEl.keyword.text)
        assertEquals("Button", startEl.id?.text)

        val attrs = events.filterIsInstance<Sd2Event.Attribute>()
        assertEquals(3, attrs.size)
        assertEquals("text", attrs[0].name.text)
        assertEquals("Click me", (attrs[0].value as Sd2Value.VString).value)
        assertEquals("width", attrs[1].name.text)
        assertEquals(120L, (attrs[1].value as Sd2Value.VInt).value)
        assertEquals("theme", attrs[2].name.text)
        val qn = attrs[2].value as Sd2Value.VQualifiedName
        assertEquals(listOf("dark","primary"), qn.parts)

        assertEquals(true, events.any { it is Sd2Event.EndElement })
        assertEquals(true, events.last() is Sd2Event.EndDocument)
    }

    @Test
    fun tripleQuotedString() {
        val input = "item X {\n" +
            "  text = \"\"\"\n" +
            "Hello\n" +
            "World\n" +
            "\"\"\"\n" +
            "}\n"
        val r = Sd2.reader(StringSource(input))
        val evs = generateSequence { r.next() }.take(30).toList()
        val attr = evs.first { it is Sd2Event.Attribute } as Sd2Event.Attribute
        val s = (attr.value as Sd2Value.VString).value
        // Should contain multi-line content
        kotlin.test.assertTrue(s.contains("Hello"))
        kotlin.test.assertTrue(s.contains("World"))
    }

    @Test
    fun namespaceNested() {
        val input = """
            page Home {
              .header {
                title = "Welcome"
              }
            }
        """.trimIndent()

        val r = Sd2.reader(StringSource(input))
        val events = generateSequence { r.next() }.take(20).toList()

        val nsStart = events.first { it is Sd2Event.StartNamespace } as Sd2Event.StartNamespace
        assertEquals("header", nsStart.name.text)
        val attr = events.first { it is Sd2Event.Attribute } as Sd2Event.Attribute
        assertEquals("title", attr.name.text)
        assertEquals("Welcome", (attr.value as Sd2Value.VString).value)
    }

    @Test
    fun listsAndMapsAndNoBodyElement() {
        val input = """
            color red
            view Main {
              items = [1, 2, 3,]
              props = {w=100, h=200, title = "Hello"}
            }
        """.trimIndent()

        val r = Sd2.reader(StringSource(input))
        val evs = mutableListOf<Sd2Event>()
        var i = 0
        while (i++ < 100) {
            val ev = r.next()
            evs += ev
            if (ev is Sd2Event.EndDocument) break
        }

        // First element has no body; ensure we see StartElement then EndElement
        val idxStartRed = evs.indexOfFirst { it is Sd2Event.StartElement && (it as Sd2Event.StartElement).keyword.text == "color" }
        val idxEndRed = evs.indexOfFirst { it is Sd2Event.EndElement && evs.indexOf(it) > idxStartRed }
        kotlin.test.assertTrue(idxStartRed >= 0 && idxEndRed > idxStartRed)

        // Second element has body and attributes with list/map
        val attrs = evs.filterIsInstance<Sd2Event.Attribute>()
        val items = attrs.first { it.name.text == "items" }.value as Sd2Value.VList
        assertEquals(listOf(1L, 2L, 3L), items.items.map { (it as Sd2Value.VInt).value })
        val props = attrs.first { it.name.text == "props" }.value as Sd2Value.VMap
        assertEquals(100L, (props.entries["w"] as Sd2Value.VInt).value)
        assertEquals(200L, (props.entries["h"] as Sd2Value.VInt).value)
        assertEquals("Hello", (props.entries["title"] as Sd2Value.VString).value)
    }

    @Test
    fun typeAndQualifiers() {
        val input = """
            component Button : ui.widget<ButtonBase, theme.Primary> size small.core, common.base {
              enabled = true
            }
        """.trimIndent()

        val r = Sd2.reader(StringSource(input))
        val events = generateSequence { r.next() }.take(20).toList()
        val start = events.first { it is Sd2Event.StartElement } as Sd2Event.StartElement
        // keyword and id
        assertEquals("component", start.keyword.text)
        assertEquals("Button", start.id?.text)
        // type
        val t = start.type!!
        assertEquals(listOf("ui","widget"), t.name.parts.map { it.text })
        assertEquals(2, t.typeArgs.size)
        assertEquals(listOf("ButtonBase"), t.typeArgs[0].name.parts.map { it.text })
        assertEquals(listOf("theme","Primary"), t.typeArgs[1].name.parts.map { it.text })
        // qualifiers
        assertEquals(1, start.qualifiers.size)
        val q = start.qualifiers[0]
        assertEquals("size", q.name.text)
        assertEquals(listOf(
            listOf("small","core"),
            listOf("common","base")
        ), q.args.map { it.parts.map { p -> p.text } })
    }

    @Test
    fun annotations() {
        val input = """
            #![feature.experimental]
            #[ui.test]
            widget Button {
              ok = true
            }
        """.trimIndent()

        val r = Sd2.reader(StringSource(input))
        val evs = generateSequence { r.next() }.take(20).toList()
        // Document annotation present
        kotlin.test.assertTrue(evs.any { it is Sd2Event.DocumentAnnotation })
        val start = evs.first { it is Sd2Event.StartElement } as Sd2Event.StartElement
        kotlin.test.assertEquals(1, start.annotations.size)
        kotlin.test.assertEquals(listOf("ui","test"), start.annotations.first().name.parts.map { it.text })
    }
}
