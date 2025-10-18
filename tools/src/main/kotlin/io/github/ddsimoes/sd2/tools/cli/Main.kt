package io.github.ddsimoes.sd2.tools.cli

import io.github.ddsimoes.sd2.tools.Sd2Formatter
import io.github.ddsimoes.sd2.tools.Sd2Validator
import java.io.File
import kotlin.system.exitProcess

private fun usage(): Nothing {
    System.err.println(
        """
        Usage:
          sd2 format <input.sd2> [output.sd2]
          sd2 validate <input.sd2>

        Examples:
          sd2 format path/file.sd2
          sd2 format path/file.sd2 path/out.sd2
          sd2 validate path/file.sd2
        """.trimIndent()
    )
    exitProcess(2)
}

private fun readAllStdin(): String = generateSequence { readLine() }.joinToString("\n") + "\n"

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    when (args[0]) {
        "format" -> {
            if (args.size < 2 || args.size > 3) usage()
            val inPlace = args.size == 3 && (args[1] == "--in-place" || args[1] == "-i")
            val path = if (inPlace) args[2] else args[1]
            val input = if (path == "-") readAllStdin() else File(path).readText()
            val formatted = Sd2Formatter.format(input)
            when {
                inPlace && path != "-" -> File(path).writeText(formatted)
                args.size == 3 && !inPlace -> File(args[2]).writeText(formatted)
                else -> print(formatted)
            }
        }
        "validate" -> {
            if (args.size !in 2..3) usage()
            val recover = args.size == 3 && args[1] == "--recover"
            val path = if (recover) args[2] else args[1]
            val input = if (path == "-") readAllStdin() else File(path).readText()
            val issues = if (recover) Sd2Validator.validateAll(input) else Sd2Validator.validate(input)
            if (issues.isEmpty()) {
                println("OK")
            } else {
                for (iss in issues) {
                    println("${iss.location.line}:${iss.location.column}: ${iss.message}")
                }
                exitProcess(1)
            }
        }
        else -> usage()
    }
}
