package io.github.ddsimoes.sd2

import kotlin.test.Test
import kotlin.test.assertEquals

class LexerTest {
    @Test
    fun binaryLiteralFollowedByNewline() {
        val input = """
            item X {
              b = 0b1010
            }
        """.trimIndent()
        val lex = Lexer(StringSource(input))
        data class K(val kind: TKind, val text: String)
        val toks = mutableListOf<K>()
        var guard = 0
        while (guard++ < 50) {
            val t = lex.next()
            toks += K(t.kind, t.text)
            if (t.kind == TKind.EOF) break
        }
        // Find INT token with text 0b1010
        val idx = toks.indexOfFirst { it.kind == TKind.INT && it.text == "0b1010" }
        val next = toks[idx + 1]
        if (next.kind != TKind.NEWLINE) {
            throw AssertionError("after INT '0b1010' got ${next.kind} with text='${next.text}'")
        }
    }
}
