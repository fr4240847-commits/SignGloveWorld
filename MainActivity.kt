package com.signglove.world

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val sppUuid: UUID = UUID.fromString(
        "00001101-0000-1000-8000-00805F9B34FB"
    )

    private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var keepReading = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var status: TextView
    private lateinit var sensorText: TextView
    private lateinit var resultText: TextView
    private lateinit var signName: EditText
    private lateinit var languageSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var speakButton: Button

    private val languageNames = listOf(
        "العربية", "English", "Français", "Español", "Deutsch", "Italiano"
    )
    private val languageCodes = listOf("ar", "en", "fr", "es", "de", "it")

    private var latestValues: FloatArray? = null
    private val samples = mutableListOf<TrainingSample>()

    data class TrainingSample(val label: String, val values: FloatArray)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        loadSamples()
        buildScreen()
        requestBluetoothPermissionIfNeeded()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
        }
        scroll.addView(content)
        root.addView(scroll)

        val heading = TextView(this).apply {
            text = "SignGlove World"
            textSize = 26f
            setPadding(0, 0, 0, 8)
        }
        content.addView(heading)

        content.addView(TextView(this).apply {
            text = "ترجمة تجريبية للإشارات باستخدام القفاز الذكي"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        })

        status = TextView(this).apply {
            text = "الحالة: غير متصل"
            textSize = 16f
            setPadding(0, 8, 0, 12)
        }
        content.addView(status)

        connectButton = Button(this).apply {
            text = "اختيار جهاز Bluetooth والاتصال"
            setOnClickListener { showPairedDevices() }
        }
        content.addView(connectButton)

        sensorText = TextView(this).apply {
            text = "قراءات الحساسات ستظهر هنا"
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        content.addView(sensorText)

        content.addView(TextView(this).apply {
            text = "الإشارة المتعرّف عليها / الترجمة"
            textSize = 18f
        })

        resultText = TextView(this).apply {
            text = "لم يتم التعرف على إشارة بعد"
            textSize = 20f
            setPadding(0, 8, 0, 12)
        }
        content.addView(resultText)

        languageSpinner = Spinner(this)
        languageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languageNames
        )
        content.addView(languageSpinner)

        speakButton = Button(this).apply {
            text = "نطق الترجمة"
            setOnClickListener { speakCurrentTranslation() }
        }
        content.addView(speakButton)

        content.addView(TextView(this).apply {
            text = "إضافة عينة تدريب"
            textSize = 18f
            setPadding(0, 24, 0, 8)
        })

        signName = EditText(this).apply {
            hint = "اسم الإشارة (مثال: hello)"
            singleLine = true
        }
        content.addView(signName)

        content.addView(Button(this).apply {
            text = "حفظ قراءة الحساسات كعينة"
            setOnClickListener { saveCurrentSample() }
        })

        content.addView(Button(this).apply {
            text = "حذف عينات التدريب"
            setOnClickListener { confirmClearSamples() }
        })

        content.addView(Button(this).apply {
            text = "فصل Bluetooth"
            setOnClickListener { disconnectBluetooth() }
        })

        content.addView(TextView(this).apply {
            text = "العينات المحفوظة: ${samples.size}\n\nملاحظة: هذا إصدار تجريبي يحتاج عينات حقيقية واختبارات لتحسين الدقة."
            textSize = 13f
            setPadding(0, 16, 0, 8)
        })

        setContentView(root)
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 701)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 701) {
            status.text = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                "الحالة: إذن Bluetooth متاح. اقترني بلوحة ESP32 من إعدادات الهاتف أولًا."
            } else {
                "يلزم السماح بإذن Bluetooth لاستخدام القفاز."
            }
        }
    }

    private fun showPairedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissionIfNeeded()
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            status.text = "هذا الهاتف لا يدعم Bluetooth."
            return
        }
        if (!adapter.isEnabled) {
            status.text = "شغّلي Bluetooth من إعدادات الهاتف."
            return
        }

        val devices = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

        if (devices.isEmpty()) {
            status.text = "لا توجد أجهزة مقترنة. اقترني بـ ESP32 من إعدادات Bluetooth أولًا."
            return
        }

        val labels = devices.map { device ->
            "${device.name ?: "جهاز بدون اسم"}\n${device.address}"
        }

        AlertDialog.Builder(this)
            .setTitle("اختاري لوحة القفاز")
            .setItems(labels.toTypedArray()) { _, which ->
                connectToDevice(devices[which])
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        status.text = "جارٍ الاتصال بـ ${device.name ?: "ESP32"}..."
        thread {
            try {
                disconnectBluetooth()
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothSocket = socket
                socket.connect()
                keepReading = true
                runOnUiThread { status.text = "متصل بالقفاز" }
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (keepReading) {
                    val line = reader.readLine() ?: break
                    processIncomingLine(line)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "تعذر الاتصال: ${e.message ?: "تحققي من الاقتران ونوع Bluetooth"}"
                }
            } finally {
                keepReading = false
                try { bluetoothSocket?.close() } catch (_: Exception) {}
                bluetoothSocket = null
                runOnUiThread {
                    if (!isFinishing) status.text = "غير متصل"
                }
            }
        }
    }

    private fun processIncomingLine(line: String) {
        val values = line.trim().split(",").mapNotNull { it.toFloatOrNull() }
        if (values.size != 9) {
            runOnUiThread { sensorText.text = "بيانات غير مفهومة: $line" }
            return
        }

        val array = values.toFloatArray()
        latestValues = array

        val recognized = recognize(array)

        runOnUiThread {
            val code = languageCodes.getOrElse(languageSpinner.selectedItemPosition) { "ar" }
            val translated = recognized?.let { translate(it, code) }

            sensorText.text = "Flex: ${values.take(3)}\nAccel: ${values.subList(3, 6)}\nGyro: ${values.subList(6, 9)}"
            resultText.text = if (recognized == null) {
                "لم يتم التعرف على الإشارة.\nأضيفي عينات تدريب أولًا."
            } else {
                "الإشارة: $recognized\nالترجمة: $translated"
            }
        }
        }
    }

    private fun saveCurrentSample() {
        val label = signName.text.toString().trim()
        val values = latestValues
        if (label.isBlank()) {
            Toast.makeText(this, "اكتبي اسم الإشارة أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        if (values == null) {
            Toast.makeText(this, "اتصلي بالقفاز وانتظري قراءة الحساسات", Toast.LENGTH_SHORT).show()
            return
        }
        samples.add(TrainingSample(label.lowercase(), values.copyOf()))
        saveSamples()
        Toast.makeText(this, "تم حفظ العينة. إجمالي العينات: ${samples.size}", Toast.LENGTH_SHORT).show()
    }

    private fun recognize(values: FloatArray): String? {
        if (samples.isEmpty()) return null
        val nearest = samples.minByOrNull { sample ->
            sample.values.indices.sumOf { i ->
                abs(sample.values[i] - values[i]).toDouble()
            }
        } ?: return null
        return nearest.label
    }

    private fun translate(word: String, language: String): String {
        val dictionary = mapOf(
            "hello" to mapOf("ar" to "مرحبًا", "en" to "Hello", "fr" to "Bonjour", "es" to "Hola", "de" to "Hallo", "it" to "Ciao"),
            "thank you" to mapOf("ar" to "شكرًا", "en" to "Thank you", "fr" to "Merci", "es" to "Gracias", "de" to "Danke", "it" to "Grazie"),
            "good morning" to mapOf("ar" to "صباح الخير", "en" to "Good morning", "fr" to "Bonjour", "es" to "Buenos días", "de" to "Guten Morgen", "it" to "Buongiorno"),
            "goodbye" to mapOf("ar" to "مع السلامة", "en" to "Goodbye", "fr" to "Au revoir", "es" to "Adiós", "de" to "Auf Wiedersehen", "it" to "Arrivederci")
        )
        return dictionary[word.lowercase()]?.get(language)
            ?: if (language == "en") word else "لا توجد ترجمة محلية لهذه الكلمة"
    }

    private fun speakCurrentTranslation() {
        val text = resultText.text.toString()
            .substringAfter("الترجمة:", "")
            .trim()
        if (text.isBlank()) {
            Toast.makeText(this, "لا يوجد نص لترجمته صوتيًا", Toast.LENGTH_SHORT).show()
            return
        }
        val code = languageCodes.getOrElse(languageSpinner.selectedItemPosition) { "ar" }
        val locale = when (code) {
            "ar" -> Locale("ar")
            "fr" -> Locale.FRANCE
            "es" -> Locale("es")
            "de" -> Locale.GERMANY
            "it" -> Locale.ITALY
            else -> Locale.US
        }
        val availability = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (ttsReady && availability >= TextToSpeech.LANG_AVAILABLE) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SignGloveSpeech")
        } else {
            Toast.makeText(this, "الصوت لهذه اللغة غير متاح على الهاتف", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSamples() {
        val prefs = getSharedPreferences("sign_samples", MODE_PRIVATE)
        val serialized = samples.joinToString("\n") { sample ->
            sample.label + "|" + sample.values.joinToString(",")
        }
        prefs.edit().putString("data", serialized).apply()
    }

    private fun loadSamples() {
        val prefs = getSharedPreferences("sign_samples", MODE_PRIVATE)
        val serialized = prefs.getString("data", "").orEmpty()
        serialized.lineSequence().forEach { row ->
            val parts = row.split("|")
            if (parts.size == 2) {
                val values = parts[1].split(",").mapNotNull { it.toFloatOrNull() }
                if (values.size == 9) samples.add(TrainingSample(parts[0], values.toFloatArray()))
            }
        }
    }

    private fun confirmClearSamples() {
        AlertDialog.Builder(this)
            .setTitle("حذف عينات التدريب؟")
            .setMessage("سيتم حذف جميع العينات المحفوظة على الهاتف.")
            .setPositiveButton("حذف") { _, _ ->
                samples.clear()
                saveSamples()
                Toast.makeText(this, "تم حذف العينات", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun disconnectBluetooth() {
        keepReading = false
        try { bluetoothSocket?.close() } catch (_: Exception) {}
        bluetoothSocket = null
    }

    override fun onInit(statusCode: Int) {
        ttsReady = statusCode == TextToSpeech.SUCCESS
    }

    override fun onDestroy() {
        disconnectBluetooth()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
