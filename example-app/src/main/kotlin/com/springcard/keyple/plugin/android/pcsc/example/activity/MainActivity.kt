/*
 * Copyright (c)2022 SpringCard - www.springcard.com.com
 * All right reserved
 * This software is covered by the SpringCard SDK License Agreement - see LICENSE.txt
 */
package com.springcard.keyple.plugin.android.pcsc.example.activity

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.springcard.keyple.plugin.android.pcsc.AndroidPcscPluginFactory
import com.springcard.keyple.plugin.android.pcsc.example.R
import com.springcard.keyple.plugin.android.pcsc.example.adapter.EventAdapter
import com.springcard.keyple.plugin.android.pcsc.example.dialog.PermissionDeniedDialog
import com.springcard.keyple.plugin.android.pcsc.example.model.EventModel
import com.springcard.keyple.plugin.android.pcsc.example.util.PermissionHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/** Activity launched on app start up that display the only screen available on this example app. */
class MainActivity : AppCompatActivity(), EventNotifierSpi {
  /** Variables for event window */
  private lateinit var adapter: RecyclerView.Adapter<*>
  private lateinit var layoutManager: RecyclerView.LayoutManager
  private val events = arrayListOf<EventModel>()

  private val readerManager: ReadersManager = ReadersManager(this)
  private val areReadersInitialized = AtomicBoolean(false)

  private var readerDetectionPending = false
  private val BLE_PERMISSIONS_REQUEST: Int = 1000

  private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothManager.adapter
  }
  private val BluetoothAdapter.isDisabled: Boolean
    get() = !isEnabled

  var requestBluetooth =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
          // granted
          Timber.d("Bluetooth enabled")
          launchBle()
        } else {
          // deny
          Timber.d("Bluetooth disabled")
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(toolbar)
    supportActionBar?.title = "Keyple Demo"
    supportActionBar?.subtitle = "SpringCard AndroidPcsc Plugin"

    /** Init recycler view */
    adapter = EventAdapter(events)
    layoutManager = LinearLayoutManager(this)
    eventRecyclerView.layoutManager = layoutManager
    eventRecyclerView.adapter = adapter
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  /** Called when the activity (screen) is first displayed or resumed from background */
  override fun onResume() {
    super.onResume()
    if (!readerDetectionPending) {
      // Check whether readers are already initialized (return from background) or not (first
      // launch)
      if (!areReadersInitialized.get()) {
        // we need to initialize the readers
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Interface selection")
        builder.setMessage("Please choose the type of interface")

        builder.setPositiveButton("USB") { dialog, which ->
          Toast.makeText(applicationContext, "USB", Toast.LENGTH_SHORT).show()
          launchUsb()
        }

        builder.setNegativeButton("BLE") { dialog, which ->
          Toast.makeText(applicationContext, "BLE", Toast.LENGTH_SHORT).show()
          checkPermissionAndLaunchBle()
        }
        builder.show()
      } else {
        // the readers are already initialized
        addActionEvent("Start card detection")
        readerManager.startCardDetection()
      }
    } else {
      // 1st resume after activation, just reset the flag
      readerDetectionPending = false
    }
  }

  /** Called when the activity (screen) is destroyed or put in background */
  override fun onPause() {
    if (areReadersInitialized.get()) {
      addActionEvent("Stopping card detection")
      readerManager.stopCardDetection()
    }
    super.onPause()
  }

  /** Called when the activity (screen) is destroyed */
  override fun onDestroy() {
    readerManager.cleanUp()
    super.onDestroy()
  }

  override fun onBackPressed() {
    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
      drawerLayout.closeDrawer(GravityCompat.START)
    } else {
      super.onBackPressed()
    }
  }

  override fun onReaderReady() {
    readerDetectionPending = false
    areReadersInitialized.set(true)
  }

  override fun onHeader(header: String) {
    addHeaderEvent(header)
  }

  override fun onAction(action: String) {
    addActionEvent(action)
  }

  override fun onResult(result: String) {
    addResultEvent(result)
  }

  private fun clearEvents() {
    events.clear()
    adapter.notifyDataSetChanged()
  }

  private fun addHeaderEvent(message: String) {
    events.add(EventModel(EventModel.TYPE_HEADER, message))
    updateList()
    Timber.d("Header: %s", message)
  }

  private fun addActionEvent(message: String) {
    events.add(EventModel(EventModel.TYPE_ACTION, message))
    updateList()
    Timber.d("Action: %s", message)
  }

  private fun addResultEvent(message: String) {
    events.add(EventModel(EventModel.TYPE_RESULT, message))
    updateList()
    Timber.d("Result: %s", message)
  }

  private fun updateList() {
    CoroutineScope(Dispatchers.Main).launch {
      adapter.notifyDataSetChanged()
      adapter.notifyItemInserted(events.lastIndex)
      eventRecyclerView.smoothScrollToPosition(events.size - 1)
    }
  }

  private fun launchUsb() {
    addActionEvent("Starting in USB mode...")
    readerDetectionPending = true
    if (!readerManager.initReaders(AndroidPcscPluginFactory.Type.Link.USB)) {
      addActionEvent("USB Reader initialization error.")
    }
  }

  private fun checkPermissionAndLaunchBle() {
    addActionEvent("Starting in BLE mode...")
    readerDetectionPending = true
    packageManager.takeIf { !it.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) }?.also {
      Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
      finish()
    }
    if (PermissionHelper.checkPermission(
        this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), BLE_PERMISSIONS_REQUEST)) {
      Timber.i("BLE permission %s is already granted", Manifest.permission.ACCESS_FINE_LOCATION)
      launchBle()
    } else {
      addActionEvent("Request ACCESS_FINE_LOCATION permission.")
      Timber.i("BLE permission %s is requested", Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    when (requestCode) {
      BLE_PERMISSIONS_REQUEST -> {
        val permissionGranted =
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) {
          launchBle()
        } else {
          PermissionDeniedDialog().apply {
            show(supportFragmentManager, PermissionDeniedDialog::class.java.simpleName)
          }
        }
        return
      }
      // Add other 'when' lines to check for other
      // permissions this app might request.
      else -> {
        // Ignore all other requests.
      }
    }
  }

  private fun launchBle() {
    if (bluetoothAdapter?.isDisabled == true) {
      // when Bluetooth is disabled we first request the user to enable it.
      // the actual launch of the reader scanning will be delayed until the activation confirmation
      // is received
      Timber.i("Bluetooth adapter is disabled.")
      val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
      addActionEvent("Request Bluetooth activation")
      requestBluetooth.launch(enableBluetoothIntent)
    } else {
      // when Bluetooth is a enabled we start immediately the scanning of the readers
      Timber.i("Bluetooth adapter is enabled.")
      if (!readerManager.initReaders(AndroidPcscPluginFactory.Type.Link.BLE)) {
        addActionEvent("BLE Reader initialization error.")
      }
    }
  }
}
