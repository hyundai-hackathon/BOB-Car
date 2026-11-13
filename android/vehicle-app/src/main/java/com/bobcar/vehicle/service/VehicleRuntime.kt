package com.bobcar.vehicle.service

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.bobcar.vehicle.checkin.DriverCheckin
import com.bobcar.vehicle.checkin.DriverDefaults
import com.bobcar.vehicle.data.BobCarDatabase
import com.bobcar.vehicle.data.DrivingRecorder
import com.bobcar.vehicle.data.TripRecorder
import com.bobcar.vehicle.debug.FakeHooks
import com.bobcar.vehicle.debug.FakeSignalInjector
import com.bobcar.vehicle.driving.DrivingEvent
import com.bobcar.vehicle.driving.DrivingMonitor
import com.bobcar.vehicle.gateway.CarPropertyGateway
import com.bobcar.vehicle.gateway.NaviGateway
import com.bobcar.vehicle.gateway.VehicleGateway
import com.bobcar.vehicle.signal.VehicleSignals
import com.bobcar.vehicle.trip.Purpose
import com.bobcar.vehicle.trip.TripEndChoices
import com.bobcar.vehicle.upload.UploadScheduler
import com.bobcar.vehicle.voice.VoiceGateway
import com.bobcar.vehicle.voice.VoiceGuide

/**
 * Everything that collects and records vehicle data, owned by [CollectionService] (one per process).
 * Screens only read [signals] and [monitor]; they never touch the gateways.
 *
 * The signal hub and monitor live for the whole process so screens can observe them before the
 * service has finished starting. Gateways are created on [start] and released on [stop].
 */
object VehicleRuntime {

    private const val TAG = "BOBCar.Runtime"

    val signals = VehicleSignals()
    val monitor = DrivingMonitor(signals)

    /** The driver now checked in (for the driver card). */
    val checkin = DriverCheckin()

    /** Driver and purpose on the trip-end card. */
    val tripEnd = TripEndChoices()

    /** SDK registration blocks for ~1 s while the services bind, so it runs off the main thread. */
    private val initThread = HandlerThread("bobcar-init").apply { start() }
    private val initHandler = Handler(initThread.looper)

    private var started = false
    private var vehicle: VehicleGateway? = null
    private var carProperty: CarPropertyGateway? = null
    private var navi: NaviGateway? = null
    private var recorder: DrivingRecorder? = null
    private var recordListener: ((DrivingEvent) -> Unit)? = null
    private var naviRefreshListener: ((DrivingEvent) -> Unit)? = null
    private var tripRecorder: TripRecorder? = null
    private var unregisterFakeSignals: (() -> Unit)? = null
    private var voice: VoiceGateway? = null
    private var voiceListener: ((DrivingEvent) -> Unit)? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        // Voice guidance (on-device TTS). Starts first: initialization takes a while and must not hold up recording
        val speaker = VoiceGateway(app).also { voice = it }
        speaker.initialize()
        val guide = VoiceGuide(speaker)

        val db = BobCarDatabase.get(app)
        // Queue entries carry the checked-in driver, so the upload can hold back drivers who did not agree
        val rec = DrivingRecorder(db, currentDriver = { checkin.current?.driverId }) { UploadScheduler.request(app) }
        val listener: (DrivingEvent) -> Unit = rec::record
        recorder = rec
        recordListener = listener
        monitor.addEventListener(listener)
        // Trip rows (logbook): registered before monitor.start so the first AppResumed is seen
        val trips = TripRecorder(
            db, signals, checkin, DriverDefaults(db), tripEnd,
            recordEvent = rec::record,
            recordConsent = rec::recordConsent,
            assignDriver = rec::assignDriver,
            claimFindings = rec::claimFindings,
            announce = guide::announce,
        ).also { tripRecorder = it }
        monitor.addEventListener(trips.onDrivingEvent)
        // Voice ③: the end countdown (and silence when it is cancelled)
        monitor.addEventListener(guide.onDrivingEvent)
        voiceListener = guide.onDrivingEvent
        trips.start()
        monitor.start()
        // Send what piled up while the app or the server was off
        UploadScheduler.request(app)

        val v = VehicleGateway(app).also { vehicle = it }
        val c = CarPropertyGateway(app).also { carProperty = it }
        val n = NaviGateway(app).also { navi = it }
        // Bookmarks have no change event: read them again on every trip start (purpose suggestion)
        val bookmarkRefresh: (DrivingEvent) -> Unit = { if (it is DrivingEvent.TripStarted) n.refreshBookmarks() }
        naviRefreshListener = bookmarkRefresh
        monitor.addEventListener(bookmarkRefresh)
        val hooks = FakeHooks(
            continueTapped = monitor::onContinueTapped,
            confirmDriver = ::confirmDriver,
            setReservation = trips::setReservationNow,
            resetConsent = trips::resetConsent,
            choosePurpose = ::choosePurpose,
            failVoice = speaker::simulateFailure,
        )
        unregisterFakeSignals = FakeSignalInjector.register(app, signals, v, c, hooks)

        initHandler.post {
            if (v.initialize()) v.startSignals(signals)
            if (c.connect()) c.startSignals(signals)
            n.startSignals(signals)
            n.initialize()
            Log.i(TAG, "collection started")
        }
    }

    /** Driver card answered: recorded on the check-in (and running trip), and used for trip merging. */
    fun confirmDriver(driverId: String) {
        monitor.onDriverConfirmed(driverId)
        tripRecorder?.confirmDriver(driverId)
    }

    /** A purpose tapped on the trip-end card. */
    fun choosePurpose(purpose: Purpose) {
        tripRecorder?.choosePurpose(purpose)
    }

    /** [계속 운행] on the trip-end card. */
    fun continueTrip() {
        monitor.onContinueTapped()
    }

    /** [변경] on the trip-end card: the driver list opens and the countdown waits. */
    fun openEndDriverList() {
        monitor.pauseEndCountdown()
    }

    /** [뒤로] on that driver list: back to the card, the countdown starts again. */
    fun closeEndDriverList() {
        monitor.resumeEndCountdown()
    }

    /** A driver picked on that list: confirmed for the trip, back to the card. */
    fun pickEndDriver(driverId: String) {
        confirmDriver(driverId)
        monitor.resumeEndCountdown()
    }

    /** Consent card answered by [driverId]: [동의] (agreed) or [나중에]. */
    fun answerConsent(driverId: String, agreed: Boolean) {
        tripRecorder?.answerConsent(driverId, agreed)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        unregisterFakeSignals?.invoke()
        monitor.stop()
        recordListener?.let { monitor.removeEventListener(it) }
        naviRefreshListener?.let { monitor.removeEventListener(it) }
        tripRecorder?.let { monitor.removeEventListener(it.onDrivingEvent) }
        val v = vehicle
        val c = carProperty
        val n = navi
        val rec = recorder
        val trips = tripRecorder
        voiceListener?.let { monitor.removeEventListener(it) }
        voiceListener = null
        voice?.release()
        voice = null
        initHandler.post {
            n?.release()
            c?.release()
            v?.release()
            rec?.close()
            trips?.close()
            Log.i(TAG, "collection stopped")
        }
        vehicle = null
        carProperty = null
        navi = null
        recorder = null
        recordListener = null
        naviRefreshListener = null
        tripRecorder = null
        unregisterFakeSignals = null
    }
}
