package com.siddhantdalal.bikelink

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.android.libraries.navigation.NavigationView
import java.lang.ref.WeakReference

/**
 * The car screen: Bike Link's take on CarPlay, and the app's only main screen.
 *
 * One live map (Google's navigation can only show its map in one place) with screens switched around it from the
 * sidebar (the bottom bar in portrait): Dashboard (the map with next-turn, now-playing and trip cards), Map, Music,
 * Calls and Home. Music, Calls and Home cover the map, which keeps running underneath, so guidance never stops. An
 * incoming call shows as a compact bar over whatever is open.
 *
 * Commands from the iPhone arrive as intents (singleTask), and [RideController] does the navigating.
 */
class MainActivity : Activity() {

    private lateinit var navigationView: NavigationView
    private lateinit var ride: RideController
    private lateinit var navBar: NavBar
    private lateinit var home: HomePane
    private lateinit var turnCard: TurnCard
    private lateinit var dashTrip: TripCard
    private lateinit var mapTrip: TripCard
    private lateinit var dashMusic: MusicCard
    private lateinit var paneMusic: MusicCard
    private lateinit var callBar: CallBar
    private lateinit var callsPane: CallsPane
    private lateinit var banner: TextView
    private lateinit var dashboardColumn: View
    private lateinit var dashTurn: View
    private lateinit var musicScreen: View
    private lateinit var callsScreen: View
    private lateinit var homeScreen: View

    private var screen = CarScreen.DASHBOARD
    private var rideState = RideController.Ride.FREE
    /** Whether this screen was woken over the lock screen for a ride, so that can be undone when the ride ends. */
    private var wokenForRide = false
    /** Whether that ride has actually started. Until it has, the ride hasn't ended either. */
    private var rideStartedSinceWake = false
    private val recheckStatus = Runnable { renderStatus() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderBattery(intent)
    }

    private val rideLogListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderRide(rideState) }

    private val iphoneListener: (IphoneLink.State) -> Unit = { renderIphone(it) }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = renderStatusSoon()
        override fun onLost(network: Network) = renderStatusSoon()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = renderStatusSoon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        navigationView = findViewById(R.id.navigation_view)
        banner = findViewById(R.id.ride_banner)
        dashboardColumn = findViewById(R.id.dashboard_column)
        dashTurn = findViewById(R.id.dash_turn)
        musicScreen = findViewById(R.id.pane_music)
        callsScreen = findViewById(R.id.pane_calls)
        homeScreen = findViewById(R.id.pane_home)

        navigationView.onCreate(savedInstanceState)
        ride = RideController(this, navigationView, ::renderRide)
        navBar = NavBar(this, ::show)
        home = HomePane(this, ::show)
        turnCard = TurnCard(dashTurn, onRideAgain = { ride.rideAgain() }, onOpenMap = { show(CarScreen.MAP) })
        dashTrip = TripCard(findViewById(R.id.dash_trip)) { ride.endRide() }
        mapTrip = TripCard(findViewById(R.id.map_trip)) { ride.endRide() }
        dashMusic = MusicCard(findViewById(R.id.dash_music)) { show(CarScreen.MUSIC) }
        paneMusic = MusicCard(musicScreen)
        callBar = CallBar(findViewById(R.id.call_bar))
        callsPane = CallsPane(callsScreen) { startActivity(Intent(this, SetupActivity::class.java)) }

        screen = savedInstanceState?.getString(KEY_SCREEN)
            ?.let { saved -> CarScreen.entries.firstOrNull { it.name == saved } }
            ?: CarScreen.DASHBOARD
        current = WeakReference(this)
        // After a rotation the ride is already running. Handling the original intent again would request, and pay
        // for, the same route twice.
        val command = intent.takeIf { savedInstanceState == null }
        if (command?.action == RideController.ACTION_NAVIGATE) wakeForRide()
        ride.start(command)
        show(screen)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == RideController.ACTION_NAVIGATE) {
            wakeForRide()
            show(CarScreen.DASHBOARD)
        }
        ride.handle(intent)
    }

    // NavigationView needs every lifecycle call forwarded.
    override fun onStart() {
        super.onStart()
        navigationView.onStart()
    }

    override fun onResume() {
        super.onResume()
        navigationView.onResume()
        BikeLinkService.start(this)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::renderBattery)
        RideLog.prefs(this).registerOnSharedPreferenceChangeListener(rideLogListener)
        IphoneLink.addListener(iphoneListener)
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(wifi, wifiCallback)
        renderStatus()
        // The background service starts asynchronously, so check again once it has had a moment.
        banner.postDelayed(recheckStatus, STATUS_RECHECK_MS)
    }

    override fun onPause() {
        banner.removeCallbacks(recheckStatus)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback)
        IphoneLink.removeListener(iphoneListener)
        RideLog.prefs(this).unregisterOnSharedPreferenceChangeListener(rideLogListener)
        unregisterReceiver(batteryReceiver)
        navigationView.onPause()
        super.onPause()
    }

    override fun onStop() {
        navigationView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
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
        // Leaving the app (Back) ends the ride. A rotation only recreates the screen.
        ride.release(finishing = isFinishing)
        navigationView.onDestroy()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    /** Switches screens. The map stays live underneath all of them. */
    private fun show(target: CarScreen) {
        screen = target
        dashboardColumn.visibility = visibleOn(CarScreen.DASHBOARD)
        dashTurn.visibility = visibleOn(CarScreen.DASHBOARD) // in portrait it floats over the map, outside the column
        musicScreen.visibility = visibleOn(CarScreen.MUSIC)
        callsScreen.visibility = visibleOn(CarScreen.CALLS)
        homeScreen.visibility = visibleOn(CarScreen.HOME)
        // The full Map screen uses Google's own turn banner. The Dashboard has its own next-turn card instead.
        navigationView.setHeaderEnabled(target == CarScreen.MAP)
        navBar.select(target)
        renderRide(rideState)
        if (target == CarScreen.HOME) renderStatus()
    }

    private fun visibleOn(target: CarScreen) = if (screen == target) View.VISIBLE else View.GONE

    private fun renderRide(state: RideController.Ride) {
        rideState = state
        banner.text = state.message
        banner.visibility = if (state.message == null) View.GONE else View.VISIBLE
        turnCard.render(state, RideLog.last(this))
        dashTrip.render(state, visibleHere = screen == CarScreen.DASHBOARD)
        mapTrip.render(state, visibleHere = screen == CarScreen.MAP)
        home.renderRide(state)
        if (wokenForRide) {
            // Keep showing over the lock screen until the ride that woke the phone has started and then ended.
            if (state.guiding || state.routing) {
                rideStartedSinceWake = true
            } else if (rideStartedSinceWake) {
                releaseLockScreen()
            }
        }
    }

    private fun renderIphone(state: IphoneLink.State) {
        dashMusic.render(state)
        paneMusic.render(state)
        callBar.render(state.call)
        callsPane.render(state)
        home.renderIphone(state)
        navBar.renderLinks(onIphoneHotspot(), state.status)
    }

    private fun renderStatus() {
        val missing = SetupChecklist(this).steps().count { it.required && !it.done }
        home.renderStatus(BikeLinkService.isRunning, missing, HotspotLink.localAddress())
        navBar.renderLinks(onIphoneHotspot(), IphoneLink.state.status)
    }

    /** Network callbacks arrive on a background thread. */
    private fun renderStatusSoon() {
        banner.post(recheckStatus)
    }

    private fun onIphoneHotspot(): Boolean = HotspotLink.localAddress()?.let(HotspotLink::isIphoneHotspot) == true

    /** The battery indicator, and keeping the screen on while the bike is charging the phone. */
    private fun renderBattery(status: Intent) {
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level >= 0 && scale > 0) navBar.renderBattery(level * 100 / scale, charging)
        if (charging) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** A ride can arrive while the phone sleeps on the mount: wake it, and get past a swipe-only lock screen. */
    private fun wakeForRide() {
        wokenForRide = true
        rideStartedSinceWake = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
    }

    /** After the ride, the screen goes back to respecting the lock screen. */
    private fun releaseLockScreen() {
        wokenForRide = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    companion object {
        private const val KEY_SCREEN = "screen"
        private const val STATUS_RECHECK_MS = 1_000L

        // Weak, so a finished screen can always be freed. The command thread only needs it while it's open.
        @Volatile
        private var current: WeakReference<MainActivity>? = null
        private val mainThread = Handler(Looper.getMainLooper())

        /** Starts two-wheeler navigation, or switches the current ride to a new destination. */
        fun navigate(context: Context, latitude: Double, longitude: Double, label: String, simulate: Boolean = false) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setAction(RideController.ACTION_NAVIGATE)
                    .putExtra(RideController.EXTRA_LATITUDE, latitude)
                    .putExtra(RideController.EXTRA_LONGITUDE, longitude)
                    .putExtra(RideController.EXTRA_LABEL, label)
                    .putExtra(RideController.EXTRA_SIMULATE, simulate)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** Ends the current ride. Returns false if there wasn't one. Safe to call from any thread. */
        fun stopRide(): Boolean {
            if (!RideController.guidingNow) return false
            val screen = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
            mainThread.post { screen.ride.endRide() }
            return true
        }
    }
}
