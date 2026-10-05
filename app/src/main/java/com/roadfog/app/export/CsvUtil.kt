package com.roadfog.app.export

internal object CsvUtil {
    fun escape(value: String): String {
        // Road names come from a remote service. Keep spreadsheet applications from
        // interpreting those names as formulas when the CSV is opened.
        val first = value.dropWhile {
            it.isWhitespace() || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
        }.firstOrNull()
        val safe = if (first in listOf('=', '+', '-', '@') ||
            value.firstOrNull() in listOf('\t', '\r', '\n')
        ) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else safe
    }
}
