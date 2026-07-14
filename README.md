

# nRF52840 OTA-DFU Android Application (Jetpack Compose)

This repository contains a mobile utility built with **Kotlin** and **Jetpack Compose** designed to perform Over-The-Air (OTA) Device Firmware Updates (DFU) on an **nRF52840** target running Zephyr RTOS / nRF Connect SDK (NCS) v3.1.0.

The application leverages Nordic Semiconductor's official **MCUmgr** library to handle the transmission protocol under the hood.

---

## 🛠️ Architecture & Core Libraries

The DFU process relies on three primary classes provided by the `io.runtime.mcumgr` library:

* **`McuMgrBleTransport`**
    * *Role:* Connection & Physical Transport.
    * *Description:* This class abstracts the entire Android Bluetooth Low Energy (BLE) stack. It transforms raw byte requests into formatted **SMP (Simple Management Protocol)** packets, writing them directly to a specialized GATT characteristic on the peripheral.
* **`FirmwareUpgradeManager`**
    * *Role:* State Machine Controller.
    * *Description:* The core orchestrator of the upgrade. It coordinates the update sequence, chunk sizes, sliding flow control window, automatic packet retries upon radio interference, and final integrity validation checks.
* **`FirmwareUpgradeCallback`**
    * *Role:* Asynchronous Event Listener.
    * *Description:* A bridge interface between the background library thread and the Jetpack Compose UI. It pushes real-time updates regarding transmission progress, state changes, or execution errors directly to the reactive state variables.

---

## 🧬 Under the Hood: How Data Transfer Works

The MCUmgr protocol relies on **SMP (Simple Management Protocol)**, which serializes and packs data into **CBOR** (a lightweight binary-encoded JSON alternative) before transmitting it over the air.

[ Android App ] ──(SMP over CBOR)──> [ BLE GATT Characteristic ] ──> [ nRF52840 Flash ]

1.  **Fragmentation (MTU Adaptation):**
    Since BLE has a limited maximum payload packet size per transmission frame (known as the **MTU** - *Maximum Transmission Unit*), `mcumgr-core` splits your compilation `.bin` file into smaller chunks (typically blocks of ~240 bytes depending on the negotiated MTU).
2.  **Chunk Transmission:**
    The Android application sends the initial chunk over the air to the target's secondary flash memory allocation space (usually **Slot 1**) on the nRF52840.
3.  **Acknowledge (ACK):**
    Once the microcontroller receives the chunk and writes it into internal flash, it returns an acknowledgment packet to the phone.
4.  **Sliding Window:**
    Upon receiving the successful write confirmation, the `FirmwareUpgradeManager` immediately dispatches the next sequential block. This loop repeats until the entire binary is transferred.

---

## 🔄 Step-by-Step DFU Lifecycle

When a user triggers the DFU update process in the application, the system transitions through the following lifecycle:

[App Launched] ──> [Permissions Granted] ──> [ZIP Loaded & Bin Extracted] ──> [DFU Started (SMP)] ──> [Progress 0-100%] ──> [Reboot & Confirm]


### 📍 Step 1: Initialization & Pairing
The `McuMgrBleTransport` is created using the static MAC address of the target board. Pressing **"Connect to nRF52840"** triggers the Android runtime permissions dialog (Bluetooth Scan & Connect). Once granted, the app transitions to `Status: Bonded via BLE`.

### 📍 Step 2: File Selection
Pressing **"Select Firmware (.zip)"** displays the native Android document picker. When the user selects a file, the app reads the stream into memory as a `ByteArray`, displays its total file size, and enables the **"Start Upgrade"** button.

### 📍 Step 3: Binary Extraction (On-the-Fly)
Upon pressing **"Start Upgrade"**, the app instantiates a `ZipInputStream` in memory. It scans the internal directories of the compressed `.zip` file. Once it locates the executable ending with `.bin`, it extracts its raw bytes directly. This prevents the user from having to manually extract files beforehand.

### 📍 Step 4: Live Data Upload
The `dfuManager.start(binBytes)` method initiates the BLE handshake. The `onUploadProgressChanged` callback triggers repeatedly as chunks write to the board. It updates the state float `progressValue`, moving the horizontal progress bar on-screen from `0%` to `100%`.

### 📍 Step 5: System Reboot (`TEST_AND_CONFIRM` Mode)
At `100%`, MCUmgr sends a system control command ordering a soft reset. The chip reboots into **MCUboot** (the bootloader), which:
1. Detects a new image staging in the secondary slot (**Slot 1**).
2. Swaps the active slot (**Slot 0**) and the secondary slot (**Slot 1**).
3. Validates that the new image boots stably.
4. Marks the image as permanent/confirmed so it doesn't roll back on subsequent reboots.



Finally, the callback triggers `onUpgradeCompleted()`, alerting the user of a successful upgrade.