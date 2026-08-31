package com.example.dfu_test

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Librerías de Nordic Semiconductor para MCUmgr / DFU
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.dfu.FirmwareUpgradeCallback
import io.runtime.mcumgr.dfu.FirmwareUpgradeController
import io.runtime.mcumgr.dfu.FirmwareUpgradeManager
import io.runtime.mcumgr.exception.McuMgrException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DfuControlScreen()
                }
            }
        }
    }
}

/**
 * Representa un dispositivo BLE detectado en el escaneo
 */
data class BleDeviceItem(
    val name: String,
    val address: String,
    val device: BluetoothDevice
)

@SuppressLint("MissingPermission")
@Composable
fun DfuControlScreen() {
    val context = LocalContext.current

    // --- ESTADOS REACTIVOS ---
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var selectedMacAddress by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Status: No device selected") }
    var fileSizeText by remember { mutableStateOf("File size: 0 KB") }
    var progressValue by remember { mutableStateOf(0f) }
    var isUpgradeEnabled by remember { mutableStateOf(false) }
    var firmwareBytes by remember { mutableStateOf<ByteArray?>(null) }

    // Estados para el Escaneo BLE
    var isScanning by remember { mutableStateOf(false) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    val discoveredDevices = remember { mutableStateListOf<BleDeviceItem>() }

    // --- INSTANCIA DINÁMICA DE FIRMWARE UPGRADE MANAGER ---
    val dfuManager = remember(selectedDevice) {
        selectedDevice?.let { device ->
            val transport = McuMgrBleTransport(context, device)
            transport.setLoggingEnabled(true)
            FirmwareUpgradeManager(transport, null)
        }
    }

    // Configuración de callbacks del DFU Manager cuando se selecciona un dispositivo
    LaunchedEffect(dfuManager) {
        dfuManager?.setFirmwareUpgradeCallback(object : FirmwareUpgradeCallback {
            override fun onUpgradeStarted(controller: FirmwareUpgradeController?) {
                statusText = "Status: Upgrading Firmware..."
            }

            override fun onStateChanged(prevState: FirmwareUpgradeManager.State?, newState: FirmwareUpgradeManager.State?) {
                if (newState == FirmwareUpgradeManager.State.RESET) {
                    statusText = "Status: Sending reboot command..."
                }
            }

            override fun onUploadProgressChanged(bytesSent: Int, imageSize: Int, timestamp: Long) {
                progressValue = bytesSent.toFloat() / imageSize.toFloat()
            }

            override fun onUpgradeCompleted() {
                statusText = "Status: Upgrade Successful!"
                progressValue = 1f
                Toast.makeText(context, "Board rebooted with new firmware", Toast.LENGTH_LONG).show()
            }

            override fun onUpgradeCanceled(state: FirmwareUpgradeManager.State) {
                statusText = "Status: Cancelled"
            }

            override fun onUpgradeFailed(state: FirmwareUpgradeManager.State?, error: McuMgrException?) {
                statusText = "Status: Upgrade failed: ${error?.message}"
            }
        })
    }

    // --- FUNCIÓN PARA INICIAR EL ESCANEO BLE ---
    fun startBleScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner

        if (scanner == null) {
            Toast.makeText(context, "Bluetooth no disponible o desactivado", Toast.LENGTH_SHORT).show()
            return
        }

        discoveredDevices.clear()
        isScanning = true
        showDeviceDialog = true

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val deviceName = device.name ?: "Desconocido / Sin Nombre"
                    val deviceAddress = device.address

                    // Evita duplicados en la lista visual
                    if (discoveredDevices.none { it.address == deviceAddress }) {
                        discoveredDevices.add(BleDeviceItem(deviceName, deviceAddress, device))
                    }
                }
            }
        }

        // Iniciar escaneo
        scanner.startScan(scanCallback)

        // Detener el escaneo automáticamente tras 7 segundos para ahorrar batería
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                scanner.stopScan(scanCallback)
            } catch (e: Exception) { /* Ignorar si ya fue cerrado */ }
            isScanning = false
        }, 7000)
    }

    // --- LAUNCHER DE PERMISOS ---
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startBleScan()
        } else {
            Toast.makeText(context, "Permisos denegados para buscar dispositivos", Toast.LENGTH_SHORT).show()
        }
    }

    // --- SELECTOR DE ARCHIVO ZIP ---
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bytes = inputStream?.readBytes()
                inputStream?.close()

                if (bytes != null) {
                    firmwareBytes = bytes
                    fileSizeText = "File size: ${bytes.size / 1024} KB"
                    if (selectedDevice != null) {
                        isUpgradeEnabled = true
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error al leer el archivo .zip", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- INTERFAZ GRÁFICA PRINCIPAL ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "nRF52840 OTA DFU (Compose)",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

// Botón 1: Buscar y Seleccionar Dispositivo BLE
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12 o superior (API 31+)
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                } else {
                    // Android 11 o inferior
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFA))
        ) {
            Text("Buscar Dispositivos BLE", color = Color.White)
        }

        // Muestra la MAC seleccionada actualmente
        selectedMacAddress?.let { mac ->
            Text(
                text = "MAC Seleccionada: $mac",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF28A745),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Text(
            text = statusText,
            modifier = Modifier.padding(vertical = 12.dp),
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Botón 2: Seleccionar Archivo .zip / .bin
        Button(
            onClick = { filePickerLauncher.launch("application/zip") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select Firmware (.zip)", color = Color.White)
        }

        Text(
            text = fileSizeText,
            modifier = Modifier.padding(vertical = 8.dp),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Barra de progreso
        LinearProgressIndicator(
            progress = { progressValue },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
        )

        Text(
            text = "${(progressValue * 100).toInt()}%",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Botón 3: Iniciar Transferencia DFU
        Button(
            onClick = {
                if (dfuManager == null) {
                    Toast.makeText(context, "Selecciona un dispositivo primero", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                firmwareBytes?.let { bytes ->
                    try {
                        dfuManager.setMode(FirmwareUpgradeManager.Mode.TEST_AND_CONFIRM)

                        // Descompresión del ZIP para extraer el archivo binario payload
                        val zipInputStream = java.util.zip.ZipInputStream(bytes.inputStream())
                        var entry = zipInputStream.nextEntry
                        var binBytes: ByteArray? = null

                        while (entry != null) {
                            if (entry.name.endsWith(".bin")) {
                                binBytes = zipInputStream.readBytes()
                                break
                            }
                            entry = zipInputStream.nextEntry
                        }
                        zipInputStream.close()

                        if (binBytes != null) {
                            statusText = "Status: Initiating transfer..."
                            dfuManager.start(binBytes)
                        } else {
                            statusText = "Error: No .bin found inside ZIP"
                        }

                    } catch (e: McuMgrException) {
                        statusText = "Error DFU: ${e.message}"
                    } catch (e: Exception) {
                        statusText = "Unexpected error: ${e.message}"
                    }
                }
            },
            enabled = isUpgradeEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28A745))
        ) {
            Text("Start Upgrade", color = Color.White)
        }
    }

    // --- VENTANA / DIÁLOGO DE SELECCIÓN DE DISPOSITIVOS ---
    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Seleccionar Dispositivo")
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            },
            text = {
                if (discoveredDevices.isEmpty()) {
                    Text("Buscando dispositivos cercanos...")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(discoveredDevices) { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDevice = item.device
                                        selectedMacAddress = item.address
                                        statusText = "Dispositivo seleccionado: ${item.address}"
                                        showDeviceDialog = false
                                        if (firmwareBytes != null) {
                                            isUpgradeEnabled = true
                                        }
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = item.address,
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}