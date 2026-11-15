package com.motonav.prototype

/** Parses "lat,lng" text typed into the app. */
object Coords {
    fun parse(text: String): Pair<Double, Double>? {
        val parts = text.split(",").map { it.trim() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }
}
