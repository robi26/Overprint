package ch.steigis.overprint.domain.model

data class GeoPoint(val lat: Double, val lon: Double)

data class GpsTrack(
    val activityId: String,
    val points: List<GeoPoint>,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    fun intersects(south: Double, north: Double, west: Double, east: Double): Boolean {
        if (maxLat < south || minLat > north || maxLon < west || minLon > east) return false
        if (points.any { it.lat in south..north && it.lon in west..east }) return true
        return maxLat >= south && minLat <= north && maxLon >= west && minLon <= east
    }
}
