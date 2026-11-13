package com.bobcar.vehicle.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class,
        EnergySnapshotEntity::class,
        VehicleEventEntity::class,
        VehicleBaselineEntity::class,
        DriverEntity::class,
        ReservationEntity::class,
        UploadQueueEntity::class,
        DriverConsentEntity::class,
        CustomerEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class BobCarDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun energySnapshotDao(): EnergySnapshotDao
    abstract fun vehicleEventDao(): VehicleEventDao
    abstract fun vehicleBaselineDao(): VehicleBaselineDao
    abstract fun driverDao(): DriverDao
    abstract fun reservationDao(): ReservationDao
    abstract fun uploadQueueDao(): UploadQueueDao
    abstract fun driverConsentDao(): DriverConsentDao
    abstract fun customerDao(): CustomerDao

    companion object {
        private const val NAME = "bobcar.db"

        @Volatile
        private var instance: BobCarDatabase? = null

        fun get(context: Context): BobCarDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, BobCarDatabase::class.java, NAME)
                    // Development stage: schema changes wipe local data. Replace with migrations before the demo data matters.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
