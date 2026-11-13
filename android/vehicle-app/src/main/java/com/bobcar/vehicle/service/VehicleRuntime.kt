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
import com.bobcar.vehicle.upload.UploadScheduler

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

    /** SDK registration blocks for ~1 s while the services bind, so it runs off the main thread. */
    private val initThread = HandlerThread("bobcar-init").apply { start() }
    private val initHandler = Handler(initThread.looper)

    private var started = false
    private var vehicle: VehicleGateway? = null
    private var carProperty: CarPropertyGateway? = null
    private var navi: NaviGateway? = null
    private var recorder: DrivingRecorder? = null
    private var recordListener: ((DrivingEvent) -> Unit)? = null
    private var tripRecorder: TripRecorder? = null
    private var unregisterFakeSignals: (() -> Unit)? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        val db = BobCarDatabase.get(app)
        val rec = DrivingRecorder(db) { UploadScheduler.request(app) }
        val listener: (DrivingEvent) -> Unit = rec::record
        recorder = rec
        recordListener = listener
        monitor.addEventListener(listener)
        // Trip rows (logbook): registered before monitor.start so the first AppResumed is seen
        val trips = TripRecorder(db, signals, checkin, DriverDefaults(db), rec::record).also { tripRecorder = it }
        monitor.addEventListener(trips.onDrivingEvent)
        trips.start()
        monitor.start()
        // Send what piled up while the app or the server was off
        UploadScheduler.request(app)

        val v = VehicleGateway(app).also { vehicle = it }
        val c = CarPropertyGateway(app).also { carProperty = it }
        val n = NaviGateway(app).also { navi = it }
        val hooks = FakeHooks(
            continueTapped = monitor::onContinueTapped,
            confirmDriver = ::confirmDriver,
            setReservation = trips::setReservationNow,
            resetConsent = trips::resetConsent,
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
        tripRecorder?.let { monitor.removeEventListener(it.onDrivingEvent) }
        val v = vehicle
        val c = carProperty
        val n = navi
        val rec = recorder
        val trips = tripRecorder
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
        tripRecorder = null
        unregisterFakeSignals = null
    }
}
