package com.roadconquest.app.export

internal object CsvUtil {
    fun escape(value: String): String {
        // Road names come from a remote service. Keep spreadsheet applications from
        // interpreting those names as formulas when the CSV is opened.
        val first = value.firstOrNull {
            !it.isWhitespace() && !Character.isISOControl(it) &&
                Character.getType(it) != Character.FORMAT.toInt()
        }
        val leading = value.firstOrNull()
        val safe = if (first == '=' || first == '+' || first == '-' || first == '@' ||
            leading == '\t' || leading == '\r' || leading == '\n'
        ) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else safe
    }
}
