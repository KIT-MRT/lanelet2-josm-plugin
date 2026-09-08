package org.openstreetmap.josm.plugins.lanelet2.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class SmoothCenterCorpusTest {

    private data class Case(
        val line: Int,
        val p0: SmoothCenter.LatLonPt,
        val p1: SmoothCenter.LatLonPt,
        val t0: SmoothCenter.TangentPt,
        val t1: SmoothCenter.TangentPt,
        val n: Int,
        val constraints: List<SmoothCenter.LatLonPt>,
        val expected: List<SmoothCenter.LatLonPt>,
    )

    private fun parsePt(field: String): SmoothCenter.LatLonPt {
        val (a, b) = field.split(',')
        return SmoothCenter.LatLonPt(a.toDouble(), b.toDouble())
    }

    private fun parsePts(field: String): List<SmoothCenter.LatLonPt> {
        if (field.isEmpty()) return emptyList()
        return field.split(';').map { parsePt(it) }
    }

    private fun parseTangent(field: String): SmoothCenter.TangentPt {
        val (a, b) = field.split(',')
        return SmoothCenter.TangentPt(a.toDouble(), b.toDouble())
    }

    private fun cases(): List<Case> {
        val text = requireNotNull(javaClass.getResourceAsStream("/smooth-corpus.txt")) {
            "smooth-corpus.txt missing; run testdata/smooth/gen_smooth_corpus.py"
        }.bufferedReader().readText()
        return text.lineSequence()
            .mapIndexed { idx, raw -> (idx + 1) to raw.trim() }
            .filter { (_, l) -> l.isNotEmpty() && !l.startsWith("#") }
            .map { (lineNo, l) ->
                val fields = l.split('|')
                require(fields.size == 7) { "malformed corpus line $lineNo" }
                Case(
                    lineNo,
                    parsePt(fields[0]),
                    parsePt(fields[1]),
                    parseTangent(fields[2]),
                    parseTangent(fields[3]),
                    fields[4].toInt(),
                    parsePts(fields[5]),
                    parsePts(fields[6]),
                )
            }
            .toList()
    }

    @Test
    fun corpusIsPresentAndSubstantial() {
        val cases = cases()
        assertTrue(cases.size >= 100, "expected a substantial corpus, got ${cases.size}")
    }

    @TestFactory
    fun matchesExtractedJythonSmoothMath(): List<DynamicTest> = cases().map { case ->
        DynamicTest.dynamicTest("line ${case.line} n=${case.n} c=${case.constraints.size}") {
            val actual = SmoothCenter.smoothBorder(
                case.p0,
                case.p1,
                case.t0,
                case.t1,
                case.n,
                case.constraints.ifEmpty { null },
            )
            assertEquals(case.expected.size, actual.size, "vertex count line ${case.line}")
            for (i in case.expected.indices) {
                assertEquals(case.expected[i].lat, actual[i].lat, 1e-12, "lat[$i] line ${case.line}")
                assertEquals(case.expected[i].lon, actual[i].lon, 1e-12, "lon[$i] line ${case.line}")
            }
        }
    }

    @Test
    fun py2RoundIsHalfAwayFromZero() {
        assertEquals(1, SmoothCenter.py2Round(0.5))
        assertEquals(3, SmoothCenter.py2Round(2.5))
        assertEquals(-1, SmoothCenter.py2Round(-0.5))
        assertEquals(-3, SmoothCenter.py2Round(-2.5))
        assertNotEquals(2, SmoothCenter.py2Round(2.5))
    }
}
