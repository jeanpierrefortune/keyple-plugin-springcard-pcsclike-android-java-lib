/*
 * Copyright (c) 2018-2018-2018 SpringCard - www.springcard.com
 * All right reserved
 * This software is covered by the SpringCard SDK License Agreement - see LICENSE.txt
 */
package com.springcard.keyple.plugin.android.pcsc.example.activity

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.MenuItem
import android.widget.Toast
import androidx.core.view.GravityCompat
import com.springcard.keyple.plugin.android.pcsc.AndroidPcscPlugin
import com.springcard.keyple.plugin.android.pcsc.AndroidPcscPluginFactory
import com.springcard.keyple.plugin.android.pcsc.AndroidPcscPluginFactoryProvider
import com.springcard.keyple.plugin.android.pcsc.AndroidPcscSupportContactlessProtocols
import com.springcard.keyple.plugin.android.pcsc.DeviceInfo
import com.springcard.keyple.plugin.android.pcsc.example.R
import com.springcard.keyple.plugin.android.pcsc.example.dialog.PermissionDeniedDialog
import com.springcard.keyple.plugin.android.pcsc.example.util.CalypsoClassicInfo
import com.springcard.keyple.plugin.android.pcsc.example.util.PermissionHelper
import com.springcard.keyple.plugin.android.pcsc.spi.DeviceScannerSpi
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.calypsonet.terminal.calypso.WriteAccessLevel
import org.calypsonet.terminal.calypso.card.CalypsoCard
import org.calypsonet.terminal.reader.CardReaderEvent
import org.calypsonet.terminal.reader.ObservableCardReader
import org.calypsonet.terminal.reader.selection.CardSelectionManager
import org.calypsonet.terminal.reader.selection.CardSelectionResult
import org.calypsonet.terminal.reader.selection.ScheduledCardSelectionsResponse
import org.eclipse.keyple.core.common.KeyplePluginExtensionFactory
import org.eclipse.keyple.core.service.ConfigurableReader
import org.eclipse.keyple.core.service.KeyplePluginException
import org.eclipse.keyple.core.service.ObservablePlugin
import org.eclipse.keyple.core.service.ObservableReader
import org.eclipse.keyple.core.service.Plugin
import org.eclipse.keyple.core.service.PluginEvent
import org.eclipse.keyple.core.service.SmartCardServiceProvider
import org.eclipse.keyple.core.service.spi.PluginObserverSpi
import org.eclipse.keyple.core.util.ByteArrayUtil
import timber.log.Timber

/** Activity launched on app start up that display the only screen available on this example app. */
class MainActivity : AbstractExampleActivity(), PluginObserverSpi, DeviceScannerSpi {
  private lateinit var androidPcscPlugin: Plugin
  private lateinit var cardSelectionManager: CardSelectionManager
  private lateinit var cardProtocol: AndroidPcscSupportContactlessProtocols

  private val areReadersInitialized = AtomicBoolean(false)

  private lateinit var progress: ProgressDialog

  private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothManager.adapter
  }

  private val BluetoothAdapter.isDisabled: Boolean
    get() = !isEnabled

  private enum class TransactionType {
    DECREASE,
    INCREASE
  }

  override fun initContentView() {
    setContentView(R.layout.activity_main)
    initActionBar(toolbar, "Keyple demo", "AndroidPcsc Plugin")
  }

  /** Called when the activity (screen) is first displayed or resumed from background */
  override fun onResume() {
    super.onResume()

    progress = ProgressDialog(this)
    progress.setMessage(getString(R.string.please_wait))
    progress.setCancelable(false)

    // Check whether readers are already initialized (return from background) or not (first launch)
    if (!areReadersInitialized.get()) {
      val builder = AlertDialog.Builder(this)
      builder.setTitle("Interface selection")
      builder.setMessage("Please choose the type of interface")
      //      builder.setPositiveButton("OK", DialogInterface.OnClickListener(function = x))

      builder.setPositiveButton("USB") { dialog, which ->
        Toast.makeText(applicationContext, "USB", Toast.LENGTH_SHORT).show()
        progress.show()
        addActionEvent("Enabling USB Reader mode")
        initReaders(AndroidPcscPluginFactory.Type.Link.USB)
      }

      builder.setNegativeButton("BLE") { dialog, which ->
        Toast.makeText(applicationContext, "BLE", Toast.LENGTH_SHORT).show()
        progress.show()
        addActionEvent("Enabling BLE Reader mode")
        initReaders(AndroidPcscPluginFactory.Type.Link.BLE)
      }
      builder.show()
    } else {
      addActionEvent("Start card Read Write Mode")
      cardReader.startCardDetection(ObservableCardReader.DetectionMode.REPEATING)
    }
  }

  /** Initializes the card reader (Contact Reader) and SAM reader (Contactless Reader) */
  override fun initReaders(link: AndroidPcscPluginFactory.Type.Link) {
    Timber.d("initReaders")
    // Connexion to AndroidPcsc lib take time, we've added a callback to this factory.
    GlobalScope.launch {
      val pluginFactory: KeyplePluginExtensionFactory?
      try {
        Timber.d("Create plugin factory for link ${link.name}")
        pluginFactory =
            withContext(Dispatchers.IO) {
              AndroidPcscPluginFactoryProvider.getFactory(link, applicationContext)
            }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) { showAlertDialog(e, finish = true, cancelable = false) }
        return@launch
      }

      // Get the instance of the SmartCardService (Singleton pattern)
      val smartCardService = SmartCardServiceProvider.getService()

      // Register the AndroidPcsc with SmartCardService, get the corresponding generic Plugin in
      // return
      androidPcscPlugin = smartCardService.registerPlugin(pluginFactory)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val PERMISSION_REQUEST_FINE_LOCATION = 5
        /* Android Permission check */
        /* As of Android M (6.0) and above, location permission is required for the app          to get BLE scan results.                                  */
        /* The main motivation behind having to explicitly require the users to grant          this permission is to protect users’ privacy.                */
        /* A BLE scan can often unintentionally reveal the user’s location to          unscrupulous app developers who scan for specific BLE beacons,       */
        /* or some BLE device may advertise location-specific information. Before          Android 10, ACCESS_COARSE_LOCATION can be used to gain access   */
        /* to BLE scan results, but we recommend using ACCESS_FINE_LOCATION instead          since it works for all versions of Android.                   */
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
          requestPermissions(
              arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_FINE_LOCATION)
        }
      }

      /* Set up BLE */
      val REQUEST_ENABLE_BT = 6
      /* Ensures Bluetooth is available on the device and it is enabled. If not, */
      /* displays a dialog requesting user permission to enable Bluetooth. */

      bluetoothAdapter?.takeIf { it.isDisabled }?.apply {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
      }

      addActionEvent("Scanning compliant ${link.name} devices...")
      androidPcscPlugin
          .getExtension(AndroidPcscPlugin::class.java)
          .scanDevices(
              2,
              true,
              this@MainActivity,
          )

      (androidPcscPlugin as ObservablePlugin).setPluginObservationExceptionHandler { pluginName, e
        ->
        Timber.e("An unexpected reader error occurred: $pluginName : $e")
      }

      (androidPcscPlugin as ObservablePlugin).addObserver(this@MainActivity)

      withContext(Dispatchers.Main) { progress.dismiss() }
    }
  }

  /** Called when the activity (screen) is destroyed or put in background */
  override fun onPause() {
    if (areReadersInitialized.get()) {
      addActionEvent("Stopping card Read Write Mode")
      // Stop NFC card detection
      cardReader.stopCardDetection()
    }
    super.onPause()
  }

  /** Called when the activity (screen) is destroyed */
  override fun onDestroy() {
    cardReader?.let {
      // stop propagating the reader events
      cardReader.removeObserver(this)
    }

    // Unregister the AndroidPcsc plugin
    SmartCardServiceProvider.getService().plugins.forEach {
      SmartCardServiceProvider.getService().unregisterPlugin(it.name)
    }

    super.onDestroy()
  }

  override fun onBackPressed() {
    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
      drawerLayout.closeDrawer(GravityCompat.START)
    } else {
      super.onBackPressed()
    }
  }

  override fun onNavigationItemSelected(item: MenuItem): Boolean {
    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
      drawerLayout.closeDrawer(GravityCompat.START)
    }
    when (item.itemId) {
      R.id.usecase1 -> {
        clearEvents()
        addHeaderEvent("Running Calypso Read transaction (without SAM)")
        configureCalypsoTransaction(::runCardReadTransactionWithoutSam)
      }
      R.id.usecase2 -> {
        clearEvents()
        addHeaderEvent("Running Calypso Read transaction (with SAM)")
        configureCalypsoTransaction(::runCardReadTransactionWithSam)
      }
      R.id.usecase3 -> {
        clearEvents()
        addHeaderEvent("Running Calypso Read/Write transaction")
        configureCalypsoTransaction(::runCardReadWriteIncreaseTransaction)
      }
      R.id.usecase4 -> {
        clearEvents()
        addHeaderEvent("Running Calypso Read/Write transaction")
        configureCalypsoTransaction(::runCardReadWriteDecreaseTransaction)
      }
    }
    return true
  }

  override fun onReaderEvent(readerEvent: CardReaderEvent?) {
    addResultEvent("New ReaderEvent received : ${readerEvent?.type?.name}")

    CoroutineScope(Dispatchers.Main).launch {
      when (readerEvent?.type) {
        CardReaderEvent.Type.CARD_MATCHED -> {
          val selectionsResult =
              cardSelectionManager.parseScheduledCardSelectionsResponse(
                  readerEvent.scheduledCardSelectionsResponse)
          val calypsoCard = selectionsResult.activeSmartCard as CalypsoCard
          addResultEvent(
              "Card ${ByteArrayUtil.toHex(calypsoCard.applicationSerialNumber)} detected with DFNAME: ${ByteArrayUtil.toHex(calypsoCard.dfName)}")
          val efEnvironmentHolder =
              calypsoCard.getFileBySfi(CalypsoClassicInfo.SFI_EnvironmentAndHolder)
          addResultEvent(
              "Environment and Holder file:\n${
              ByteArrayUtil.toHex(
                efEnvironmentHolder.data.content
              )
            }")
          GlobalScope.launch(Dispatchers.IO) {
            try {
              CalypsoTransaction.runCardReadTransaction(cardReader, calypsoCard, false)
              val counter =
                  calypsoCard
                      .getFileBySfi(CalypsoClassicInfo.SFI_Counter1)
                      .data
                      .getContentAsCounterValue(CalypsoClassicInfo.RECORD_NUMBER_1.toInt())
              val eventLog =
                  ByteArrayUtil.toHex(
                      calypsoCard.getFileBySfi(CalypsoClassicInfo.SFI_EventLog).data.content)
              addResultEvent("Counter value: $counter")
              addResultEvent("EventLog file:\n$eventLog")
            } catch (e: KeyplePluginException) {
              Timber.e(e)
              addResultEvent("Exception: ${e.message}")
            } catch (e: Exception) {
              Timber.e(e)
              addResultEvent("Exception: ${e.message}")
            }
          }
          cardReader.finalizeCardProcessing()
        }
        CardReaderEvent.Type.CARD_INSERTED -> {
          addResultEvent("Card detected but AID didn't match with ${CalypsoClassicInfo.AID}")
          cardReader.finalizeCardProcessing()
        }
        CardReaderEvent.Type.CARD_REMOVED -> {
          addResultEvent("Card removed")
        }
        else -> {
          // Do nothing
        }
      }
    }
  }

  private fun configureCalypsoTransaction(
      responseProcessor: (selectionsResponse: ScheduledCardSelectionsResponse) -> Unit
  ) {
    addActionEvent("Prepare Calypso card Selection with AID: ${CalypsoClassicInfo.AID}")
    cardSelectionManager = CalypsoTransaction.initiateScheduledCardSelection(cardReader)
  }

  private fun runCardReadTransactionWithSam(selectionsResponse: ScheduledCardSelectionsResponse) {
    runCardReadTransaction(selectionsResponse, true)
  }

  private fun runCardReadTransactionWithoutSam(
      selectionsResponse: ScheduledCardSelectionsResponse
  ) {
    runCardReadTransaction(selectionsResponse, false)
  }

  private fun runCardReadTransaction(
      selectionsResponse: ScheduledCardSelectionsResponse,
      withSam: Boolean
  ) {

    GlobalScope.launch(Dispatchers.IO) {
      try {
        /*
         * print tag info in View
         */

        addActionEvent("Process selection")
        val selectionsResult =
            cardSelectionManager.parseScheduledCardSelectionsResponse(selectionsResponse)

        val calypsoCard = selectionsResult.activeSmartCard as CalypsoCard

        addResultEvent(
            "Selection successful of card ${ByteArrayUtil.toHex(calypsoCard.applicationSerialNumber)}")

        /*
         * Retrieve the data read from the parser updated during the selection process
         */
        val efEnvironmentHolder =
            calypsoCard.getFileBySfi(CalypsoClassicInfo.SFI_EnvironmentAndHolder)
        addActionEvent("Read environment and holder data")

        addResultEvent(
            "Environment and Holder file: ${
                          ByteArrayUtil.toHex(
                              efEnvironmentHolder.data.content
                          )
                      }")

        val cardTransaction =
            if (withSam) {
              addActionEvent("Create a secured card transaction with SAM")

              // Configure the card resource service to provide an adequate SAM for future secure
              // operations.
              // We suppose here, we use a Identive contact PC/SC reader as card reader.
              val androidPcscPlugin =
                  SmartCardServiceProvider.getService().getPlugin(AndroidPcscPlugin.PLUGIN_NAME)
              setupCardResourceService(
                  androidPcscPlugin,
                  CalypsoClassicInfo.SAM_READER_NAME_REGEX,
                  CalypsoClassicInfo.SAM_PROFILE_NAME)

              /*
               * Create secured card transaction.
               *
               * check the availability of the SAM doing a ATR based selection, open its physical and
               * logical channels and keep it open
               */
              calypsoCardExtensionProvider.createCardTransaction(
                  cardReader, calypsoCard, getSecuritySettings())
            } else {
              // Create unsecured card transaction
              calypsoCardExtensionProvider.createCardTransactionWithoutSecurity(
                  cardReader, calypsoCard)
            }

        /*
         * Prepare the reading order and keep the associated parser for later use once the
         * transaction has been processed.
         */
        cardTransaction.prepareReadRecordFile(
            CalypsoClassicInfo.SFI_EventLog, CalypsoClassicInfo.RECORD_NUMBER_1.toInt())

        cardTransaction.prepareReadRecordFile(
            CalypsoClassicInfo.SFI_Counter1, CalypsoClassicInfo.RECORD_NUMBER_1.toInt())

        /*
         * Actual card communication: send the prepared read order, then close the channel
         * with the card
         */
        addActionEvent("Process card Command for counter and event logs reading")

        if (withSam) {
          addActionEvent("Process card Opening session for transactions")
          cardTransaction.processOpening(WriteAccessLevel.LOAD)
          addResultEvent("Opening session: SUCCESS")
          val counter = readCounter(selectionsResult)
          val eventLog = ByteArrayUtil.toHex(readEventLog(selectionsResult))

          addActionEvent("Process card Closing session")
          cardTransaction.processClosing()
          addResultEvent("Closing session: SUCCESS")

          // In secured reading, value read elements can only be trusted if the session is closed
          // without error.
          addResultEvent("Counter value: $counter")
          addResultEvent("EventLog file: $eventLog")
        } else {
          cardTransaction.processCardCommands()
          addResultEvent("Counter value: ${readCounter(selectionsResult)}")
          addResultEvent(
              "EventLog file: ${
                              ByteArrayUtil.toHex(
                                  readEventLog(
                                      selectionsResult
                                  )
                              )
                          }")
        }

        addResultEvent("End of the Calypso card processing.")
        addResultEvent("You can remove the card now")
      } catch (e: KeyplePluginException) {
        Timber.e(e)
        addResultEvent("Exception: ${e.message}")
      } catch (e: Exception) {
        Timber.e(e)
        addResultEvent("Exception: ${e.message}")
      }
    }
  }

  private fun readCounter(selectionsResult: CardSelectionResult): Int {
    val calypsoCard = selectionsResult.activeSmartCard as CalypsoCard
    val efCounter1 = calypsoCard.getFileBySfi(CalypsoClassicInfo.SFI_Counter1)
    return efCounter1.data.getContentAsCounterValue(CalypsoClassicInfo.RECORD_NUMBER_1.toInt())
  }

  private fun readEventLog(selectionsResult: CardSelectionResult): ByteArray? {
    val calypsoCard = selectionsResult.activeSmartCard as CalypsoCard
    val efCounter1 = calypsoCard.getFileBySfi(CalypsoClassicInfo.SFI_EventLog)
    return efCounter1.data.content
  }

  private fun runCardReadWriteIncreaseTransaction(
      selectionsResponse: ScheduledCardSelectionsResponse
  ) {
    runCardReadWriteTransaction(selectionsResponse, TransactionType.INCREASE)
  }

  private fun runCardReadWriteDecreaseTransaction(
      selectionsResponse: ScheduledCardSelectionsResponse
  ) {
    runCardReadWriteTransaction(selectionsResponse, TransactionType.DECREASE)
  }

  private fun runCardReadWriteTransaction(
      selectionsResponse: ScheduledCardSelectionsResponse,
      transactionType: TransactionType
  ) {
    GlobalScope.launch(Dispatchers.IO) {
      try {
        addActionEvent("1st card exchange: aid selection")
        val selectionsResult =
            cardSelectionManager.parseScheduledCardSelectionsResponse(selectionsResponse)

        if (selectionsResult.activeSelectionIndex != -1) {
          addResultEvent("Calypso card selection: SUCCESS")
          val calypsoCard = selectionsResult.activeSmartCard as CalypsoCard
          addResultEvent("AID: ${ByteArrayUtil.fromHex(CalypsoClassicInfo.AID)}")

          val androidPcscPlugin =
              SmartCardServiceProvider.getService().getPlugin(AndroidPcscPlugin.PLUGIN_NAME)
          setupCardResourceService(
              androidPcscPlugin,
              CalypsoClassicInfo.SAM_READER_NAME_REGEX,
              CalypsoClassicInfo.SAM_PROFILE_NAME)

          addActionEvent("Create secured card transaction with SAM")
          // Create secured card transaction
          val cardTransaction =
              calypsoCardExtensionProvider.createCardTransaction(
                  cardReader, calypsoCard, getSecuritySettings())

          when (transactionType) {
            TransactionType.INCREASE -> {
              /*
               * Open Session for the debit key
               */
              addActionEvent("Process card Opening session for transactions")
              cardTransaction.processOpening(WriteAccessLevel.LOAD)
              addResultEvent("Opening session: SUCCESS")

              cardTransaction.prepareReadRecordFile(
                  CalypsoClassicInfo.SFI_Counter1, CalypsoClassicInfo.RECORD_NUMBER_1.toInt())
              cardTransaction.processCardCommands()

              cardTransaction.prepareIncreaseCounter(
                  CalypsoClassicInfo.SFI_Counter1, CalypsoClassicInfo.RECORD_NUMBER_1.toInt(), 10)
              addActionEvent("Process card increase counter by 10")
              cardTransaction.processClosing()
              addResultEvent("Increase by 10: SUCCESS")
            }
            TransactionType.DECREASE -> {
              /*
               * Open Session for the debit key
               */
              addActionEvent("Process card Opening session for transactions")
              cardTransaction.processOpening(WriteAccessLevel.DEBIT)
              addResultEvent("Opening session: SUCCESS")

              cardTransaction.prepareReadRecordFile(
                  CalypsoClassicInfo.SFI_Counter1, CalypsoClassicInfo.RECORD_NUMBER_1.toInt())
              cardTransaction.processCardCommands()

              /*
               * A ratification command will be sent (CONTACTLESS_MODE).
               */
              cardTransaction.prepareDecreaseCounter(
                  CalypsoClassicInfo.SFI_Counter1, CalypsoClassicInfo.RECORD_NUMBER_1.toInt(), 1)
              addActionEvent("Process card decreasing counter and close transaction")
              cardTransaction.processClosing()
              addResultEvent("Decrease by 1: SUCCESS")
            }
          }

          addResultEvent("End of the Calypso card processing.")
          addResultEvent("You can remove the card now")
        } else {
          addResultEvent(
              "The selection of the card has failed. Should not have occurred due to the MATCHED_ONLY selection mode.")
        }
      } catch (e: KeyplePluginException) {
        Timber.e(e)
        addResultEvent("Exception: ${e.message}")
      } catch (e: Exception) {
        Timber.e(e)
        addResultEvent("Exception: ${e.message}")
      }
    }
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
  ) {
    when (requestCode) {
      PermissionHelper.MY_PERMISSIONS_REQUEST_ALL -> {
        val storagePermissionGranted =
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!storagePermissionGranted) {
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

  override fun onPluginEvent(pluginEvent: PluginEvent?) {
    if (pluginEvent != null) {
      var logMessage =
          "Plugin Event: plugin=${pluginEvent.pluginName}, event=${pluginEvent.type?.name}"
      for (readerName in pluginEvent.readerNames) {
        logMessage += ", reader=$readerName"
      }
      Timber.d(logMessage)
      if (pluginEvent.type == PluginEvent.Type.READER_CONNECTED) {
        onReaderConnected(pluginEvent.readerNames.first())
      }
      if (pluginEvent.type == PluginEvent.Type.READER_DISCONNECTED) {
        addActionEvent("Reader '${pluginEvent.readerNames.first()}' connected.")
      }
      // handle reader disconnection here (PluginEvent.Type.READER_DISCONNECTED)
    }
  }

  private fun onReaderConnected(readerName: String) {

    addActionEvent("Reader '$readerName' connected.")

    // Get and configure the card reader
    cardReader = androidPcscPlugin.getReader(readerName) as ObservableReader
    cardReader.setReaderObservationExceptionHandler { pluginName, readerName, e ->
      Timber.e("An unexpected reader error occurred: $pluginName:$readerName : $e")
    }

    // Set the current activity as Observer of the card reader
    cardReader.addObserver(this@MainActivity)

    cardProtocol = AndroidPcscSupportContactlessProtocols.NFC_ALL
    // Activate protocols for the card reader
    (cardReader as ConfigurableReader).activateProtocol(cardProtocol.key, cardProtocol.key)

    // Get and configure the SAM reader
    //          samReader = androidPcscPlugin.getReader(AndroidPcscReader.READER_NAME)

    /*            PermissionHelper.checkPermission(
        this@MainActivity, arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            AndroidPcscPlugin.PCSCLIKE_SAM_PERMISSION
        )
    )*/

    areReadersInitialized.set(true)

    // Start the NFC detection
    cardReader.startCardDetection(ObservableCardReader.DetectionMode.REPEATING)

    configureCalypsoTransaction(::runCardReadTransactionWithoutSam)
  }

  override fun onDeviceDiscovered(deviceInfoList: MutableCollection<DeviceInfo>) {
    for (bleDeviceInfo in deviceInfoList) {
      Timber.i("Discovered devices: $bleDeviceInfo")
    }
    addActionEvent("Device discovery is finished.\n${deviceInfoList.size} device(s) discovered.")
    for (deviceInfo in deviceInfoList) {
      addActionEvent("Device: " + deviceInfo.textInfo)
    }
    // connect to first discovered device (we should ask the user)
    if (deviceInfoList.isNotEmpty()) {
      val device = deviceInfoList.first()
      androidPcscPlugin
          .getExtension(AndroidPcscPlugin::class.java)
          .connectToDevice(device.identifier)
    }
  }
}