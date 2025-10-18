package io.github.ddsimoes.sd2.tools

import io.github.ddsimoes.sd2.*
import io.github.ddsimoes.sd2.Annotation
import kotlin.collections.iterator
import kotlin.text.iterator


object Sd2Formatter {
    fun format(input: String): String = format(StringSource(input))

    fun format(source: Sd2Source): String {
        val r = Sd2.reader(source)
        val out = StringBuilder()
        var indent = 0

        fun nl() { out.append('\n') }
        fun ind() { repeat(indent) { out.append("  ") } }

        fun printIdent(id: Identifier): String {
            val s = id.text
            val simple = Regex("[A-Za-z_][A-Za-z0-9_-]*")
            return if (simple.matches(s) && s !in setOf("true","false","null")) s else "`$s`"
        }

        fun printQName(q: QualifiedName): String = q.parts.joinToString(".") { printIdent(it) }

        fun printType(t: TypeExpr): String = buildString {
            append(printQName(t.name))
            if (t.typeArgs.isNotEmpty()) {
                append('<')
                append(t.typeArgs.joinToString(", ") { printType(it) })
                append('>')
            }
        }

        fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }

        fun printString(s: String): String =
            if (s.contains('\n')) buildString {
                append("\"\"\"")
                nl()
                s.split('\n').forEach { line ->
                    if (line.isNotEmpty()) ind()
                    append(line)
                    nl()
                }
                ind(); append("\"\"\"")
            } else "\"${esc(s)}\""

        fun printValue(v: Sd2Value): String = when (v) {
            is Sd2Value.VString -> printString(v.value)
            is Sd2Value.VInt -> v.value.toString()
            is Sd2Value.VFloat -> v.value.toString()
            is Sd2Value.VBool -> v.value.toString()
            is Sd2Value.VNull -> "null"
            is Sd2Value.VQualifiedName -> v.parts.joinToString(".") { part ->
                val simple = Regex("[A-Za-z_][A-Za-z0-9_-]*")
                if (simple.matches(part) && part !in setOf("true","false","null")) part else "`$part`"
            }
            is Sd2Value.VList -> v.items.joinToString(prefix = "[", postfix = "]", separator = ", ") { printValue(it) }
            is Sd2Value.VMap -> v.entries.entries.joinToString(prefix = "{", postfix = "}", separator = ", ") { (k, vv) -> "$k = ${printValue(vv)}" }
            is Sd2Value.VConstructor -> buildString {
                append(printQName(v.name)); append(" {"); nl(); indent++
                for ((k, vv) in v.attributes) { ind(); out.append("$k = ${printValue(vv)}"); nl() }
                indent--; ind(); out.append("}")
            }.toString()
            is Sd2Value.VForeign -> buildString {
                val prefix = v.constructor?.let { printQName(it) } ?: ""
                if (prefix.isNotEmpty()) append(prefix)
                append("@\"\"\"\n")
                append(v.content)
                append("\n\"\"\"")
            }
            is Sd2Value.VTuple -> buildString {
                val inner = v.items.joinToString(separator = ", ") { printValue(it) }
                if (v.items.size == 1) append("(").append(inner).append(",)") else append("(").append(inner).append(")")
            }
            is Sd2Value.VConstructorTuple -> v.args.joinToString(prefix = "${printQName(v.name)}(", postfix = ")", separator = ", ") { printValue(it) }
        }

        fun printAnnotations(anns: List<Annotation>) {
            for (a in anns) {
                ind(); out.append("#["); out.append(printQName(a.name));
                if (a.argsRaw != null) { out.append('(').append(a.argsRaw).append(')') }
                out.append("]"); nl()
            }
        }

        val pendingEndStack = ArrayDeque<String>()

        while (true) {
            val e = r.next()
            when (e) {
                is Sd2Event.StartDocument -> {}
                is Sd2Event.EndDocument -> break
                is Sd2Event.DocumentAnnotation -> {
                    ind(); out.append("#!["); out.append(printQName(e.name));
                    if (e.argsRaw != null) { out.append('(').append(e.argsRaw).append(')') }
                    out.append("]"); nl()
                }
                is Sd2Event.StartElement -> {
                    printAnnotations(e.annotations)
                    ind(); out.append(printIdent(e.keyword))
                    val _id = e.id
                    if (_id != null) { out.append(' ').append(printIdent(_id)) }
                    val _type = e.type
                    if (_type != null) { out.append(" : ").append(printType(_type)) }
                    if (e.qualifiers.isNotEmpty()) {
                        out.append(' ')
                        out.append(e.qualifiers.joinToString(" ") { q ->
                            buildString {
                                append(printIdent(q.name))
                                append(' ')
                                append(q.args.joinToString(", ") { printQName(it) })
                            }
                        })
                    }
                    out.append(" {"); nl(); indent++
                    pendingEndStack.addLast("}")
                }
                is Sd2Event.EndElement -> {
                    indent--; ind(); out.append(pendingEndStack.removeLast()); nl()
                }
                is Sd2Event.StartNamespace -> {
                    ind(); out.append('.'); out.append(printIdent(e.name)); out.append(" {"); nl(); indent++
                    pendingEndStack.addLast("}")
                }
                is Sd2Event.EndNamespace -> {
                    indent--; ind(); out.append(pendingEndStack.removeLast()); nl()
                }
                is Sd2Event.Attribute -> {
                    ind(); out.append(printIdent(e.name)); out.append(" = ")
                    val v = e.value
                    if (v != null) out.append(printValue(v)) else out.append("<streamed>")
                    nl()
                }
                else -> {}
            }
        }

        return out.toString().trimEnd() + "\n"
    }
}
