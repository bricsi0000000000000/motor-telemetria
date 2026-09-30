package hu.motor.telemetria.net

import hu.motor.telemetria.service.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A terület-látogatás a telefonon ugyanazt adja, mint a szerveren – ugyanazok
 * az esetek, mint a `server/test/areas.test.js`-ben. Ha itt más jönne ki, net
 * nélkül mást mutatna a térkép, mint a szerver.
 */
class AreaVisitsTest {

    private val square = listOf(
        doubleArrayOf(47.0, 17.0), doubleArrayOf(47.0, 17.0013),
        doubleArrayOf(47.0009, 17.0013), doubleArrayOf(47.0009, 17.0)
    )
    private val innerBox = listOf(
        doubleArrayOf(47.0006, 17.0009), doubleArrayOf(47.0006, 17.0012),
        doubleArrayOf(47.0008, 17.0012), doubleArrayOf(47.0008, 17.0009)
    )
    private val plaza = MapArea(1, "Pláza", "SHOP", square, 47.00045, 17.00065, null)
    private val shop = MapArea(2, "Bolt", "SHOP", innerBox, 47.0007, 17.00105, null)

    private val outside = 47.003 to 17.0
    private val inOuter = 47.0002 to 17.0002
    private val inInner = 47.0007 to 17.001

    /** Pontok ötmásodpercenként egy helyen, adott sebességgel. */
    private fun stay(from: Long, to: Long, at: Pair<Double, Double>, speed: Float = 0f, accuracy: Float = 6f) =
        (from..to step 5000L).map { PathPoint(at.first, at.second, 0, speed, accuracy, it) }

    private fun summary(points: List<PathPoint>, areas: List<MapArea>) =
        ObservedStopRepository.visits(points, areas).map { it.name to it.durationMs!! / 1000 }

    @Test
    fun steppingIntoAnInnerAreaLeavesTheOuterOne() {
        val points = stay(0, 5000, outside, 8f) +
            stay(10000, 60000, inOuter) + stay(65000, 300000, inOuter, 1.3f) +
            // 300 és 420 mp között nincs GPS (épület): a pláza számol tovább.
            stay(420000, 540000, inInner) + stay(545000, 655000, inInner, 1.0f) +
            stay(660000, 840000, inOuter, 1.3f) + stay(845000, 860000, outside, 8f)
        assertEquals(listOf("Pláza" to 595L, "Bolt" to 240L), summary(points, listOf(plaza, shop)))
    }

    @Test
    fun leavingAndComingBackIsSummed() {
        val points = stay(0, 120000, inOuter) + stay(125000, 400000, outside, 9f) + stay(405000, 525000, inOuter, 1f)
        assertEquals(listOf("Pláza" to 245L), summary(points, listOf(plaza)))
    }

    @Test
    fun ridingThroughOrAMomentInsideIsNotAVisit() {
        assertEquals(emptyList<Any>(), summary(stay(0, 90000, inOuter, 6f) + stay(95000, 100000, outside, 8f), listOf(plaza)))
        assertEquals(emptyList<Any>(), summary(stay(0, 20000, inOuter) + stay(25000, 60000, outside, 8f), listOf(plaza)))
    }

    @Test
    fun inaccurateIndoorFixesDoNotMoveYouOut() {
        val points = stay(0, 95000, inOuter) + stay(100000, 200000, outside, accuracy = 80f) +
            stay(205000, 300000, inOuter, 1f) + stay(305000, 310000, outside, 8f)
        assertEquals(listOf("Pláza" to 305L), summary(points, listOf(plaza)))
    }

    @Test
    fun trafficAreasAreNotVisits() {
        val junction = MapArea(3, "Kereszteződés", "SIGNAL", square, 47.00045, 17.00065, null)
        assertEquals(emptyList<Any>(), summary(stay(0, 120000, inOuter) + stay(125000, 130000, outside, 8f), listOf(junction)))
    }
}
