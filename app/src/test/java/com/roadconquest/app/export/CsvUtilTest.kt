package com.roadconquest.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvUtilTest {
    @Test fun neutralizesFormulasIncludingLeadingWhitespace() {
        listOf("=1+1", "+1", "-1", "@SUM(A1)", "  =1", "\tvalue").forEach {
            assertEquals("'$it", CsvUtil.escape(it))
        }
    }

    @Test fun preservesNormalNamesAndEscapesQuotesCommasAndNewlines() {
        assertEquals("Main Street", CsvUtil.escape("Main Street"))
        assertEquals("\"Main, \"\"North\"\"\nStreet\"", CsvUtil.escape("Main, \"North\"\nStreet"))
        assertEquals("", CsvUtil.escape(""))
    }

    @Test fun neutralizesFormulasHiddenAfterUnicodeFormatOrControlCharacters() {
        listOf("\uFEFF=1+1", "\u200B@SUM(A1)", "\u0000+1", "\u200B  -1").forEach {
            assertEquals("'$it", CsvUtil.escape(it))
        }
    }
}
