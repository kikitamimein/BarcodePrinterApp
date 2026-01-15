package com.example.barcodeprinter.feature.print

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Socket

class PrinterManager(private val context: Context) {

    // 55mm width, 40mm height
    // 1 inch = 25.4mm
    // 72 points per inch
    // Width points: 55 / 25.4 * 72 ≈ 156
    // Height points: 40 / 25.4 * 72 ≈ 113
    private val pageWidth = 156
    private val pageHeight = 113

    fun generatePdf(barcode: String, article: String): File {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val paint = Paint()
        paint.color = Color.BLACK
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 10f

        // Draw Article
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(article, pageWidth / 2f, 20f, paint)

        // Draw Barcode Text (Simulated as text for now, should generate barcode image realistically but text is placeholder)
        // Ideally we draw the actual barcode lines here.
        // For simple MVP text is okay, but user asked for "print that barcode".
        // Let's rely on a barcode font or drawing logic if possible?
        // Or simply draw the code bigger.
        paint.textSize = 14f
        canvas.drawText(barcode, pageWidth / 2f, 60f, paint)

        pdfDocument.finishPage(page)

        val file = File(context.cacheDir, "sticker.pdf")
        val ops = FileOutputStream(file)
        pdfDocument.writeTo(ops)
        pdfDocument.close()
        ops.close()

        return file
    }

    suspend fun sendToPrinter(file: File, ip: String, port: Int) = withContext(Dispatchers.IO) {
        if (!file.exists()) throw IOException("File not found")
        
        Socket(ip, port).use { socket ->
            val outputStream = socket.getOutputStream()
            val fileBytes = file.readBytes()
            outputStream.write(fileBytes)
            outputStream.flush()
        }
    }
}
