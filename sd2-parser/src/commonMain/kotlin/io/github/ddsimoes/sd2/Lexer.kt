package io.github.ddsimoes.sd2

import kotlin.compareTo

internal enum class TKind {
    LBRACE, RBRACE, LBRACK, RBRACK, LPAREN, RPAREN,
    COMMA, COLON, EQUALS, DOT, LT, GT, HASH, BANG, AT, PIPE,
    IDENT, BACKTICK_IDENT, STRING, INT, FLOAT, BOOL, NULL,
    NEWLINE, EOF,
}

internal data class Token(
    val kind: TKind,
    val text: String,
    val loc: Location,
)

internal class Lexer(private val src: Sd2Source) {
    private var ch: Int = -2
    private var line = 1
    private var col = 0
    private var off = 0

    private fun readChar(): Int {
        val c = src.read()
        if (c != 0) {
            off += 1
            if (c == '\n'.code) {
                line += 1
                col = 0
            } else {
                col += 1
            }
        }
        return c
    }

    private fun ensure() {
        if (ch == -2) ch = readChar()
    }

    private fun adv(): Int {
        ensure()
        val c = ch
        ch = readChar()
        return c
    }

    private fun loc(): Location = Location(line, if (col == 0) 1 else col, off)

    private fun isIdentStart(c: Int) = c == '_'.code || (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code)
    private fun isIdentPart(c: Int) = isIdentStart(c) || (c in '0'.code..'9'.code) || c == '-'.code

    private fun skipSpacesAndComments(): Token? {
        while (true) {
            ensure()
            when (ch) {
                ' '.code, '\t'.code, '\r'.code -> { adv(); continue }
                '/'.code -> {
                    // comments //... or /* ... */
                    val start = loc()
                    adv()
                    if (ch == '/'.code) {
                        while (ch >= 0 && ch != '\n'.code) adv()
                        continue
                    } else if (ch == '*'.code) {
                        adv()
                        var prev = -1
                        while (ch >= 0) {
                            if (prev == '*'.code && ch == '/'.code) { adv(); break }
                            prev = adv()
                        }
                        continue
                    } else {
                        // not a comment, return slash as unknown -> treat as IDENT start fallback
                        return Token(TKind.IDENT, "/", start)
                    }
                }
                '\n'.code -> {
                    val t = Token(TKind.NEWLINE, "\n", loc())
                    adv()
                    return t
                }
                else -> return null
            }
        }
    }

    fun next(): Token {
        skipSpacesAndComments()?.let { return it }
        ensure()
        val start = loc()
        return when (ch) {
            -1 -> Token(TKind.EOF, "", start)
            '{'.code -> { adv(); Token(TKind.LBRACE, "{", start) }
            '}'.code -> { adv(); Token(TKind.RBRACE, "}", start) }
            '['.code -> { adv(); Token(TKind.LBRACK, "[", start) }
            ']'.code -> { adv(); Token(TKind.RBRACK, "]", start) }
            '('.code -> { adv(); Token(TKind.LPAREN, "(", start) }
            ')'.code -> { adv(); Token(TKind.RPAREN, ")", start) }
            ','.code -> { adv(); Token(TKind.COMMA, ",", start) }
            ':'.code -> { adv(); Token(TKind.COLON, ":", start) }
            '='.code -> { adv(); Token(TKind.EQUALS, "=", start) }
            '.'.code -> { adv(); Token(TKind.DOT, ".", start) }
            '<'.code -> { adv(); Token(TKind.LT, "<", start) }
            '>'.code -> { adv(); Token(TKind.GT, ">", start) }
            '#'.code -> { adv(); Token(TKind.HASH, "#", start) }
            '!'.code -> { adv(); Token(TKind.BANG, "!", start) }
            '"'.code -> stringToken(start)
            '`'.code -> backtickIdent(start)
            '@'.code -> { adv(); foreignToken(start) }
            '|'.code -> { adv(); Token(TKind.PIPE, "|", start) }
            in '0'.code..'9'.code, '+'.code, '-'.code -> numberToken(start)
            else -> {
                if (isIdentStart(ch)) identToken(start) else {
                    // fallback: consume one char as IDENT to avoid infinite loop on unknown char
                    val c = adv()
                    Token(TKind.IDENT, c.toChar().toString(), start)
                }
            }
        }
    }

    private fun backtickIdent(start: Location): Token {
        // consume starting backtick `
        adv()
        val sb = StringBuilder()
        while (ch >= 0 && ch != '`'.code && ch != '\n'.code && ch != '\r'.code) {
            sb.append(ch.toChar()); adv()
        }
        if (ch == '\n'.code || ch == '\r'.code || ch < 0) {
            throw ParseError("E6002", "newline in backtick identifier", start)
        }
        if (ch == '`'.code) adv() // closing backtick
        return Token(TKind.BACKTICK_IDENT, sb.toString(), start)
    }

    private fun foreignToken(start: Location): Token {
        // After '@', next must be one of '"', '\'', '[', '{' or triple of those with mandatory newline
        ensure()
        val opener = ch
        if (!(opener == '"'.code || opener == '\''.code || opener == '['.code || opener == '{'.code)) {
            throw ParseError("E4002", "invalid delimiter after '@'", start)
        }

        // Consume first opener
        adv()

        // Check for triple delimiter
        if (ch == opener) {
            adv()
            if (ch == opener) {
                adv()
                // triple: require immediate newline after opener sequence
                if (ch != '\n'.code) {
                    // Not a valid triple start; fall back to single-delimited using the first opener seen
                } else {
                    // Valid triple: consume newline and capture until matching triple closer
                    adv()
                    val content = StringBuilder()
                    val closer = when (opener) {
                        '['.code -> ']'.code
                        '{'.code -> '}'.code
                        else -> opener
                    }
                    var match = 0
                    while (ch >= 0) {
                        if (ch == closer) {
                            match++
                            if (match == 3) { adv(); return Token(TKind.AT, content.toString(), start) }
                        } else {
                            if (match > 0) { repeat(match) { content.append(closer.toChar()) }; match = 0 }
                            content.append(ch.toChar())
                        }
                        adv()
                    }
                    throw ParseError("E4001", "unterminated foreign code block", start)
                }
            } else {
                // was double but not triple; will fall back to single-delimited
            }
        }

        // Single-delimited: read until matching closer on same line (no newline allowed)
        val close = when (opener) {
            '"'.code -> '"'
            '\''.code -> '\''
            '['.code -> ']'
            '{'.code -> '}'
            else -> '"'
        }
        val content = StringBuilder()
        while (ch >= 0) {
            if (ch == '\n'.code || ch == '\r'.code) {
                throw ParseError("E4001", "unterminated foreign code block", start)
            }
            if (ch == close.code) { adv(); return Token(TKind.AT, content.toString(), start) }
            content.append(ch.toChar()); adv()
        }
        throw ParseError("E4001", "unterminated foreign code block", start)
    }

    private fun identToken(start: Location): Token {
        val sb = StringBuilder()
        while (ch >= 0 && isIdentPart(ch)) { sb.append(ch.toChar()); adv() }
        val s = sb.toString()
        return when (s) {
            "true", "false" -> Token(TKind.BOOL, s, start)
            "null" -> Token(TKind.NULL, s, start)
            else -> Token(TKind.IDENT, s, start)
        }
    }

    private fun numberToken(start: Location): Token {
        val sb = StringBuilder()
        var hasDot = false
        var hasExp = false
        val hadSign = if (ch == '+'.code || ch == '-'.code) { sb.append(ch.toChar()); adv(); true } else false

        if (ch == '0'.code) {
            // consume '0' and inspect next char
            sb.append('0'); adv()
            if (ch == 'x'.code || ch == 'X'.code) {
                if (hadSign) throw ParseError("E7001", "signed hex/binary integers are not allowed", start)
                sb.append('x'); adv()
                while (ch >= 0 && (ch in '0'.code..'9'.code || ch in 'a'.code..'f'.code || ch in 'A'.code..'F'.code || ch == '_'.code)) { sb.append(ch.toChar()); adv() }
                return Token(TKind.INT, sb.toString(), start)
            }
            if (ch == 'b'.code || ch == 'B'.code) {
                if (hadSign) throw ParseError("E7001", "signed hex/binary integers are not allowed", start)
                sb.append('b'); adv()
                while (ch >= 0 && (ch == '0'.code || ch == '1'.code || ch == '_'.code)) { sb.append(ch.toChar()); adv() }
                return Token(TKind.INT, sb.toString(), start)
            }
            // Not hex/binary; fall through to decimal/float handling with initial '0' already in sb
        }

        while (ch >= 0) {
            val c = ch
            if (c in '0'.code..'9'.code || c == '_'.code) {
                sb.append(c.toChar()); adv(); continue
            }
            if (c == '.'.code && !hasDot && !hasExp) { hasDot = true; sb.append('.'); adv(); continue }
            if ((c == 'e'.code || c == 'E'.code) && !hasExp) {
                hasExp = true; sb.append(c.toChar()); adv()
                if (ch == '+'.code || ch == '-'.code) { sb.append(ch.toChar()); adv() }
                continue
            }
            break
        }
        return Token(if (hasDot || hasExp) TKind.FLOAT else TKind.INT, sb.toString(), start)
    }

    private fun peek(): Int {
        ensure()
        return ch
    }

    private fun stringToken(start: Location): Token {
        // support double-quoted with escapes and triple-quoted form """..."""
        val sb = StringBuilder()
        adv() // skip first '"'
        // Check for triple-quote
            if (ch == '"'.code) {
                adv()
                if (ch == '"'.code) {
                    adv() // we are inside triple-quoted string
                    // mandatory immediate newline after opening per spec
                    if (ch != '\n'.code) {
                        // Not a valid triple-quoted start, treat as simple string starting with ""
                        sb.append('"').append('"')
                    } else {
                        adv() // consume the newline (not part of content)
                        var match = 0
                        while (ch >= 0) {
                            if (ch == '"'.code) {
                                match++
                                if (match == 3) {
                                    adv() // consume last quote after closing
                                    break
                                }
                            } else {
                                if (match > 0) {
                                    repeat(match) { sb.append('"') }
                                    match = 0
                                }
                                sb.append(ch.toChar())
                            }
                            adv()
                        }
                        val raw = sb.toString()
                        val norm = normalizeMultiline(raw)
                        return Token(TKind.STRING, norm, start)
                    }
                } else {
                    // it was just two quotes in a row, treat first as content
                    sb.append('"')
                }
        }
        // simple quoted string with escapes
        while (ch >= 0 && ch != '"'.code) {
            if (ch == '\\'.code) {
                adv()
                when (ch) {
                    'n'.code -> { sb.append('\n'); adv() }
                    't'.code -> { sb.append('\t'); adv() }
                    'r'.code -> { sb.append('\r'); adv() }
                    '"'.code -> { sb.append('"'); adv() }
                    '\\'.code -> { sb.append('\\'); adv() }
                    'u'.code -> {
                        sb.append("\\u"); adv()
                        if (ch == '{'.code) {
                            sb.append('{'); adv()
                            while (ch >= 0 && ch != '}'.code) { sb.append(ch.toChar()); adv() }
                            if (ch == '}'.code) { sb.append('}'); adv() }
                        }
                    }
                    else -> { sb.append('\\'); sb.append(ch.toChar()); adv() }
                }
            } else {
                sb.append(ch.toChar()); adv()
            }
        }
        if (ch == '"'.code) adv() // closing
        return Token(TKind.STRING, sb.toString(), start)
    }

    private fun normalizeMultiline(raw: String): String {
        // Normalize CRLF/CR to LF
        var s = raw.replace("\r\n", "\n").replace("\r", "\n")
        // Handle line joins: backslash immediately before newline (no spaces)
        run {
            val b = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length && s[i + 1] == '\n') {
                    // skip backslash and newline, and also skip following indentation spaces/tabs
                    i += 2
                    while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
                    continue
                }
                b.append(c)
                i++
            }
            s = b.toString()
        }
        // Dedent by indentation of first non-empty line
        val lines = s.split('\n')
        var indent: String? = null
        for (line in lines) {
            if (line.isNotEmpty() && line.any { it != ' ' && it != '\t' }) {
                // capture leading spaces/tabs
                val i = line.indexOfFirst { it != ' ' && it != '\t' }
                indent = if (i <= 0) "" else line.substring(0, i)
                break
            }
        }
        if (indent.isNullOrEmpty()) return s
        val dedented = lines.joinToString("\n") { ln ->
            if (ln.startsWith(indent!!)) ln.substring(indent!!.length) else ln
        }
        return dedented
    }
}
