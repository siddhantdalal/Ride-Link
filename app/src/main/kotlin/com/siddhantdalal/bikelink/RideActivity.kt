package com.siddhantdalal.bikelink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.StylingOptions
import com.google.android.libraries.navigation.Waypoint
import java.lang.ref.WeakReference
import java.util.Date

/**
 * The ride screen: Google's turn-by-turn navigation in two-wheeler mode, with Bike Link's trip strip below the map.
 *
 * There's only ever one ride screen (singleTask), and commands arrive at it as intents: a new destination replaces
 * the current route, and Stop Ride ends it. Google's Navigator is shared across the app. This screen requests it
 * (Google asks the rider to accept its terms the first time), sets the route and starts guidance.
 */
class RideActivity : Activity() {

    private lateinit var navigationView: NavigationView
    private lateinit var banner: TextView
    private lateinit var tripTime: TextView
    private lateinit var tripMeta: TextView
    private lateinit var rideAgain: Button
    private lateinit var dock: IphoneDock

    private var navigator: Navigator? = null
    /** A command that arrived before Google's navigator was ready. */
    private var waiting: Intent? = null
    /** A route is being requested: stops a second tap from asking (and paying) twice. */
    private var routing = false

    private val tripListener = Navigator.RemainingTimeOrDistanceChangedListener { renderTrip() }
    private val arrivalListener = Navigator.ArrivalListener { onArrived() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_ride)
        navigationView = findViewById(R.id.navigation_view)
        banner = findViewById(R.id.ride_banner)
        tripTime = findViewById(R.id.trip_time)
        tripMeta = findViewById(R.id.trip_meta)
        rideAgain = findViewById(R.id.ride_again)
        dock = IphoneDock(this)
        findViewById<View>(R.id.end_ride).setOnClickListener { endRide() }

        navigationView.onCreate(savedInstanceState)
        styleNavigation()
        followRider()
        current = WeakReference(this)
        // After a rotation the route is already set. Handling the original intent again would request, and pay for,
        // the same route twice.
        if (savedInstanceState == null) waiting = intent
        renderTrip()
        requestNavigator()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (navigator == null) waiting = intent else handle(intent)
    }

    // NavigationView needs every lifecycle call forwarded.
    override fun onStart() {
        super.onStart()
        navigationView.onStart()
    }

    override fun onResume() {
        super.onResume()
        navigationView.onResume()
        dock.attach()
    }

    override fun onPause() {
        dock.detach()
        navigationView.onPause()
        super.onPause()
    }

    override fun onStop() {
        navigationView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navigationView.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        navigationView.onConfigurationChanged(newConfig)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        navigationView.onTrimMemory(level)
    }

    override fun onDestroy() {
        navigator?.let {
            it.removeRemainingTimeOrDistanceChangedListener(tripListener)
            it.removeArrivalListener(arrivalListener)
            // Leaving the ride screen (Back, or End ride) ends the ride. A rotation only recreates the screen.
            if (isFinishing) stopNavigation(it)
        }
        navigationView.onDestroy()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun requestNavigator() {
        showBanner(getString(R.string.ride_starting))
        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(ready: Navigator) {
                navigator = ready
                // Voice guidance through the helmet intercom when it's connected, otherwise the phone's speaker.
                ready.setAudioGuidance(
                    Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE or Navigator.AudioGuidance.BLUETOOTH_AUDIO,
                )
                ready.addRemainingTimeOrDistanceChangedListener(TRIP_UPDATE_SECONDS, TRIP_UPDATE_METERS, tripListener)
                ready.addArrivalListener(arrivalListener)
                hideBanner()
                renderTrip()
                waiting?.let(::handle)
                waiting = null
            }

            override fun onError(errorCode: Int) {
                showBanner(getString(navigatorError(errorCode)))
            }
        })
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_NAVIGATE -> navigate(
                latitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN),
                longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN),
                label = intent.getStringExtra(EXTRA_LABEL).orEmpty(),
                simulate = intent.getBooleanExtra(EXTRA_SIMULATE, false),
            )
            ACTION_STOP -> endRide()
        }
    }

    private fun navigate(latitude: Double, longitude: Double, label: String, simulate: Boolean) {
        val nav = navigator ?: return
        if (latitude.isNaN() || longitude.isNaN()) return
        if (!RouteBudget.tryUse(this)) {
            showBanner(getString(R.string.ride_budget_used, RouteBudget.DAILY_LIMIT, RouteBudget.MONTHLY_LIMIT))
            return
        }
        showBanner(getString(R.string.ride_finding_route, label))
        routing = true
        renderTrip()
        val destination = Waypoint.builder().setLatLng(latitude, longitude).setTitle(label).build()
        val twoWheeler = RoutingOptions().travelMode(RoutingOptions.TravelMode.TWO_WHEELER)
        nav.setDestination(destination, twoWheeler).setOnResultListener { status ->
            runOnUiThread { onRouteResult(nav, status, label, latitude, longitude, simulate) }
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
            showBanner(getString(routeError(status), label))
            renderTrip()
            return
        }
        hideBanner()
        nav.startGuidance()
        // For testing at a desk: Google's simulator rides the route, so the whole screen can be seen working.
        if (simulate) nav.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(SIMULATION_SPEED))
        RideLog.started(this, label, latitude, longitude)
        renderTrip()
    }

    private fun onArrived() {
        navigator?.let(::stopNavigation)
        RideLog.ended(this)
        showBanner(getString(R.string.ride_arrived))
        renderTrip()
    }

    private fun endRide() {
        navigator?.let(::stopNavigation)
        RideLog.ended(this)
        finish()
    }

    private fun stopNavigation(nav: Navigator) {
        nav.simulator.unsetUserLocation()
        nav.stopGuidance()
        nav.clearDestinations()
    }

    /**
     * The trip strip is always shown, because it holds End ride. Without a route it's a free ride: the map follows the
     * rider at no cost, and a big button offers the last destination again. Only that tap requests (and counts) a route.
     */
    private fun renderTrip() {
        val trip = navigator?.takeIf { it.isGuidanceRunning }?.currentTimeAndDistance
        val last = RideLog.last(this)
        val offerRideAgain = trip == null && !routing && navigator != null && last != null
        rideAgain.visibility = if (offerRideAgain) View.VISIBLE else View.GONE
        if (offerRideAgain && last != null) {
            rideAgain.text = getString(R.string.ride_again_button, last.destination)
            rideAgain.setOnClickListener { navigate(last.latitude, last.longitude, last.destination, simulate = false) }
        }
        if (trip == null) {
            tripTime.text = getString(R.string.free_ride)
            tripMeta.text = getString(R.string.free_ride_hint)
            return
        }
        val arrival = Date(System.currentTimeMillis() + trip.seconds * 1_000L)
        tripTime.text = RideFormat.duration(trip.seconds)
        tripMeta.text = getString(
            R.string.trip_meta,
            RideFormat.distance(trip.meters),
            DateFormat.getTimeFormat(this).format(arrival),
        )
    }

    private fun showBanner(message: String) {
        banner.text = message
        banner.visibility = View.VISIBLE
    }

    private fun hideBanner() {
        banner.visibility = View.GONE
    }

    /** Shows the rider on the map and follows them, tilted like a navigation view, with or without a route. */
    @SuppressLint("MissingPermission") // checked first: location is a required setup step
    private fun followRider() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        navigationView.getMapAsync { map ->
            map.isMyLocationEnabled = true
            map.followMyLocation(GoogleMap.CameraPerspective.TILTED)
        }
    }

    /** Google's next-turn header in Bike Link's colours: the accent green with dark text, day and night. */
    private fun styleNavigation() {
        val accent = getColor(R.color.accent)
        val onAccent = getColor(R.color.on_accent)
        navigationView.setStylingOptions(
            StylingOptions()
                .primaryDayModeThemeColor(accent)
                .secondaryDayModeThemeColor(getColor(R.color.accent_dark))
                .primaryNightModeThemeColor(accent)
                .secondaryNightModeThemeColor(getColor(R.color.accent_dark))
                .headerLargeManeuverIconColor(onAccent)
                .headerSmallManeuverIconColor(onAccent)
                .headerInstructionsTextColor(onAccent)
                .headerNextStepTextColor(onAccent)
                .headerDistanceValueTextColor(onAccent)
                .headerDistanceUnitsTextColor(onAccent),
        )
        // Bike Link's own trip strip replaces Google's ETA card. The speedometer is useful on a bike, so it stays.
        navigationView.setEtaCardEnabled(false)
        navigationView.setTripProgressBarEnabled(false)
        navigationView.setReportIncidentButtonEnabled(false)
        navigationView.setSpeedometerEnabled(true)
        navigationView.setSpeedLimitIconEnabled(true)
    }

    /** A ride command can arrive while the phone sleeps on the mount: wake it, and get past a swipe-only lock screen. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
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
        private const val ACTION_NAVIGATE = "com.siddhantdalal.bikelink.action.NAVIGATE"
        private const val ACTION_STOP = "com.siddhantdalal.bikelink.action.STOP"
        private const val EXTRA_LATITUDE = "latitude"
        private const val EXTRA_LONGITUDE = "longitude"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_SIMULATE = "simulate"
        private const val TRIP_UPDATE_SECONDS = 30
        private const val TRIP_UPDATE_METERS = 100
        private const val SIMULATION_SPEED = 3f

        // Weak, so a finished screen can always be freed: the Bluetooth and command threads only need to reach the
        // screen while it's open.
        @Volatile
        private var current: WeakReference<RideActivity>? = null
        private val mainThread = Handler(Looper.getMainLooper())

        /** The open ride screen, ignoring one that's already closing (between finish() and onDestroy()). */
        private val openScreen: RideActivity?
            get() = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

        val isOpen: Boolean get() = openScreen != null

        /** Starts two-wheeler navigation, or switches the current ride to a new destination. */
        fun navigate(context: Context, latitude: Double, longitude: Double, label: String, simulate: Boolean = false) {
            context.startActivity(
                Intent(context, RideActivity::class.java)
                    .setAction(ACTION_NAVIGATE)
                    .putExtra(EXTRA_LATITUDE, latitude)
                    .putExtra(EXTRA_LONGITUDE, longitude)
                    .putExtra(EXTRA_LABEL, label)
                    .putExtra(EXTRA_SIMULATE, simulate)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** Brings the current ride back to the front, or opens the map for a free ride. */
        fun openMap(context: Context) {
            context.startActivity(Intent(context, RideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Ends the current ride. Returns false if there wasn't one. Safe to call from any thread. */
        fun stop(): Boolean {
            val screen = openScreen ?: return false
            mainThread.post { screen.endRide() }
            return true
        }
    }
}
