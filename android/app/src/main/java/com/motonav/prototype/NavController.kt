package com.motonav.prototype

import android.app.Activity
import android.content.Intent
import android.location.Location
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint

/**
 * Thin wrapper around the Google Navigation SDK:
 *  init (shows Google's terms dialog) -> setDestination -> startGuidance [-> simulator].
 * The turn-by-turn data itself arrives in [NavInfoReceivingService].
 */
class NavController(
    private val activity: Activity,
    private val log: (String) -> Unit,
) {
    private var navigator: Navigator? = null

    val isReady: Boolean get() = navigator != null

    private val locationListener = object : RoadSnappedLocationProvider.LocationListener {
        override fun onLocationChanged(location: Location) {
            NavBridge.speedKmhX10 = if (location.hasSpeed()) NavMapper.speedKmhX10(location.speed) else 0
        }
    }

    fun init(onReady: () -> Unit) {
        val app = activity.application
        // Keeps guidance alive in the background with a notification.
        NavigationApi.initForegroundServiceManagerMessageAndIntent(
            app, 1001, "MotoNav is guiding you", Intent(activity, MainActivity::class.java),
        )
        log("Starting Navigation SDK ${NavigationApi.getNavSDKVersion()} (accept Google's terms if asked)")
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(navigator: Navigator) {
                this@NavController.navigator = navigator
                navigator.setAudioGuidance(Navigator.AudioGuidance.SILENT)   // desk testing: no voice
                navigator.addArrivalListener(Navigator.ArrivalListener { event ->
                    if (event.isFinalDestination) {
                        NavBridge.arrived = true
                        NavBridge.sendMode(NavProtocol.Mode.ARRIVED)
                        log("Arrived at destination")
                    }
                })
                val registered = navigator.registerServiceForNavUpdates(
                    activity.packageName, NavInfoReceivingService::class.java.name, 1,
                )
                NavigationApi.getRoadSnappedLocationProvider(app)?.addLocationListener(locationListener)
                log("Navigator ready. Turn-by-turn feed registered = $registered")
                onReady()
            }

            override fun onError(errorCode: Int) {
                val why = when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED ->
                        "NOT_AUTHORIZED — check MAPS_API_KEY, that the Navigation SDK is enabled and billing is on"
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "TERMS_NOT_ACCEPTED — accept Google's terms"
                    NavigationApi.ErrorCode.NETWORK_ERROR -> "NETWORK_ERROR — check internet"
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "LOCATION_PERMISSION_MISSING — grant location"
                    else -> "error $errorCode"
                }
                log("Navigator error: $why")
            }
        })
    }

    fun start(simulate: Boolean, start: LatLng?, dest: LatLng, twoWheeler: Boolean) {
        val nav = navigator
        if (nav == null) {
            log("Init the Navigation SDK first")
            return
        }
        NavBridge.reset()

        val waypoint = Waypoint.builder().setLatLng(dest.latitude, dest.longitude).setTitle("Destination").build()
        val options = RoutingOptions().travelMode(
            if (twoWheeler) RoutingOptions.TravelMode.TWO_WHEELER else RoutingOptions.TravelMode.DRIVING,
        )

        if (simulate) {
            if (start == null) {
                log("Enter a valid start lat,lng for the simulation")
                return
            }
            nav.simulator.setUserLocation(start)
        } else {
            nav.simulator.unsetUserLocation()
        }

        log("Requesting route (${if (twoWheeler) "two-wheeler" else "driving"}, ${if (simulate) "simulated" else "real GPS"})...")
        nav.setDestination(waypoint, options).setOnResultListener { status ->
            if (status == Navigator.RouteStatus.OK) {
                nav.startGuidance()
                if (simulate) nav.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(3f))
                log("Guidance started")
            } else {
                log("Route request failed: $status")
            }
        }
    }

    fun stop() {
        navigator?.let {
            it.stopGuidance()
            it.clearDestinations()
            it.simulator.unsetUserLocation()
        }
        NavBridge.reset()
        NavBridge.sendMode(NavProtocol.Mode.IDLE)
        log("Navigation stopped")
    }
}
