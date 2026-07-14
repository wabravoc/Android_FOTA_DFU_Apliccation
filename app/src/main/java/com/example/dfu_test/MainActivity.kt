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
// Official Nordic Semiconductor libraries for firmware upgrading over BLE
import io.runtime.mcumgr.ble.McuMgrBleTransport
import io.runtime.mcumgr.dfu.FirmwareUpgradeCallback
import io.runtime.mcumgr.dfu.FirmwareUpgradeController
import io.runtime.mcumgr.dfu.FirmwareUpgradeManager
import io.runtime.mcumgr.exception.McuMgrException

/**
 * Main Activity of the application.
 * Configures the graphical container using Jetpack Compose and defines the visual theme.
 */
class MainActivity : ComponentActivity() {

    // Target nRF52840 physical hardware MAC address
    private val DEVICE_MAC_ADDRESS = "C6:2D:6E:45:4C:23"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Load the control screen and pass the defined MAC address
                    DfuControlScreen(DEVICE_MAC_ADDRESS)
                }
            }
        }
    }
}

/**
 * Composable UI component that handles all the visual layout,
 * reactive states, and user interactions to perform the DFU process.
 */
@Composable
fun DfuControlScreen(macAddress: String) {
    val context = LocalContext.current

    // --- REACTIVE STATE VARIABLES (Keep UI synchronized upon updates) ---
    var statusText by remember { mutableStateOf("Status: Disconnected") }
    var fileSizeText by remember { mutableStateOf("File size: 0 KB") }
    var progressValue by remember { mutableStateOf(0f) }
    var isUpgradeEnabled by remember { mutableStateOf(false) }
    var firmwareBytes by remember { mutableStateOf<ByteArray?>(null) }

    // --- NORDIC UPGRADE MANAGER CONFIGURATION (MCUmgr) ---
    val dfuManager = remember {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val bluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress)

        // Initialize the MCUmgr BLE transport (Binds the SMP protocol to this device's GATT channel)
        val transport = McuMgrBleTransport(context, bluetoothDevice)

        // Enable debug logs to track the communication packet exchange in the Logcat console
        transport.setLoggingEnabled(true)

        // Instantiate the upgrade manager passing the configured transport
        FirmwareUpgradeManager(transport, null)
    }

    // The LaunchedEffect block runs once when the upgrade manager is initialized
    LaunchedEffect(dfuManager) {
        // Register callbacks to handle asynchronous events and responses from the hardware
        dfuManager.setFirmwareUpgradeCallback(object : FirmwareUpgradeCallback {

            // Triggered when the firmware update process formally starts
            override fun onUpgradeStarted(controller: FirmwareUpgradeController?) {
                statusText = "Status: Upgrading Firmware..."
            }

            // Triggered on internal state changes in the MCUmgr state machine (Validation, Upload, Reset)
            override fun onStateChanged(prevState: FirmwareUpgradeManager.State?, newState: FirmwareUpgradeManager.State?) {
                if (newState == FirmwareUpgradeManager.State.RESET) {
                    statusText = "Status: Sending reboot command..."
                }
            }

            // Calculates the upload progress percentage as BLE data packets are sent
            override fun onUploadProgressChanged(bytesSent: Int, imageSize: Int, timestamp: Long) {
                progressValue = bytesSent.toFloat() / imageSize.toFloat()
            }

            // Success. The chip processed the image, rebooted, and is running the new application
            override fun onUpgradeCompleted() {
                statusText = "Status: Upgrade Successful!"
                progressValue = 1f
                Toast.makeText(context, "The board has rebooted with the new firmware", Toast.LENGTH_LONG).show()
            }

            // Triggered if the user or the app proactively cancels the upload
            override fun onUpgradeCanceled(state: FirmwareUpgradeManager.State) {
                statusText = "Status: Cancelled"
            }

            // Triggered if there is a transmission failure, connection loss, or corrupt image signature
            override fun onUpgradeFailed(state: FirmwareUpgradeManager.State?, error: McuMgrException?) {
                statusText = "Status: Upgrade failed: ${error?.message}"
            }
        })
    }

    // --- HARDWARE PERMISSION LAUNCHER (Required for Android 12 or higher) ---
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            statusText = "Status: Bonded via BLE"
            Toast.makeText(context, "Connection ready for DFU transport", Toast.LENGTH_SHORT).show()
        } else {
            statusText = "Status: Permissions denied"
        }
    }

    // --- FILE SELECTOR LAUNCHER (Android System Storage Picker) ---
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                // Open an input stream to read the ZIP file into a raw byte array in memory
                val inputStream = context.contentResolver.openInputStream(it)
                val bytes = inputStream?.readBytes()
                inputStream?.close()

                if (bytes != null) {
                    firmwareBytes = bytes
                    fileSizeText = "File size: ${bytes.size / 1024} KB"
                    isUpgradeEnabled = true // Enable the "Start Upgrade" button
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error reading the ZIP file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- GRAPHICAL USER INTERFACE LAYOUT ---
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

        // Button 1: Request permissions and connect
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Requests Bluetooth Scan and Connect permissions on Android 12+
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                } else {
                    statusText = "Status: Bonded via BLE"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFA))
        ) {
            Text("Connect to nRF52840", color = Color.White)
        }

        Text(
            text = statusText,
            modifier = Modifier.padding(vertical = 12.dp),
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Button 2: Open file explorer to select the firmware .zip
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

        Spacer(modifier = Modifier.height(32.dp))

        // Horizontal progress bar indicator
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

        // Button 3: Extract the binary and start the DFU transfer
        Button(
            onClick = {
                firmwareBytes?.let { bytes ->
                    try {
                        // 1. Set the classic MCUboot test-and-confirm mode (Uploads, verifies, reboots, and confirms)
                        dfuManager.setMode(FirmwareUpgradeManager.Mode.TEST_AND_CONFIRM)

                        // 2. Manual Zip decompressor to locate and extract the .bin payload on the fly
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

                            // 3. Pass the clean, extracted raw binary directly to the asynchronous upgrade manager
                            dfuManager.start(binBytes)
                        } else {
                            statusText = "Error: No .bin file was found inside the selected ZIP"
                        }

                    } catch (e: McuMgrException) {
                        statusText = "Error starting DFU: ${e.message}"
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
}