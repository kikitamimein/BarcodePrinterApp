package com.example.barcodeprinter.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

class TxtHelper {

    fun readFromTxt(inputStream: InputStream): List<BarcodeItem> {
        val items = mutableListOf<BarcodeItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.useLines { lines ->
                lines.forEach { line ->
                    // Expected format: barcode-article
                    // We split by the first hyphen only, allowing hyphens in article name
                    val parts = line.split("-", limit = 2)
                    if (parts.size == 2) {
                        val barcode = parts[0].trim()
                        val article = parts[1].trim()
                        if (barcode.isNotEmpty() && article.isNotEmpty()) {
                            items.add(BarcodeItem(code = barcode, article = article))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    fun writeToTxt(outputStream: OutputStream, items: List<BarcodeItem>) {
        try {
            val writer = outputStream.writer()
            items.forEach { item ->
                writer.write("${item.code}-${item.article}\n")
            }
            writer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
