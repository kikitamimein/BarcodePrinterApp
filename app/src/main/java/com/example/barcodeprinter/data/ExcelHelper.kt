package com.example.barcodeprinter.data

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream
import java.io.OutputStream

class ExcelHelper {

    fun readFromExcel(inputStream: InputStream): List<BarcodeItem> {
        val items = mutableListOf<BarcodeItem>()
        try {
            val workbook = XSSFWorkbook(inputStream)
            val sheet = workbook.getSheetAt(0)

            for (row in sheet) {
                // Skip header or empty rows if necessary, but assuming simple 2-column format
                // Column 0: Barcode, Column 1: Article
                val barcodeCell = row.getCell(0)
                val articleCell = row.getCell(1)

                if (barcodeCell == null || articleCell == null) continue

                val barcode = barcodeCell.toString().trim()
                val article = articleCell.toString().trim()

                if (barcode.isNotEmpty() && article.isNotEmpty()) {
                    items.add(BarcodeItem(code = barcode, article = article))
                }
            }
            workbook.close()
        } catch (e: Exception) {
            e.printStackTrace()
            // In a real app, propagate error or log it
        }
        return items
    }

    fun writeToExcel(outputStream: OutputStream, items: List<BarcodeItem>) {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Database")

        // Header
        val headerRow = sheet.createRow(0)
        headerRow.createCell(0).setCellValue("Barcode")
        headerRow.createCell(1).setCellValue("Article")

        // Key items
        items.forEachIndexed { index, item ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(item.code)
            row.createCell(1).setCellValue(item.article)
        }

        try {
            workbook.write(outputStream)
            workbook.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
