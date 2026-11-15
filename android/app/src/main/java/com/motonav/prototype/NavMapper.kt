package com.motonav.prototype

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.motonav.prototype.NavProtocol.Man
import com.motonav.prototype.NavProtocol.Mode

/** Pure mapping from Google Navigation SDK values to the MotoNav device protocol. */
object NavMapper {

    fun maneuver(sdk: Int): Int = when (sdk) {
        Maneuver.DEPART -> Man.DEPART
        Maneuver.DESTINATION -> Man.ARRIVE
        Maneuver.DESTINATION_LEFT -> Man.ARRIVE_LEFT
        Maneuver.DESTINATION_RIGHT -> Man.ARRIVE_RIGHT
        Maneuver.STRAIGHT, Maneuver.NAME_CHANGE -> Man.STRAIGHT

        Maneuver.TURN_LEFT -> Man.LEFT
        Maneuver.TURN_RIGHT -> Man.RIGHT
        Maneuver.TURN_KEEP_LEFT, Maneuver.FORK_LEFT -> Man.KEEP_LEFT
        Maneuver.TURN_KEEP_RIGHT, Maneuver.FORK_RIGHT -> Man.KEEP_RIGHT
        Maneuver.TURN_SLIGHT_LEFT -> Man.SLIGHT_LEFT
        Maneuver.TURN_SLIGHT_RIGHT -> Man.SLIGHT_RIGHT
        Maneuver.TURN_SHARP_LEFT -> Man.SHARP_LEFT
        Maneuver.TURN_SHARP_RIGHT -> Man.SHARP_RIGHT

        // Clockwise = turning right, counter-clockwise = turning left (true in left- and right-hand traffic).
        Maneuver.TURN_U_TURN_CLOCKWISE,
        Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
        Maneuver.OFF_RAMP_U_TURN_CLOCKWISE -> Man.UTURN_RIGHT

        Maneuver.TURN_U_TURN_COUNTERCLOCKWISE,
        Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
        Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE -> Man.UTURN_LEFT

        Maneuver.MERGE_UNSPECIFIED, Maneuver.MERGE_LEFT, Maneuver.MERGE_RIGHT,
        Maneuver.ON_RAMP_UNSPECIFIED -> Man.MERGE

        Maneuver.ON_RAMP_LEFT, Maneuver.ON_RAMP_KEEP_LEFT,
        Maneuver.ON_RAMP_SLIGHT_LEFT, Maneuver.ON_RAMP_SHARP_LEFT,
        Maneuver.OFF_RAMP_LEFT, Maneuver.OFF_RAMP_KEEP_LEFT,
        Maneuver.OFF_RAMP_SLIGHT_LEFT, Maneuver.OFF_RAMP_SHARP_LEFT -> Man.RAMP_LEFT

        Maneuver.ON_RAMP_RIGHT, Maneuver.ON_RAMP_KEEP_RIGHT,
        Maneuver.ON_RAMP_SLIGHT_RIGHT, Maneuver.ON_RAMP_SHARP_RIGHT,
        Maneuver.OFF_RAMP_RIGHT, Maneuver.OFF_RAMP_KEEP_RIGHT,
        Maneuver.OFF_RAMP_SLIGHT_RIGHT, Maneuver.OFF_RAMP_SHARP_RIGHT -> Man.RAMP_RIGHT

        Maneuver.OFF_RAMP_UNSPECIFIED -> Man.STRAIGHT

        Maneuver.FERRY_BOAT, Maneuver.FERRY_TRAIN -> Man.FERRY

        in Maneuver.ROUNDABOUT_CLOCKWISE..Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE -> Man.ROUNDABOUT

        else -> Man.NONE
    }

    fun isRoundabout(deviceManeuver: Int): Boolean = deviceManeuver == Man.ROUNDABOUT

    fun mode(sdkNavState: Int, arrived: Boolean): Int = when {
        arrived -> Mode.ARRIVED
        sdkNavState == NavState.ENROUTE -> Mode.NAV
        sdkNavState == NavState.REROUTING -> Mode.REROUTING
        else -> Mode.IDLE
    }

    /** Seconds -> whole minutes, rounded to nearest. */
    fun etaMinutes(seconds: Int?): Int = if (seconds == null || seconds <= 0) 0 else (seconds + 30) / 60

    /** m/s -> km/h in 0.1 units. */
    fun speedKmhX10(metersPerSecond: Float): Int =
        if (metersPerSecond <= 0f) 0 else Math.round(metersPerSecond * 3.6f * 10f)
}
