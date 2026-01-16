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
            cmds.append("DIRECTION 1\r\n")
            cmds.append("CLS\r\n")
            
            // BARCODE
            // X, Y, "Type", Height, HumanReadable, Rotation, Narrow, Wide, "Content"
            // X=20 (margin)
            // Y=20 
            // Type=128 (automatically switches subsets) or EAN13
            // Height=160
            // HumanReadable=1 (print text below) or 0 (we verify manually)
            // Rotation=0
            // Narrow=2, Wide=2 (to stretch? Standard is 1,2 or 2,4. Let's try 2,2 or 3,1 for width)
            // If user wants "stretch to full length", we increase module width.
            
            // Checking if barcode is numbers only (EAN13)
            val isEan13 = barcode.length == 13 && barcode.all { it.isDigit() }
            val barcodeType = if (isEan13) "EAN13" else "128"
            
            // To stretch, we increase the narrow bar width. 
            // Default usually 1 or 2 dots. Let's try 3 for wider barcodes if it fits, else 2.
            // 440 dots width. EAN13 has ~95 modules. 95*2=190 (small). 95*4=380 (good).
            // Code128 varies.
            // Let's use moderate width.
            
            // Using logic to print Barcode
            // BARCODE x,y,type,height,human_readable,rotation,narrow,wide,content
            // human_readable=0 because we want to format text manually to reduce spacing
            cmds.append("BARCODE 20,20,\"$barcodeType\",200,0,0,3,6,\"$barcode\"\r\n")
            
            // TEXT (Human Readable Numbers)
            // Just below barcode. Barcode y=20, h=200 -> ends at 220.
            // TEXT x,y,"font",rotation,x_mul,y_mul,"content"
            // Font "0" is internal font. 
            // Let's put it at y=230
            // Spaced out numbers? TSPL doesn't auto-space easy. Just print barcode value.
            cmds.append("TEXT 220,230,\"0\",0,10,10,\"$barcode\"\r\n") // 220 is center x approx?
            // Actually alignment is tricky in raw TSPL without calculation.
            // Let's rely on standard centered approximation.
            // 55mm = 440 dots. Center = 220.
            // But TEXT command x,y is starting point? NO, use BOX or just experimental X.
            // Better: use internal human readable of barcode command if alignment is hard, 
            // BUT user wants specific spacing.
            // Let's try "TEXT" command with centered logic.
            // If we don't know the text width, it's hard to enter.
            // Let's try to enable human readable in BARCODE command first? 
            // User said: "reduce distance between digits and article".
            // If I use built-in human readable, the distance is fixed.
            // So custom text is needed.
            
            // Let's try to approximate X.
            // Or use "BLOCK" command if available? No.
            // Let's guess: "0" font is scalable.
            // x_mul, y_mul = 1 means normal. 10 is huge? No, 1-10 range multiplier.
            // Let's use x_mul=2, y_mul=2 for readable text.
            
            // Let's CENTER approximately.
            // We'll just define a "safe" centered start X for typical 13 digit.
            // EAN13 is wide.
            // Let's try X=100.
            cmds.append("TEXT 80,240,\"2\",0,1,1,\"$barcode\"\r\n")
            // Font "2" is usually good.
            
            // ARTICLE
            // Below numbers. Y=270.
            cmds.append("TEXT 80,270,\"2\",0,1,1,\"$article\"\r\n")
            
            cmds.append("PRINT 1,1\r\n")
            
            val bytes = cmds.toString().toByteArray(java.nio.charset.Charset.forName("GB18030")) // or ASCII
            outputStream.write(bytes)
            outputStream.flush()
        }
    }
}
