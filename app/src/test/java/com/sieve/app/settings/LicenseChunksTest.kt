package com.sieve.app.settings

import com.sieve.app.ui.settings.LICENSE_CHUNK_CHARS
import com.sieve.app.ui.settings.licenseChunks
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * About's license cards show each text as a stack of short Text pieces rather than one Text: an 80 KB license is
 * ~3,900 wrapped rows at 2x font scale (over 300,000 px on a 3x-density phone), past the 262,143 px Compose can
 * represent in a single layout constraint. Pieces are cut between lines, so the stack reads as the original text.
 */
class LicenseChunksTest {

    @Test fun joiningThePiecesGivesTheTextBackExactly() {
        for (text in listOf(
            "",
            "one line",
            "a\nb\nc",
            "a\n\nb\n\n\nc\n",
            "\nleading blank",
            "trailing blanks\n\n",
            (1..500).joinToString("\n") { "line $it of some license text that goes on a while" },
        )) {
            for (max in listOf(1, 7, 20, 100, 3000)) {
                assertEquals(text, licenseChunks(text, max).joinToString("\n"), "max=$max, text=${text.take(20)}")
            }
        }
    }

    @Test fun piecesAreCutBetweenLinesAndStayWithinTheLimit() {
        val text = (1..200).joinToString("\n") { "line number $it" }
        val chunks = licenseChunks(text, maxChars = 100)
        assertTrue(chunks.size > 1)
        for (c in chunks) assertTrue(c.length <= 100, "piece of ${c.length} chars")
        // A piece never starts or ends mid-line: every one of its lines is a whole line of the original.
        val originalLines = text.split('\n').toSet()
        for (c in chunks) for (l in c.split('\n')) assertTrue(l in originalLines, "'$l' is not a whole original line")
    }

    @Test fun aSingleOverlongLineIsKeptWholeRatherThanSplit() {
        val long = "x".repeat(250)
        assertEquals(listOf("a", long, "b"), licenseChunks("a\n$long\nb", maxChars = 100))
    }

    @Test fun shortTextStaysOnePiece() {
        assertEquals(listOf("short\ntext"), licenseChunks("short\ntext"))
    }

    @Test fun everyShippedLicenseIsSplitIntoBoundedPieces() {
        val dir = File("src/main/assets/licenses")
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }!!.toList()
        assertTrue(files.size >= 8, "expected the license assets in ${dir.absolutePath}")
        for (f in files) {
            val text = f.readText().replace("\r\n", "\n")
            val chunks = licenseChunks(text)
            assertEquals(text, chunks.joinToString("\n"), f.name)
            for (c in chunks) {
                // Bounded unless one line alone is longer (none of ours is).
                assertTrue(c.length <= LICENSE_CHUNK_CHARS || '\n' !in c, "${f.name}: piece of ${c.length} chars")
            }
            // Anything past one screen of text must not be a single Text.
            if (text.length > 2 * LICENSE_CHUNK_CHARS) assertTrue(chunks.size > 1, "${f.name} is still one piece")
        }
    }
}
