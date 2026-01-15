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
        
        // 1. Draw Barcode Image
        // Use CODE_128 as fallback, but if it looks like EAN-13, use EAN-13 for better look
        val format = if (barcode.length == 13 && barcode.all { it.isDigit() }) {
             com.google.zxing.BarcodeFormat.EAN_13
        } else {
             com.google.zxing.BarcodeFormat.CODE_128
        }
        
        try {
            // Barcode height ~80pts (about 70% of 113 height)
            val barcodeBitmap = createBarcodeBitmap(barcode, format, 140, 70)
            // Center horizontally: (156 - 140) / 2 = 8
            canvas.drawBitmap(barcodeBitmap, 8f, 10f, null)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback text if generation fails
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 12f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("No Barcode", pageWidth / 2f, 40f, paint)
        }

        // 2. Draw Barcode Numbers (Human Readable)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 12f
        paint.textAlign = Paint.Align.CENTER
        // Provide spacing between digits if EAN-13? For now simple text.
        // Position: below barcode (10 + 70 + 12 approx) -> 92
        canvas.drawText(barcode.chunked(1).joinToString(" "), pageWidth / 2f, 92f, paint)

        // 3. Draw Article Text (Bottom, Small)
        paint.textSize = 6f
        paint.textAlign = Paint.Align.CENTER
        // Bottom position: pageHeight - 5 -> 108
        canvas.drawText("Aрт: $article", pageWidth / 2f, 108f, paint)

        pdfDocument.finishPage(page)

        val file = File(context.cacheDir, "sticker.pdf")
        val ops = FileOutputStream(file)
        pdfDocument.writeTo(ops)
        pdfDocument.close()
        ops.close()

        return file
    }
    
    private fun createBarcodeBitmap(contents: String, format: com.google.zxing.BarcodeFormat, width: Int, height: Int): android.graphics.Bitmap {
        val writer = com.google.zxing.MultiFormatWriter()
        val bitMatrix = writer.encode(contents, format, width, height)
        val w = bitMatrix.width
        val h = bitMatrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (bitMatrix[x, y]) Color.BLACK else Color.TRANSPARENT
            }
        }
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
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
