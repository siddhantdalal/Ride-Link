package com.siddhantdalal.bikelink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.StylingOptions
import com.google.android.libraries.navigation.TimeAndDistance
import com.google.android.libraries.navigation.Waypoint

/**
 * Turn-by-turn navigation for the car screen. It looks after Google's shared Navigator, two-wheeler routing, the trip
 * and next-turn feeds, free ride (the map following the rider without a route), and the route budget.
 *
 * The screen supplies its NavigationView, and gets one [Ride] snapshot to show whenever anything changes.
 * Everything runs on the main thread.
 */
class RideController(
    private val activity: Activity,
    private val navigationView: NavigationView,
    private val onChange: (Ride) -> Unit,
) {

    /** What the screen shows about the ride. With no route (guiding = false), it's a free ride. */
    data class Ride(
        val guiding: Boolean,
        /** A route is being requested. */
        val routing: Boolean,
        val trip: TimeAndDistance?,
        val turn: NavInfo?,
        /** Where the current route goes. */
        val destination: String?,
        /** A status or problem to show over the map, such as "Finding a route…" or "No internet". */
        val message: String?,
    ) {
        companion object {
            val FREE = Ride(guiding = false, routing = false, trip = null, turn = null, destination = null, message = null)
        }
    }

    private var navigator: Navigator? = null
    /** A command that arrived before Google's navigator was ready. */
    private var waiting: Intent? = null
    private var routing = false
    private var message: String? = null
    private var turn: NavInfo? = null

    private val tripListener = Navigator.RemainingTimeOrDistanceChangedListener { publish() }
    private val arrivalListener = Navigator.ArrivalListener { onArrived() }
    private val turnListener: (NavInfo?) -> Unit = {
        turn = it
        publish()
    }

    /** Sets up the map, then asks Google for the navigator. Any ride in [command] starts once it's ready. */
    fun start(command: Intent?) {
        waiting = command
        styleMap()
        NavInfoFeed.addListener(turnListener)
        message = activity.getString(R.string.ride_starting)
        publish()
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(ready: Navigator) {
                navigator = ready
                // Voice guidance goes through the helmet intercom when it's connected, otherwise the phone's speaker.
                ready.setAudioGuidance(
                    Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE or Navigator.AudioGuidance.BLUETOOTH_AUDIO,
                )
                ready.addRemainingTimeOrDistanceChangedListener(TRIP_UPDATE_SECONDS, TRIP_UPDATE_METERS, tripListener)
                ready.addArrivalListener(arrivalListener)
                // Following the rider needs a ready navigator: asking earlier crashes inside the SDK (seen on device).
                followRider()
                message = null
                publish()
                waiting?.let(::handle)
                waiting = null
            }

            override fun onError(errorCode: Int) {
                message = activity.getString(navigatorError(errorCode))
                publish()
            }
        })
    }

    /** A command from the iPhone (or the dashboard): navigate somewhere, or end the ride. */
    fun handle(command: Intent) {
        if (navigator == null) {
            waiting = command
            return
        }
        when (command.action) {
            ACTION_NAVIGATE -> navigate(
                latitude = command.getDoubleExtra(EXTRA_LATITUDE, Double.NaN),
                longitude = command.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN),
                label = command.getStringExtra(EXTRA_LABEL).orEmpty(),
                simulate = command.getBooleanExtra(EXTRA_SIMULATE, false),
            )
        }
    }

    /** From a free ride: route to the last destination again. This tap is the only way a free ride requests a route. */
    fun rideAgain() {
        val last = RideLog.last(activity) ?: return
        navigate(last.latitude, last.longitude, last.destination, simulate = false)
    }

    fun endRide() {
        navigator?.let(::stopNavigation)
        RideLog.ended(activity)
        message = null
        publish()
    }

    /** Call from the screen's onDestroy. A finishing screen ends the ride; a rotation only recreates the screen. */
    fun release(finishing: Boolean) {
        NavInfoFeed.removeListener(turnListener)
        navigator?.let {
            it.removeRemainingTimeOrDistanceChangedListener(tripListener)
            it.removeArrivalListener(arrivalListener)
            if (finishing) stopNavigation(it)
        }
    }

    private fun navigate(latitude: Double, longitude: Double, label: String, simulate: Boolean) {
        val nav = navigator ?: return
        if (latitude.isNaN() || longitude.isNaN() || routing) return
        if (!RouteBudget.tryUse(activity)) {
            message = activity.getString(R.string.ride_budget_used, RouteBudget.DAILY_LIMIT, RouteBudget.MONTHLY_LIMIT)
            return publish()
        }
        routing = true
        message = activity.getString(R.string.ride_finding_route, label)
        publish()
        val destination = Waypoint.builder().setLatLng(latitude, longitude).setTitle(label).build()
        val twoWheeler = RoutingOptions().travelMode(RoutingOptions.TravelMode.TWO_WHEELER)
        nav.setDestination(destination, twoWheeler).setOnResultListener { status ->
            activity.runOnUiThread { onRouteResult(nav, status, label, latitude, longitude, simulate) }
        }
    }

    private fun onRouteResult(
        nav: Navigator,
        status: Navigator.RouteStatus,
        label: String,
        latitude: Double,
        longitude: Double,
        simulate: Boolean,
    ) {
        routing = false
        if (status != Navigator.RouteStatus.OK) {
            message = activity.getString(routeError(status), label)
            return publish()
        }
        message = null
        nav.startGuidance()
        // The next-turn card is fed by Google's turn-by-turn feed, with Google's own arrow images.
        nav.registerServiceForNavUpdates(
            activity.packageName,
            NavInfoService::class.java.name,
            NavigationUpdatesOptions.builder()
                .setNumNextStepsToPreview(1)
                .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.BITMAP)
                .setDisplayMetrics(activity.resources.displayMetrics)
                .build(),
        )
        // For testing at a desk: Google's simulator rides the route, so the whole screen can be seen working.
        if (simulate) nav.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(SIMULATION_SPEED))
        RideLog.started(activity, label, latitude, longitude)
        publish()
    }

    private fun onArrived() {
        navigator?.let(::stopNavigation)
        RideLog.ended(activity)
        message = activity.getString(R.string.ride_arrived)
        publish()
    }

    private fun stopNavigation(nav: Navigator) {
        nav.simulator.unsetUserLocation()
        nav.stopGuidance()
        nav.clearDestinations()
        nav.unregisterServiceForNavUpdates()
        NavInfoFeed.publish(null)
    }

    private fun publish() {
        val nav = navigator
        val guiding = nav?.isGuidanceRunning == true
        guidingNow = guiding
        onChange(
            Ride(
                guiding = guiding,
                routing = routing,
                trip = if (guiding) nav?.currentTimeAndDistance else null,
                turn = if (guiding) turn else null,
                destination = if (guiding) RideLog.last(activity)?.destination else null,
                message = message,
            ),
        )
    }

    /** The map follows the rider, tilted like a navigation view, with or without a route. */
    @SuppressLint("MissingPermission") // checked first: location is a required setup step
    private fun followRider() {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        navigationView.getMapAsync { map ->
            map.isMyLocationEnabled = true
            map.followMyLocation(GoogleMap.CameraPerspective.TILTED)
        }
    }

    /** Google's next-turn banner (shown on the full Map screen) in Bike Link's colours, day and night. */
    private fun styleMap() {
        val accent = activity.getColor(R.color.accent)
        val dark = activity.getColor(R.color.accent_dark)
        val onAccent = activity.getColor(R.color.on_accent)
        navigationView.setStylingOptions(
            StylingOptions()
                .primaryDayModeThemeColor(accent)
                .secondaryDayModeThemeColor(dark)
                .primaryNightModeThemeColor(accent)
                .secondaryNightModeThemeColor(dark)
                .headerLargeManeuverIconColor(onAccent)
                .headerSmallManeuverIconColor(onAccent)
                .headerInstructionsTextColor(onAccent)
                .headerNextStepTextColor(onAccent)
                .headerDistanceValueTextColor(onAccent)
                .headerDistanceUnitsTextColor(onAccent),
        )
        // Bike Link's own trip card replaces Google's ETA card. The speedometer is useful on a bike, so it stays.
        navigationView.setEtaCardEnabled(false)
        navigationView.setTripProgressBarEnabled(false)
        navigationView.setReportIncidentButtonEnabled(false)
        navigationView.setSpeedometerEnabled(true)
        navigationView.setSpeedLimitIconEnabled(true)
    }

    private fun navigatorError(code: Int): Int = when (code) {
        NavigationApi.ErrorCode.NOT_AUTHORIZED -> R.string.ride_error_key
        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> R.string.ride_error_terms
        NavigationApi.ErrorCode.NETWORK_ERROR -> R.string.ride_error_network
        NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> R.string.ride_error_location_permission
        else -> R.string.ride_error_generic
    }

    private fun routeError(status: Navigator.RouteStatus): Int = when (status) {
        Navigator.RouteStatus.NO_ROUTE_FOUND -> R.string.route_error_none
        Navigator.RouteStatus.NETWORK_ERROR -> R.string.ride_error_network
        Navigator.RouteStatus.LOCATION_DISABLED -> R.string.route_error_location_off
        Navigator.RouteStatus.LOCATION_UNKNOWN -> R.string.route_error_no_gps
        Navigator.RouteStatus.QUOTA_CHECK_FAILED -> R.string.route_error_quota
        else -> R.string.route_error_generic
    }

    companion object {
        const val ACTION_NAVIGATE = "com.siddhantdalal.bikelink.action.NAVIGATE"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SIMULATE = "simulate"
        private const val TRIP_UPDATE_SECONDS = 30
        private const val TRIP_UPDATE_METERS = 100
        private const val SIMULATION_SPEED = 3f

        /** Whether a route is being followed right now. Read by the iPhone command thread. */
        @Volatile
        var guidingNow = false
            private set
    }
}
