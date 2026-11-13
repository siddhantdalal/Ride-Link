package com.siddhantdalal.bikelink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The Bluetooth Low Energy link to the iPhone, for its calls and music.
 *
 * The bike phone advertises itself as an accessory that wants Apple's notification service (ANCS). That's what makes
 * it appear in the iPhone's Settings → Bluetooth. Once the rider pairs it there, the iPhone connects to us, and we
 * connect back over the same link as a client of ANCS and AMS. Incoming calls then arrive with the caller's name,
 * Answer and Decline act on the iPhone, and its now-playing info and controls work from the bike.
 *
 * Bluetooth callbacks arrive on binder threads, so all the work happens on one background thread. Android's GATT
 * client also allows only one operation at a time, which a small queue enforces. Listeners hear about changes on the
 * main thread.
 */
// MissingPermission: every path that touches Bluetooth starts in startOnLink(), which checks first.
// StaticFieldLeak: the only Context kept is the application context, which lives as long as the app anyway.
@SuppressLint("MissingPermission", "StaticFieldLeak")
object IphoneLink {

    enum class Status {
        /** This phone can't advertise as an accessory: no Bluetooth LE advertising, or Android older than 12. */
        UNSUPPORTED,
        BLUETOOTH_OFF,
        PERMISSION_NEEDED,
        /** Advertising, waiting for the iPhone to pair or come back into range. */
        WAITING,
        /** Connected, pairing or setting up the subscriptions. */
        CONNECTING,
        CONNECTED,
    }

    data class IncomingCall(val uid: Int, val caller: String)

    data class NowPlaying(val title: String, val artist: String, val playing: Boolean)

    data class State(val status: Status, val call: IncomingCall? = null, val music: NowPlaying? = null)

    @Volatile
    var state = State(Status.WAITING)
        private set

    private const val TAG = "IphoneLink"
    private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val mainThread = Handler(Looper.getMainLooper())
    private val link = Handler(HandlerThread("iphone-link").apply { start() }.looper)

    // Everything below is only touched on the link thread.
    private var context: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var remoteCommand: BluetoothGattCharacteristic? = null
    private val operations = ArrayDeque<() -> Boolean>()
    private var operationInFlight = false
    private val attributeReader = Ancs.AttributeReader()
    private var ringingUid: Int? = null
    private var trackTitle = ""
    private var trackArtist = ""
    private var playing = false
    private var receiversRegistered = false

    /** Adds a listener, and calls it right away with the current state. Call from the main thread. */
    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    /** The runtime permissions the link needs. Android 12+ only, since older versions can't be supported anyway. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    fun hasPermissions(context: Context): Boolean =
        requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Starts advertising and waits for the iPhone. Safe to call repeatedly, for example after a permission is granted. */
    fun start(context: Context) {
        val app = context.applicationContext
        link.post { startOnLink(app) }
    }

    fun stop() {
        link.post { stopOnLink() }
    }

    fun media(command: Ams.Command) {
        link.post { remoteCommand?.let { write(it, byteArrayOf(command.id)) } }
    }

    /** Answers ([answer] = true) or declines the incoming call on the iPhone. */
    fun respondToCall(answer: Boolean) {
        link.post {
            val call = state.call ?: return@post
            controlPoint?.let { write(it, Ancs.callAction(call.uid, answer)) }
        }
    }

    private fun startOnLink(app: Context) {
        context = app
        registerReceivers(app)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return publish(State(Status.UNSUPPORTED))
        if (!hasPermissions(app)) return publish(State(Status.PERMISSION_NEEDED))
        val manager = app.getSystemService(BluetoothManager::class.java)
        val bluetooth = manager?.adapter ?: return publish(State(Status.UNSUPPORTED))
        adapter = bluetooth
        if (!bluetooth.isEnabled) return publish(State(Status.BLUETOOTH_OFF))
        if (bluetooth.bluetoothLeAdvertiser == null) return publish(State(Status.UNSUPPORTED))
        if (server == null) server = manager.openGattServer(app, serverCallback)
        if (gatt == null) startAdvertising(bluetooth)
    }

    private fun stopOnLink() {
        stopAdvertising()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        server?.close()
        server = null
        resetSession()
        context?.let(::unregisterReceivers)
    }

    // --- Advertising, so the iPhone can find and pair with us ---

    private fun startAdvertising(bluetooth: BluetoothAdapter) {
        val advertiser = bluetooth.bluetoothLeAdvertiser ?: return
        if (!advertising && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            // Soliciting ANCS is what makes iOS list the phone under Settings → Bluetooth. (This API is Android 12+.)
            val data = AdvertiseData.Builder().addServiceSolicitationUuid(ParcelUuid(Ancs.SERVICE)).build()
            // The name goes in the scan response, which has its own space: it's how the rider recognises the phone.
            val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            advertising = true
        }
        publish(State(Status.WAITING))
    }

    private fun stopAdvertising() {
        if (!advertising) return
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            link.post {
                advertising = false
                Log.w(TAG, "Advertising failed: $errorCode")
                if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) publish(State(Status.UNSUPPORTED))
            }
        }
    }

    // --- The iPhone connects to us, and we connect back to it as a client ---

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            link.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> connectBack(device)
                    BluetoothProfile.STATE_DISCONNECTED -> if (gatt?.device == device) onDisconnected()
                }
            }
        }
    }

    private fun connectBack(device: BluetoothDevice) {
        val app = context ?: return
        if (gatt != null) return
        stopAdvertising()
        publish(State(Status.CONNECTING))
        gatt = device.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            link.post {
                if (g != gatt) return@post
                when (newState) {
                    // The iPhone only shares calls and music with a paired accessory, so pair first if needed.
                    BluetoothProfile.STATE_CONNECTED ->
                        if (g.device.bondState == BluetoothDevice.BOND_BONDED) g.discoverServices() else g.device.createBond()
                    BluetoothProfile.STATE_DISCONNECTED -> onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            link.post { if (g == gatt) subscribe(g) }
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            // iOS can add ANCS after connecting, for example once the rider allows notifications.
            link.post { if (g == gatt) g.discoverServices() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            link.post { onOperationDone(g, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            link.post { onOperationDone(g, status) }
        }

        // Android 13+ passes the value directly.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            link.post { if (g == gatt) onNotified(characteristic.uuid, value) }
        }

        // Before Android 13 the value is read from the characteristic, and copied before another packet replaces it.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            link.post { if (g == gatt) onNotified(characteristic.uuid, value) }
        }
    }

    private fun subscribe(g: BluetoothGatt) {
        operations.clear()
        operationInFlight = false
        val ancs = g.getService(Ancs.SERVICE)
        val ams = g.getService(Ams.SERVICE)
        if (ancs == null && ams == null) {
            // Not shared yet: the iPhone needs to be paired, with notifications allowed.
            if (g.device.bondState != BluetoothDevice.BOND_BONDED) g.device.createBond()
            return publish(state.copy(status = Status.CONNECTING))
        }
        controlPoint = ancs?.getCharacteristic(Ancs.CONTROL_POINT)
        remoteCommand = ams?.getCharacteristic(Ams.REMOTE_COMMAND)
        // Apple's spec: subscribe to the Data Source before the Notification Source, so no reply can be missed.
        ancs?.getCharacteristic(Ancs.DATA_SOURCE)?.let(::enableNotifications)
        ancs?.getCharacteristic(Ancs.NOTIFICATION_SOURCE)?.let(::enableNotifications)
        ams?.getCharacteristic(Ams.ENTITY_UPDATE)?.let { updates ->
            enableNotifications(updates)
            write(updates, Ams.SUBSCRIBE_TRACK)
            write(updates, Ams.SUBSCRIBE_PLAYER)
        }
        // AMS expects this subscription before it accepts commands.
        remoteCommand?.let(::enableNotifications)
        publish(state.copy(status = Status.CONNECTED))
    }

    private fun onDisconnected() {
        gatt?.close()
        gatt = null
        resetSession()
        publish(State(Status.WAITING))
        adapter?.takeIf { it.isEnabled }?.let(::startAdvertising)
    }

    private fun resetSession() {
        controlPoint = null
        remoteCommand = null
        operations.clear()
        operationInFlight = false
        attributeReader.reset()
        ringingUid = null
        trackTitle = ""
        trackArtist = ""
        playing = false
    }

    // --- What the iPhone tells us ---

    private fun onNotified(uuid: UUID, value: ByteArray) {
        when (uuid) {
            Ancs.NOTIFICATION_SOURCE -> Ancs.parseEvent(value)?.let(::onNotificationEvent)
            Ancs.DATA_SOURCE -> attributeReader.add(value)?.let(::onAttributes)
            Ams.ENTITY_UPDATE -> Ams.parseUpdate(value)?.let(::onMediaUpdate)
        }
    }

    private fun onNotificationEvent(event: Ancs.Event) {
        if (event.categoryId != Ancs.CATEGORY_INCOMING_CALL) return
        when (event.eventId) {
            Ancs.EVENT_ADDED -> controlPoint?.let {
                // Ask who's calling. The answer arrives on the Data Source.
                ringingUid = event.uid
                attributeReader.reset()
                write(it, Ancs.getTitle(event.uid))
            }
            // Answered, declined or missed: the call notification goes away.
            Ancs.EVENT_REMOVED -> if (ringingUid == event.uid || state.call?.uid == event.uid) {
                ringingUid = null
                publish(state.copy(call = null))
            }
        }
    }

    private fun onAttributes(attributes: Ancs.Attributes) {
        if (attributes.uid != ringingUid) return
        publish(state.copy(call = IncomingCall(attributes.uid, attributes.values[Ancs.ATTRIBUTE_TITLE].orEmpty())))
    }

    private fun onMediaUpdate(update: Ams.Update) {
        when {
            update.entity == Ams.ENTITY_TRACK && update.attribute == Ams.TRACK_TITLE -> trackTitle = update.value
            update.entity == Ams.ENTITY_TRACK && update.attribute == Ams.TRACK_ARTIST -> trackArtist = update.value
            update.entity == Ams.ENTITY_PLAYER && update.attribute == Ams.PLAYER_PLAYBACK_INFO ->
                playing = Ams.isPlaying(update.value)
            else -> return
        }
        val music = if (trackTitle.isBlank()) null else NowPlaying(trackTitle, trackArtist, playing)
        publish(state.copy(music = music))
    }

    // --- One GATT operation at a time ---

    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) = enqueue {
        val g = gatt ?: return@enqueue false
        if (!g.setCharacteristicNotification(characteristic, true)) return@enqueue false
        val config = characteristic.getDescriptor(CLIENT_CONFIG) ?: return@enqueue false
        writeDescriptor(g, config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray) = enqueue {
        val g = gatt ?: return@enqueue false
        writeCharacteristic(g, characteristic, value)
    }

    private fun enqueue(operation: () -> Boolean) {
        operations.addLast(operation)
        if (!operationInFlight) runNext()
    }

    /** Starts the next queued operation. One that can't start is skipped, so a single failure can't stall the queue. */
    private fun runNext() {
        while (operations.isNotEmpty()) {
            if (operations.removeFirst().invoke()) {
                operationInFlight = true
                return
            }
        }
        operationInFlight = false
    }

    private fun onOperationDone(g: BluetoothGatt, status: Int) {
        if (g != gatt) return
        operationInFlight = false
        if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION) {
            // Not paired yet. Pair now. Once bonded, the bond receiver discovers the services and subscribes again.
            operations.clear()
            g.device.createBond()
            return
        }
        runNext()
    }

    private fun writeDescriptor(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }

    private fun writeCharacteristic(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }

    // --- Bluetooth turning on and off, and pairing finishing ---

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
                    link.post { if (on) context.let(::startOnLink) else bluetoothTurnedOff() }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bonded = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) ==
                        BluetoothDevice.BOND_BONDED
                    if (bonded) link.post { gatt?.discoverServices() }
                }
            }
        }
    }

    private fun bluetoothTurnedOff() {
        advertising = false
        gatt?.close()
        gatt = null
        server = null // closed by the system along with Bluetooth
        resetSession()
        publish(State(Status.BLUETOOTH_OFF))
    }

    private fun registerReceivers(app: Context) {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Both are system broadcasts, which reach a receiver that isn't exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(systemReceiver, filter)
        }
        receiversRegistered = true
    }

    private fun unregisterReceivers(app: Context) {
        if (!receiversRegistered) return
        app.unregisterReceiver(systemReceiver)
        receiversRegistered = false
    }

    private fun publish(newState: State) {
        state = newState
        mainThread.post { listeners.forEach { it(newState) } }
    }
}
