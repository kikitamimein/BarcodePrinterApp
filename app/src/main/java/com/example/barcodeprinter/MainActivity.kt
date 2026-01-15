package com.example.barcodeprinter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.example.barcodeprinter.data.BarcodeDatabase
import com.example.barcodeprinter.data.BarcodeItem
import com.example.barcodeprinter.feature.camera.BarcodeAnalyzer
import com.example.barcodeprinter.feature.print.PrinterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var db: BarcodeDatabase
    private lateinit var printerManager: PrinterManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = Room.databaseBuilder(
            applicationContext,
            BarcodeDatabase::class.java, "barcode-db"
        ).build()
        
        printerManager = PrinterManager(this)

        setContent {
            BarcodeApp(db, printerManager)
        }
    }
}

@Composable
fun BarcodeApp(db: BarcodeDatabase, printerManager: PrinterManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var scannedCode by remember { mutableStateOf<String?>(null) }
    var foundItem by remember { mutableStateOf<BarcodeItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    // Settings State
    var printerIp by remember { mutableStateOf("192.168.1.100") }
    var printerPort by remember { mutableStateOf("9100") }

    // Permission
    var hasCameraPermission by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )
    
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showSettingsDialog = true }) {
                Text("Settings")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Top 1/3 Camera
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)) {
                if (hasCameraPermission) {
                    CameraPreview(
                        onBarcodeScanned = { code ->
                            if (scannedCode != code) {
                                scannedCode = code
                                scope.launch {
                                    foundItem = db.barcodeDao().getByCode(code)
                                    if (foundItem == null) {
                                        showAddDialog = true
                                    }
                                }
                            }
                        }
                    )
                } else {
                    Text("Camera permission required", modifier = Modifier.align(Alignment.Center))
                }
            }

            // Middle & Bottom Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (scannedCode != null) {
                    Text("Scanned: $scannedCode", style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (foundItem != null) {
                        Text("Article: ${foundItem?.article}", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    printLabel(context, printerManager, scannedCode!!, foundItem!!.article, printerIp, printerPort.toIntOrNull() ?: 9100)
                                    // Reset scan after print
                                    scannedCode = null
                                    foundItem = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Print Label")
                        }
                    } else {
                        Text("Unknown Item", color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { showAddDialog = true },
                             modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add Item")
                        }
                    }
                } else {
                    Text("Scan a barcode...", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (showAddDialog && scannedCode != null) {
        var newArticle by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { 
                showAddDialog = false 
                scannedCode = null // Dismiss resets scan
            },
            title = { Text("Add New Item") },
            text = {
                Column {
                    Text("Barcode: $scannedCode")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newArticle,
                        onValueChange = { newArticle = it },
                        label = { Text("Article") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newArticle.isNotBlank()) {
                         scope.launch {
                             val item = BarcodeItem(scannedCode!!, newArticle)
                             db.barcodeDao().insert(item)
                             foundItem = item
                             showAddDialog = false
                             // Auto print logic could go here, or user clicks print manually.
                             // Requirement: "при успешном считывании(а так же после добавления нового шк) приложение генерирует стикер"
                             printLabel(context, printerManager, item.code, item.article, printerIp, printerPort.toIntOrNull() ?: 9100)
                             scannedCode = null
                             foundItem = null
                         }
                    }
                }) {
                    Text("Save & Print")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    scannedCode = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    val txtHelper = remember { com.example.barcodeprinter.data.TxtHelper() }

    // Export Launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    try {
                        val items = db.barcodeDao().getAllList()
                        context.contentResolver.openOutputStream(it)?.use { output ->
                            txtHelper.writeToTxt(output, items)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Database exported successfully", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    )

    // Import Launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(it)?.use { input ->
                            val items = txtHelper.readFromTxt(input)
                            for (item in items) {
                                db.barcodeDao().insert(item)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Database imported successfully", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    )

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Printer Settings") },
            text = {
                Column {
                    OutlinedTextField(value = printerIp, onValueChange = { printerIp = it }, label = { Text("IP Address") })
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = printerPort, onValueChange = { printerPort = it }, label = { Text("Port") })
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Database Management", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = { importLauncher.launch(arrayOf("text/plain")) },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        ) {
                            Text("Import Txt")
                        }
                        
                        Button(
                            onClick = { exportLauncher.launch("barcode_db.txt") },
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        ) {
                            Text("Export Txt")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

suspend fun printLabel(context: Context, manager: PrinterManager, code: String, article: String, ip: String, port: Int) {
    try {
        val pdf = manager.generatePdf(code, article)
        manager.sendToPrinter(pdf, ip, port)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Sent to printer", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
         withContext(Dispatchers.Main) {
            Toast.makeText(context, "Print failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
        Log.e("Print", "Error", e)
    }
}

@Composable
fun CameraPreview(onBarcodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            previewView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            val executor = Executors.newSingleThreadExecutor()
            
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor, BarcodeAnalyzer { code ->
                            // Post to main thread
                            previewView.post { onBarcodeScanned(code) }
                        })
                    }
                
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch(e: Exception) {
                    Log.e("Camera", "Binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
