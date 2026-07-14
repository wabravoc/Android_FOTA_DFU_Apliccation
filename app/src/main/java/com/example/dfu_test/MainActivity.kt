package com.example.dfu_test

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.dfu.FirmwareUpgradeCallback
import io.runtime.mcumgr.dfu.FirmwareUpgradeController
import io.runtime.mcumgr.dfu.FirmwareUpgradeManager
import io.runtime.mcumgr.exception.McuMgrException

class MainActivity : ComponentActivity() {

    private val DEVICE_MAC_ADDRESS = "C6:2D:6E:45:4C:23"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DfuControlScreen(DEVICE_MAC_ADDRESS)
                }
            }
        }
    }
}

@Composable
fun DfuControlScreen(macAddress: String) {
    val context = LocalContext.current

    var statusText by remember { mutableStateOf("Estado: Desconectado") }
    var fileSizeText by remember { mutableStateOf("Tamaño del archivo: 0 KB") }
    var progressValue by remember { mutableStateOf(0f) }
    var isUpgradeEnabled by remember { mutableStateOf(false) }
    var firmwareBytes by remember { mutableStateOf<ByteArray?>(null) }

    val dfuManager = remember {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val bluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress)

        // Inicializar el transporte BLE de McuMgr
        val transport = McuMgrBleTransport(context, bluetoothDevice)

        // Habilitar logs para rastrear la comunicación en el Logcat
        transport.setLoggingEnabled(true)

        FirmwareUpgradeManager(transport, null)
    }

    LaunchedEffect(dfuManager) {
        // Registrar callbacks de la actualización
        dfuManager.setFirmwareUpgradeCallback(object : FirmwareUpgradeCallback {
            override fun onUpgradeStarted(controller: FirmwareUpgradeController?) {
                statusText = "Estado: Actualizando Firmware..."
            }

            override fun onStateChanged(prevState: FirmwareUpgradeManager.State?, newState: FirmwareUpgradeManager.State?) {
                if (newState == FirmwareUpgradeManager.State.RESET) {
                    statusText = "Estado: Enviando orden de reinicio..."
                }
            }

            override fun onUploadProgressChanged(bytesSent: Int, imageSize: Int, timestamp: Long) {
                progressValue = bytesSent.toFloat() / imageSize.toFloat()
            }

            override fun onUpgradeCompleted() {
                statusText = "Estado: ¡Actualización Exitosa!"
                progressValue = 1f
                Toast.makeText(context, "La placa se ha reiniciado con el nuevo FW", Toast.LENGTH_LONG).show()
            }

            override fun onUpgradeCanceled(state: FirmwareUpgradeManager.State) {
                statusText = "Estado: Cancelado"
            }

            override fun onUpgradeFailed(state: FirmwareUpgradeManager.State?, error: McuMgrException?) {
                statusText = "Estado: Falló la actualización: ${error?.message}"
            }
        })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            statusText = "Estado: Enlazado vía BLE"
            Toast.makeText(context, "Conexión lista para transporte DFU", Toast.LENGTH_SHORT).show()
        } else {
            statusText = "Estado: Permisos denegados"
        }
    }

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
                    fileSizeText = "Tamaño del archivo: ${bytes.size / 1024} KB"
                    isUpgradeEnabled = true
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error al leer el archivo ZIP", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                } else {
                    statusText = "Estado: Enlazado vía BLE"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFA))
        ) {
            Text("Conectar a nRF52840", color = Color.White)
        }

        Text(
            text = statusText,
            modifier = Modifier.padding(vertical = 12.dp),
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { filePickerLauncher.launch("application/zip") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Seleccionar Firmware (.zip)", color = Color.White)
        }

        Text(
            text = fileSizeText,
            modifier = Modifier.padding(vertical = 8.dp),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

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

        Button(
            onClick = {
                firmwareBytes?.let { bytes ->
                    try {
                        // 1. Configurar el modo de testeo y confirmación clásico
                        dfuManager.setMode(FirmwareUpgradeManager.Mode.TEST_AND_CONFIRM)

                        // 2. Extractor manual del binario desde el ZIP seleccionado
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
                            statusText = "Estado: Iniciando transferencia..."

                            // 3. Pasar el ByteArray limpio directamente compatible con tu firma de función
                            dfuManager.start(binBytes)
                        } else {
                            statusText = "Error: No se encontró un archivo .bin dentro del ZIP"
                        }

                    } catch (e: McuMgrException) {
                        statusText = "Error al iniciar DFU: ${e.message}"
                    } catch (e: Exception) {
                        statusText = "Error inesperado: ${e.message}"
                    }
                }
            },
            enabled = isUpgradeEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28A745))
        ) {
            Text("Comenzar Actualización", color = Color.White)
        }
    }
}