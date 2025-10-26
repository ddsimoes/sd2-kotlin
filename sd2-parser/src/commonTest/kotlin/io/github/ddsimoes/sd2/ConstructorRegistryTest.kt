package io.github.ddsimoes.sd2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ConstructorRegistryTest {

    private fun readAttributes(src: String, config: Sd2ReaderConfig): Map<String, Sd2Value?> {
        val reader = Sd2.reader(StringSource(src), config)
        val attrs = LinkedHashMap<String, Sd2Value?>()
        while (true) {
            when (val ev = reader.next()) {
                is Sd2Event.EndDocument -> break
                is Sd2Event.Attribute -> attrs[ev.name.text] = ev.value
                else -> {}
            }
        }
        return attrs
    }

    @Test
    fun resolves_registered_tuple_constructor_to_VObject() {
        val builder = ConstructorRegistryBuilder()
        // foo(string) -> uppercase string; typeTag = test.Foo
        builder.register("foo", "test.Foo", ConstructorHandler { call, ctx ->
            val arg0 = call.args.firstOrNull() as? Sd2Value.VString
                ?: ctx.error("E5002", "foo requires one string argument", call.location)
            arg0.value.uppercase()
        })
        val reg = builder.build()

        val input = """
            item {
              a = foo("bar")
            }
        """.trimIndent()

        val attrs = readAttributes(input, Sd2ReaderConfig(constructorRegistry = reg))
        val a = attrs["a"] ?: fail("missing attribute a")
        val vobj = assertIs<Sd2Value.VObject>(a)
        assertEquals(listOf("test","Foo"), vobj.type.parts.map { it.text })
        assertEquals("BAR", vobj.value as String)
    }

    @Test
    fun resolves_nested_constructors_via_ctx_resolve() {
        val builder = ConstructorRegistryBuilder()
        builder.register("foo", "test.Foo", ConstructorHandler { call, ctx ->
            val s = (call.args.firstOrNull() as? Sd2Value.VString)?.value
                ?: ctx.error("E5002", "foo requires a string", call.location)
            s.uppercase()
        })
        // wrap(x) -> resolves inner, appends '!'
        builder.register("wrap", "test.Wrap", ConstructorHandler { call, ctx ->
            val inner = call.args.firstOrNull() ?: ctx.error("E5002", "wrap requires one arg", call.location)
            val rv = ctx.resolve(inner)
            when (rv) {
                is Sd2Value.VObject -> {
                    val s = rv.value as? String ?: ctx.error("E5002", "expected string payload", call.location)
                    s + "!"
                }
                is Sd2Value.VString -> rv.value + "!"
                else -> ctx.error("E5002", "unsupported inner value", call.location)
            }
        })
        val reg = builder.build()

        val input = """
            item {
              b = wrap(foo("x"))
            }
        """.trimIndent()
        val attrs = readAttributes(input, Sd2ReaderConfig(constructorRegistry = reg))
        val b = attrs["b"] ?: fail("missing attribute b")
        val vb = assertIs<Sd2Value.VObject>(b)
        assertEquals(listOf("test","Wrap"), vb.type.parts.map { it.text })
        assertEquals("X!", vb.value as String)
    }

    @Test
    fun resolves_attribute_constructor_body() {
        data class Person(val name: String)
        val builder = ConstructorRegistryBuilder()
        builder.register("person", "test.Person", ConstructorHandler { call, ctx ->
            val name = (call.attrs?.get("name") as? Sd2Value.VString)?.value
                ?: ctx.error("E5002", "person requires 'name'", call.location)
            Person(name)
        })
        val reg = builder.build()
        val input = """
            item {
              c = person { name = "Ada" 
              }
            }
        """.trimIndent()

        val attrs = readAttributes(input, Sd2ReaderConfig(constructorRegistry = reg))
        val c = attrs["c"] ?: fail("missing attribute c")
        val v = assertIs<Sd2Value.VObject>(c)
        assertEquals(listOf("test","Person"), v.type.parts.map { it.text })
        val p = v.value as Person
        assertEquals("Ada", p.name)
    }

    @Test
    fun unknown_constructor_policy_keep_raw_default() {
        val input = """
            item {
              d = unknown("x")
            }
        """.trimIndent()
        val attrs = readAttributes(input, Sd2ReaderConfig())
        val d = attrs["d"] ?: fail("missing attribute d")
        assertIs<Sd2Value.VConstructorTuple>(d)
    }

    @Test
    fun unknown_constructor_policy_error() {
        val input = """
            item {
              d = unknown("x")
            }
        """.trimIndent()
        val emptyReg = ConstructorRegistryBuilder().build()
        try {
            readAttributes(
                input,
                Sd2ReaderConfig(
                    constructorRegistry = emptyReg,
                    unknownConstructorPolicy = UnknownConstructorPolicy.Error
                )
            )
            fail("expected error for unknown constructor")
        } catch (e: ParseError) {
            assertTrue(e.code.startsWith("E5"), "expected E5xxx, was ${e.code}")
        }
    }
}
