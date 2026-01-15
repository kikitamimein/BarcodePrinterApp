package com.example.barcodeprinter.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BarcodeItem::class], version = 1, exportSchema = false)
abstract class BarcodeDatabase : RoomDatabase() {
    abstract fun barcodeDao(): BarcodeDao
}
