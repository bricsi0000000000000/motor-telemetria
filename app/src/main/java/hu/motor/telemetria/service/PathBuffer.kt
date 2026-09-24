package hu.motor.telemetria.service

data class PathPoint(val lat: Double, val lon: Double, val segment: Int,
    val speedMps: Float = 0f, val accuracy: Float = 0f, val time: Long = 0L)

/**
 * A futó mérés nyomvonala memóriában, hogy a térkép ne a DB-ből olvasson
 * másodpercenként. A UI csak az utoljára kirajzolt index utáni pontokat kéri le,
 * így a polyline bővítése O(új pontok) és nem O(összes pont).
 */
object PathBuffer {

    private val points = ArrayList<PathPoint>()

    @Synchronized
    fun add(point: PathPoint) {
        points.add(point)
    }

    @Synchronized
    fun addAll(newPoints: List<PathPoint>) {
        points.addAll(newPoints)
    }

    @Synchronized
    fun size(): Int = points.size

    /** A [fromIndex]-től (bezárólag) a végéig tartó pontok másolata. */
    @Synchronized
    fun from(fromIndex: Int): List<PathPoint> =
        if (fromIndex >= points.size) emptyList()
        else ArrayList(points.subList(fromIndex, points.size))

    @Synchronized
    fun last(): PathPoint? = points.lastOrNull()

    @Synchronized
    fun clear() {
        points.clear()
    }
}
