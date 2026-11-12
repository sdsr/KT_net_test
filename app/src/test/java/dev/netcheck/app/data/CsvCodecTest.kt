package dev.netcheck.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvCodecTest {
    @Test fun keepsCommasQuotesAndNewlinesInsideOneCell() {
        assertEquals("\"a,b\"\"c\nnext\"", CsvCodec.cell("a,b\"c\nnext"))
    }
    @Test fun preservesMissingVersusNumericZeroAndNegativeSignal() {
        assertEquals(",\"0\",\"-95\"\r\n", CsvCodec.row(listOf(null,0,-95)))
    }
    @Test fun protectsSpreadsheetFormulaText() {
        assertEquals("\"'=HYPERLINK(\"\"https://example.com\"\")\"", CsvCodec.cell("=HYPERLINK(\"https://example.com\")"))
        assertEquals("\"'  +cmd\"", CsvCodec.cell("  +cmd"))
    }
}
