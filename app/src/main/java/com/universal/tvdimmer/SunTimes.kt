package com.universal.tvdimmer

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * LeoDimmer v2.4 -- sunset and sunrise, offline.
 *
 * This is the standard sunrise equation (the arithmetic behind NOAA's solar
 * calculator): pure maths, no network, no location permission, no dependency.
 * Given a date and a position it returns sunrise and sunset to within a couple
 * of minutes, which is all the "Night at sunset" schedule needs.
 *
 * A null result means the sun does not cross the horizon that day -- polar day
 * or polar night -- where a fixed hour beats a wrong one.
 */
object SunTimes {

    private const val DEG = Math.PI / 180.0
    private const val JULIAN_EPOCH = 2440587.5
    private const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * Sunrise and sunset for the UTC day containing [dayMillis], as epoch
     * milliseconds. Null when the sun stays up (or down) all day.
     */
    fun forDay(dayMillis: Long, latitude: Double, longitude: Double): Pair<Long, Long>? {
        if (latitude.isNaN() || longitude.isNaN()) return null
        val noon = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = dayMillis
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val julian = noon / MILLIS_PER_DAY + JULIAN_EPOCH

        val daysSinceJ2000 = julian - 2451545.0 + 0.0008
        val meanSolarNoon = daysSinceJ2000 - longitude / 360.0
        val meanAnomaly = (357.5291 + 0.98560028 * meanSolarNoon) % 360.0
        val equationOfCentre = 1.9148 * sin(meanAnomaly * DEG) +
            0.02 * sin(2 * meanAnomaly * DEG) +
            0.0003 * sin(3 * meanAnomaly * DEG)
        val eclipticLongitude = (meanAnomaly + equationOfCentre + 180.0 + 102.9372) % 360.0
        val solarTransit = 2451545.0 + meanSolarNoon +
            0.0053 * sin(meanAnomaly * DEG) -
            0.0069 * sin(2 * eclipticLongitude * DEG)
        val declination = asin(sin(eclipticLongitude * DEG) * sin(23.44 * DEG))

        val cosHourAngle = (sin(-0.833 * DEG) - sin(latitude * DEG) * sin(declination)) /
            (cos(latitude * DEG) * cos(declination))
        if (cosHourAngle.isNaN() || abs(cosHourAngle) > 1.0) return null
        val hourAngle = acos(cosHourAngle) / DEG

        return toMillis(solarTransit - hourAngle / 360.0) to
            toMillis(solarTransit + hourAngle / 360.0)
    }

    private fun toMillis(julian: Double): Long = ((julian - JULIAN_EPOCH) * MILLIS_PER_DAY).toLong()

    /**
     * Is the sun down at [now]? Polar days fall back to a clock hour, so the
     * schedule still does something sensible above the Arctic circle.
     */
    fun isNight(now: Long, latitude: Double, longitude: Double): Boolean {
        val times = forDay(now, latitude, longitude) ?: return fallbackIsNight(now)
        val (sunrise, sunset) = times
        return now < sunrise || now >= sunset
    }

    private fun fallbackIsNight(now: Long): Boolean {
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return hour >= 19 || hour < 6
    }
}
