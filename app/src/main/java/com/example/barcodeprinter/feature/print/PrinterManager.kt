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
        val format = if (barcode.length == 13 && barcode.all { it.isDigit() }) {
             com.google.zxing.BarcodeFormat.EAN_13
        } else {
             com.google.zxing.BarcodeFormat.CODE_128
        }
        
        try {
            // Barcode height ~80pts (about 70% of 113 height)
            // Stretch width: Use essentially full width (minus small margin)
            // 156 width total. Let's use 150 width.
            val barcodeBitmap = createBarcodeBitmap(barcode, format, 150, 70)
            // Center horizontally: (156 - 150) / 2 = 3
            canvas.drawBitmap(barcodeBitmap, 3f, 10f, null)
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
        paint.textSize = 10f // Slightly smaller to fit if needed
        paint.textAlign = Paint.Align.CENTER
        
        // Position: Just below barcode. 
        // Barcode ends at y=10+70=80.
        // Draw text at y=90 (was 92, moving up closer)
        canvas.drawText(barcode.chunked(1).joinToString(" "), pageWidth / 2f, 90f, paint)

        // 3. Draw Article Text (Bottom, Small)
        paint.textSize = 8f // Increased visibility
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        // Position: closer to numbers. Numbers are at 90. 
        // Let's put article at 102 (was 108).
        canvas.drawText("$article", pageWidth / 2f, 102f, paint)

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

    suspend fun printLabel(barcode: String, article: String, ip: String, port: Int) = withContext(Dispatchers.IO) {
        Socket(ip, port).use { socket ->
            val outputStream = socket.getOutputStream()
            
            // TSPL Commands for 55mm x 40mm label
            // 203 DPI = ~8 dots/mm
            // Width: 55 * 8 = 440 dots
            // Height: 40 * 8 = 320 dots
            
            val cmds = StringBuilder()
            cmds.append("SIZE 55 mm, 40 mm\r\n")
            cmds.append("GAP 2 mm, 0 mm\r\n")
            cmds.append("DIRECTION 0\r\n")
            cmds.append("CLS\r\n")
            
            // Checking if barcode is numbers only (EAN13)
            val isEan13 = barcode.length == 13 && barcode.all { it.isDigit() }
            val barcodeType = if (isEan13) "EAN13" else "128"
            
            // Width Calculation
            // EAN13: 95 modules.
            // Code 128 (Digits): Pack 2 digits per symbol (Code C).
            // Length 12 digits -> ~6 data symbols + start/check/stop ~100 modules.
            // If we use narrow=4: 100*4 = 400 dots. Fits in 440.
            // If longer, we must reduce to 3 or 2.
            val isCompactNum = barcode.all { it.isDigit() } && barcode.length <= 14
            val narrow = if (isEan13 || isCompactNum) 3 else 2 
            // Using 3 is safer for "Full Length" without overflow risk for slightly longer codes.
            // 4 might be too tight for edge cases. 
            // 3: 100*3 = 300. 300/440 = 68%. 
            // Let's try 4 for short numeric codes to really stretch it? 
            // User requested "Full length". 
            val finalNarrow = if (barcode.length <= 12 && barcode.all { it.isDigit() }) 4 else 3

            // Height Calculation
            // User wants minimal distance between Code and Article (at bottom).
            // Article Y ~ 270. (Font height ~20-30).
            // Barcode Text (built-in) requires ~30 dots.
            // So Barcode Bars should end at ~240.
            // Start Y = 20. Height = 220.
            val barHeight = 220

            // Layout Barcode
            // Calculate Center X
            // Approx width estimate
            val estimatedWidth = if (isEan13) 95 * finalNarrow else (barcode.length * 11 * finalNarrow) // Rough calc for 128
            // For Code 128C it is much shorter: (Len/2 + 4) * 11 * narrow.
            val actualWidth = if (isEan13) 95*finalNarrow else if(barcode.all { it.isDigit() }) ((barcode.length/2 + 5)*11*finalNarrow) else (barcode.length * 11 * finalNarrow)
            
            val x = (440 - actualWidth) / 2
            val startX = if (x > 20) x else 20

            cmds.append("BARCODE $startX,20,\"$barcodeType\",$barHeight,1,0,$finalNarrow,$finalNarrow,\"$barcode\"\r\n")
            
            // ARTICLE TEXT
            // Position: Very bottom.
            // 40mm = 320 dots.
            // Let's place at 280 (leaving 40 dots / 5mm margin at bottom)
            val fullArticle = "Арт: $article"
            
            // Center Text
            val charWidth = 10 // Font 2 approx width scaled? Font 2 is 8x12.
            // Let's estimate
            val textWidth = fullArticle.length * 8
            val textX = (440 - textWidth) / 2
            val finalTx = if (textX > 10) textX else 10
            
            cmds.append("TEXT $finalTx,275,\"2\",0,1,1,\"$fullArticle\"\r\n")
            
            cmds.append("PRINT 1,1\r\n")
            
            val bytes = cmds.toString().toByteArray(java.nio.charset.Charset.forName("GB18030")) 
            outputStream.write(bytes)
            outputStream.flush()
        }
    }
}
