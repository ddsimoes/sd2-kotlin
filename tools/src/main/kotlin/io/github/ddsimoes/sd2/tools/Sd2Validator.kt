package io.github.ddsimoes.sd2.tools

import io.github.ddsimoes.sd2.Location
import io.github.ddsimoes.sd2.ParseError
import io.github.ddsimoes.sd2.Sd2
import io.github.ddsimoes.sd2.Sd2Event
import io.github.ddsimoes.sd2.Sd2ReaderConfig
import io.github.ddsimoes.sd2.Sd2Source
import io.github.ddsimoes.sd2.Sd2Value
import io.github.ddsimoes.sd2.*

data class Sd2Issue(val message: String, val location: Location)

object Sd2Validator {
    fun validate(input: String): List<Sd2Issue> = validate(StringSource(input))

    fun validate(source: Sd2Source): List<Sd2Issue> {
        val issues = mutableListOf<Sd2Issue>()
        val r = Sd2.reader(source)
        try {
            while (true) {
                val e = r.next()
                if (e is Sd2Event.EndDocument) break
            }
        } catch (pe: ParseError) {
            issues += Sd2Issue("${pe.code}: ${pe.message}", pe.location)
        } catch (t: Throwable) {
            issues += Sd2Issue(t.message ?: t::class.simpleName ?: "error", Location(0, 0, 0))
        }
        return issues
    }

    fun validateAll(input: String): List<Sd2Issue> = validateAll(StringSource(input))

    fun validateAll(source: Sd2Source): List<Sd2Issue> {
        val issues = mutableListOf<Sd2Issue>()
        val r = Sd2.reader(source,
            Sd2ReaderConfig(
                allowRecovery = true,
                onError = { e -> issues += Sd2Issue("${e.code}: ${e.message}", e.location) })
        )

        data class BodyFrame(
            val attrs: MutableSet<String> = linkedSetOf(),
            val elements: MutableSet<Pair<String, String?>> = linkedSetOf(),
            var seenNamespaceOrElement: Boolean = false,
        )
        val stack = ArrayDeque<BodyFrame>()

        fun checkValue(v: Sd2Value) {
            when (v) {
                is Sd2Value.VConstructorTuple -> validateTemporalConstructor(v)?.let { issues += it }
                is Sd2Value.VList -> v.items.forEach(::checkValue)
                is Sd2Value.VMap -> v.entries.values.forEach(::checkValue)
                is Sd2Value.VConstructor -> v.attributes.values.forEach(::checkValue)
                else -> {}
            }
        }

        while (true) {
            val e = try { r.next() } catch (pe: ParseError) {
                issues += Sd2Issue("${pe.code}: ${pe.message}", pe.location); continue
            }
            when (e) {
                is Sd2Event.StartDocument -> {}
                is Sd2Event.EndDocument -> break
                is Sd2Event.StartElement -> {
                    stack.addLast(BodyFrame())
                    // Register element for duplicate-detection in parent scope if present
                    val parent = stack.dropLast(1).lastOrNull()
                    if (parent != null) {
                        val key = e.keyword.text to e.id?.text
                        if (!parent.elements.add(key)) {
                            issues += Sd2Issue("E2004: duplicate element '${e.keyword.text} ${e.id?.text ?: ""}'.", e.location)
                        }
                        parent.seenNamespaceOrElement = true
                    }
                }
                is Sd2Event.EndElement -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                }
                is Sd2Event.StartNamespace -> {
                    stack.addLast(BodyFrame())
                    // Register namespace as 'seenNamespaceOrElement' in parent
                    val parent = stack.dropLast(1).lastOrNull()
                    if (parent != null) parent.seenNamespaceOrElement = true
                }
                is Sd2Event.EndNamespace -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                }
                is Sd2Event.Attribute -> {
                    val frame = stack.lastOrNull()
                    if (frame != null) {
                        if (frame.seenNamespaceOrElement) {
                            issues += Sd2Issue("E2002: attribute after namespace/sub-element", e.location)
                        }
                        val name = e.name.text
                        if (!frame.attrs.add(name)) {
                            issues += Sd2Issue("E2001: duplicate attribute '$name'", e.location)
                        }
                    }
                    run { val vv = e.value; if (vv != null) checkValue(vv) }
                }
                else -> {}
            }
        }
        return issues
    }

    private fun validateTemporalConstructor(v: Sd2Value.VConstructorTuple): Sd2Issue? {
        val simpleName = v.name.parts.joinToString(".")
        val lowered = simpleName.lowercase()
        if (lowered !in setOf("date", "time", "instant", "duration", "period")) return null
        // Expect at least one string argument
        val first = v.args.firstOrNull()
        val s = (first as? Sd2Value.VString)?.value ?: return Sd2Issue("E3001: invalid temporal format", v.location)
        val reDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        val reTime = Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$")
        val reInstant = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$")
        val reDuration = Regex("^P(?=.*[YMDTHS]).*") // at least one component

        fun fracDigitsOk(): Boolean {
            val m = Regex("\\.(\\d+)").find(s) ?: return true
            return m.groupValues[1].length <= 9
        }

        return when (lowered) {
            "date" -> if (reDate.matches(s)) null else Sd2Issue("E3001: invalid temporal format", v.location)
            "time" -> when {
                !fracDigitsOk() -> Sd2Issue("E3003: temporal fractions exceed 9 digits", v.location)
                reTime.matches(s) -> null
                else -> Sd2Issue("E3001: invalid temporal format", v.location)
            }
            "instant" -> when {
                !fracDigitsOk() -> Sd2Issue("E3003: temporal fractions exceed 9 digits", v.location)
                reInstant.matches(s) -> null
                else -> Sd2Issue("E3001: invalid temporal format", v.location)
            }
            "duration", "period" -> if (!reDuration.matches(s)) Sd2Issue("E3002: duration must have at least one component", v.location) else null
            else -> null
        }
    }
}
