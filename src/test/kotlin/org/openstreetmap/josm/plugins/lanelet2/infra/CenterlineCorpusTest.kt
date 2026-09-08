package org.openstreetmap.josm.plugins.lanelet2.infra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Differential test of [Centerline] against the original Jython implementation.
 *
 * [CenterlineTest] covers hand-written structural cases; this pins the port
 * against a generated corpus of pseudo-random and degenerate border pairs whose
 * expected values come from `core/infra/lanelet2_centerline_calculate.py`. It
 * catches drift that hand-picked cases would miss, notably candidate ordering
 * on distance ties and the misaligned-bounds bail-out.
 *
 * Regenerate with `python3 testdata/centerline/gen_centerline_corpus.py`.
 */
class CenterlineCorpusTest {

    private data class Case(
        val line: Int,
        val left: List<LonLat>,
        val right: List<LonLat>,
        val expected: List<LonLat>,
    )

    private fun parsePts(field: String): List<LonLat> {
        if (field.isEmpty()) return emptyList()
        return field.split(';').map { pt ->
            val (lon, lat) = pt.split(',')
            LonLat(lon.toDouble(), lat.toDouble())
        }
    }

    private fun cases(): List<Case> {
        val text = requireNotNull(javaClass.getResourceAsStream("/centerline-corpus.txt")) {
            "centerline-corpus.txt missing; run testdata/centerline/gen_centerline_corpus.py"
        }.bufferedReader().readText()

        return text.lineSequence()
            .mapIndexed { idx, raw -> (idx + 1) to raw.trim() }
            .filter { (_, l) -> l.isNotEmpty() && !l.startsWith("#") }
            .map { (lineNo, l) ->
                val fields = l.split('|')
                require(fields.size == 3) { "malformed corpus line $lineNo" }
                Case(lineNo, parsePts(fields[0]), parsePts(fields[1]), parsePts(fields[2]))
            }
            .toList()
    }

    @Test
    fun corpusIsPresentAndSubstantial() {
        val cases = cases()
        assertTrue(cases.size >= 200, "expected a substantial corpus, got ${cases.size}")
    }

    @TestFactory
    fun matchesTheOriginalJythonImplementation(): List<DynamicTest> = cases().map { case ->
        DynamicTest.dynamicTest("line ${case.line} (${case.left.size}x${case.right.size})") {
            val actual = Centerline.calculateCenterlinePoints(case.left, case.right)
            assertEquals(
                case.expected.size,
                actual.size,
                "vertex count: expected=${case.expected} actual=$actual",
            )
            for (i in case.expected.indices) {
                assertEquals(case.expected[i].lon, actual[i].lon, 1e-12, "lon[$i] of ${case.expected}")
                assertEquals(case.expected[i].lat, actual[i].lat, 1e-12, "lat[$i] of ${case.expected}")
            }
        }
    }
}
