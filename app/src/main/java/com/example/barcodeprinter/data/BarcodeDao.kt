package com.example.barcodeprinter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM barcode_items WHERE code = :code")
    suspend fun getByCode(code: String): BarcodeItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BarcodeItem)

    @Query("SELECT * FROM barcode_items")
    fun getAll(): Flow<List<BarcodeItem>>
}
