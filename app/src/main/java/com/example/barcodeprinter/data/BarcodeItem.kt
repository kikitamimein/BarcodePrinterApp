package com.example.barcodeprinter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "barcode_items")
data class BarcodeItem(
    @PrimaryKey
    val code: String,
    val article: String
)
