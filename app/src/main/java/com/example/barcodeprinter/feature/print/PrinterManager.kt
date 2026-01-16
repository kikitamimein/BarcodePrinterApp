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
            cmds.append("DIRECTION 0\r\n") // Changed to 0 to fix rotation
            cmds.append("CLS\r\n")
            
            // Checking if barcode is numbers only (EAN13)
            val isEan13 = barcode.length == 13 && barcode.all { it.isDigit() }
            val barcodeType = if (isEan13) "EAN13" else "128"
            
            // BARCODE
            // X, Y, "Type", Height, HumanReadable, Rotation, Narrow, Wide, "Content"
            // X=24 (Center horizontally: 440 width. EAN13 Narrow=4 -> ~380 dots wide. (440-380)/2 = 30)
            // Y=20 
            // Height=160 (20mm)
            // HumanReadable=1 (Enable built-in text for standard look)
            // Narrow=4 (Stretch width: 95*4 = 380 dots)
            
            val narrow = if (isEan13) 4 else 2
            // Calculate X to center
            val barcodeWidth = if (isEan13) 380 else barcode.length * 11 * narrow // Approx for 128
            val x = (440 - barcodeWidth) / 2
            val startX = if (x > 0) x else 10

            cmds.append("BARCODE $startX,20,\"$barcodeType\",160,1,0,$narrow,$narrow,\"$barcode\"\r\n")
            
            // ARTICLE TEXT
            // Position: Below barcode built-in text.
            // Barcode Y=20, Height=160. Text usually adds ~40-50 dots. Block ends ~220.
            // Let's place article at Y=230 to be close.
            // Font "2" (Standard)
            cmds.append("TEXT 220,230,\"2\",0,1,1,2,\"$article\"\r\n") // Center alignment needs manual calc or alignment command if available?
            // TSPL TEXT doesn't auto-center unless specific firmware. 
            // We use center coordinates? No, TEXT x,y is start.
            // To center text properly we need to know char width.
            // Font 2 is ~8x12 dots. 
            // Let's simplified: Start at X=20 and let it print? Or estimate center.
            // Better: use a safe left margin since length varies.
            // Or try to center coarsely. 
            // 440 width. Center 220.
            // "TEXT" command has no alignment param in standard TSPL (some extensions do).
            // Let's rely on visual center 220 is NOT center alignment, it's starting position.
            // Wait, for TEXT command: TEXT x, y, "font", rotation, x-mul, y-mul, "content"
            // There is no alignment.
            // We'll estimate start X based on length?
            // "Art: XYZ" ~ 10 chars. 10 * 8 * 1 = 80 dots wide. center = 220 - 40 = 180.
            // Let's just start at X=20 to be safe and left-aligned, or try to center.
            // User requested "like second photo". Second photo text is Centered.
            // In second photo: "Art. NabZak..." is centered.
            // I will use a simple heuristic to center.
            
            val charWidth = 8 // Font 2 approximate width
            val textWidth = article.length * charWidth
            val textX = (440 - textWidth) / 2
            val finalTextX = if (textX > 0) textX else 10
            
            cmds.append("TEXT $finalTextX,230,\"2\",0,1,1,\"$article\"\r\n")
            
            cmds.append("PRINT 1,1\r\n")
            
            val bytes = cmds.toString().toByteArray(java.nio.charset.Charset.forName("GB18030")) 
            outputStream.write(bytes)
            outputStream.flush()
        }
    }
}
