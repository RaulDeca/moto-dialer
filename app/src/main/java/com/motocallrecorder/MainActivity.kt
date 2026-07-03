package com.motocallrecorder

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.motocallrecorder.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var recordingsAdapter: RecordingsAdapter
    private lateinit var callHistoryAdapter: CallHistoryAdapter
    private lateinit var contactsAdapter: ContactsAdapter
    private lateinit var dialerSearchAdapter: ContactsAdapter
    private val recordings = mutableListOf<Recording>()
    private var mediaPlayer: MediaPlayer? = null
    private var currentFilter = -1
    private var allContacts = listOf<ContactEntry>()
    private val dialerNumber = StringBuilder()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val MP_REQUEST_CODE = 103

        private fun getRequiredPermissions(): Array<String> {
            val list = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CONTACTS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return list.toTypedArray()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePref = applicationContext.getSharedPreferences("motocallrecorder_prefs", Context.MODE_PRIVATE)
            .getString("theme_mode", "system") ?: "system"
        when (themePref) {
            "dark" -> setTheme(R.style.Theme_MotoCallRecorder)
            "light" -> setTheme(R.style.Theme_MotoCallRecorder_Light)
            else -> {
                val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (mode == Configuration.UI_MODE_NIGHT_YES) {
                    setTheme(R.style.Theme_MotoCallRecorder)
                } else {
                    setTheme(R.style.Theme_MotoCallRecorder_Light)
                }
            }
        }
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            prefs = Prefs(this)
            setupToolbar()
            setupBottomNav()
            setupCallHistory()
            setupDialer()
            setupContacts()
            setupRecordings()
            checkPermissions()
            switchTab(R.id.nav_dialer)
            if (ProjectionGlobals.data == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestMediaProjection()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "onCreate error", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setTitle("Moto Call Recorder")
        binding.toolbar.setTitleTextColor(0xFFE94560.toInt())
        binding.toolbar.inflateMenu(R.menu.toolbar_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> { showSettingsDialog(); true }
                else -> false
            }
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
    }

    private fun switchTab(tabId: Int) {
        binding.tabRecents.visibility = if (tabId == R.id.nav_recents) View.VISIBLE else View.GONE
        binding.tabDialer.visibility = if (tabId == R.id.nav_dialer) View.VISIBLE else View.GONE
        binding.tabContacts.visibility = if (tabId == R.id.nav_contacts) View.VISIBLE else View.GONE
        binding.tabRecordings.visibility = if (tabId == R.id.nav_recordings) View.VISIBLE else View.GONE

        val title = when (tabId) {
            R.id.nav_recents -> "Recents"
            R.id.nav_dialer -> "Dialer"
            R.id.nav_contacts -> "Contacts"
            R.id.nav_recordings -> "Recordings"
            else -> "Moto Call Recorder"
        }
        binding.toolbar.setTitle(title)

        when (tabId) {
            R.id.nav_recents -> loadCallHistory()
            R.id.nav_contacts -> loadContacts()
            R.id.nav_recordings -> refreshRecordings()
        }
    }

    // ─── SIM DETECTION ──────────────────────────────────────────

    private fun getPhoneAccounts(): List<PhoneAccountHandle> {
        return try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.callCapablePhoneAccounts.toList()
        } catch (_: Exception) { emptyList() }
    }

    private fun getSimLabel(handle: PhoneAccountHandle): String {
        return try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val account = tm.getPhoneAccount(handle)
            if (account != null) account.label.toString() else handle.id ?: "SIM"
        } catch (_: Exception) { handle.id ?: "SIM" }
    }

    private fun updateSimSelector() {
        val accounts = getPhoneAccounts()
        if (accounts.size < 2) {
            binding.simSelector.visibility = View.GONE
            return
        }
        binding.simSelector.visibility = View.VISIBLE
        val simLabels = accounts.mapIndexed { i, h -> "${getSimLabel(h)}" }
        val currentSimIndex = (binding.simSelector.tag as? Int) ?: 0
        binding.simSelector.text = simLabels.getOrElse(currentSimIndex) { "SIM" }
        binding.simSelector.setOnClickListener {
            showSimPicker(accounts) { idx ->
                binding.simSelector.tag = idx
                binding.simSelector.text = simLabels.getOrElse(idx) { "SIM" }
            }
        }
    }

    private var selectedSimIndex = 0
    private var cachedAccounts = listOf<PhoneAccountHandle>()

    private fun showSimPicker(accounts: List<PhoneAccountHandle>, onSelected: (Int) -> Unit) {
        val simLabels = accounts.mapIndexed { i, h -> "${getSimLabel(h)}" }
        AlertDialog.Builder(this)
            .setTitle("Select SIM")
            .setSingleChoiceItems(simLabels.toTypedArray(), selectedSimIndex) { dialog, which ->
                selectedSimIndex = which
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getSelectedAccount(): PhoneAccountHandle? {
        val accounts = cachedAccounts.ifEmpty { getPhoneAccounts() }
        cachedAccounts = accounts
        return accounts.getOrNull(selectedSimIndex)
    }

    // ─── VOICE / VIDEO CALLS ────────────────────────────────────

    private fun placeVoiceCall(number: String) {
        if (number.isEmpty()) { Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            dialNumber(number); return
        }
        val accounts = getPhoneAccounts()
        cachedAccounts = accounts
        if (accounts.size > 1) {
            showSimPicker(accounts) { _ ->
                val account = getSelectedAccount()
                doVoiceCall(number, account)
            }
        } else {
            doVoiceCall(number, accounts.firstOrNull())
        }
    }

    private fun doVoiceCall(number: String, account: PhoneAccountHandle?) {
        try {
            val uri = Uri.parse("tel:$number")
            if (account != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val extras = Bundle()
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                tm.placeCall(uri, extras)
            } else {
                startActivity(Intent(Intent.ACTION_CALL, uri))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot place call", Toast.LENGTH_SHORT).show()
        }
    }

    private fun placeVideoCall(number: String) {
        if (number.isEmpty()) { Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "CALL_PHONE permission required", Toast.LENGTH_SHORT).show(); return
        }
        val accounts = getPhoneAccounts()
        cachedAccounts = accounts
        if (accounts.size > 1) {
            showSimPicker(accounts) { _ ->
                val account = getSelectedAccount()
                doVideoCall(number, account)
            }
        } else {
            doVideoCall(number, accounts.firstOrNull())
        }
    }

    private fun doVideoCall(number: String, account: PhoneAccountHandle?) {
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = tm.callCapablePhoneAccounts.toList()
            android.util.Log.i("VideoCall", "Accounts: ${accounts.size}")
            for (a in accounts) {
                val pa = tm.getPhoneAccount(a)
                android.util.Log.i("VideoCall", "  Account ${a.id}: caps=${pa?.capabilities}")
            }
            val extras = Bundle()
            extras.putInt("android.telecom.extra.START_CALL_WITH_VIDEO", 3)
            if (account != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                android.util.Log.i("VideoCall", "Using account: ${account.id}")
            }
            android.util.Log.i("VideoCall", "Placing call with video=3 ...")
            tm.placeCall(Uri.parse("tel:$number"), extras)
            android.util.Log.i("VideoCall", "placeCall succeeded (call routed)")
        } catch (e: SecurityException) {
            android.util.Log.e("VideoCall", "placeCall SecurityException: ${e.message}")
            android.util.Log.e("VideoCall", "App may need to be default dialer for video calls on Android 14")
            Toast.makeText(this, "Video call requires dialer access, opening dialer...", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            } catch (_: Exception) {}
        } catch (e: Exception) {
            android.util.Log.e("VideoCall", "placeCall failed: ${e.message}")
            android.util.Log.e("VideoCall", "Trying ACTION_CALL intent fallback...")
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                intent.putExtra("android.telecom.extra.START_CALL_WITH_VIDEO", 3)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e2: Exception) {
                android.util.Log.e("VideoCall", "ACTION_CALL also failed: ${e2.message}")
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                } catch (_: Exception) {}
            }
        }
    }

    private fun dialNumber(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot dial", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── CALL HISTORY ────────────────────────────────────────────

    private fun setupCallHistory() {
        callHistoryAdapter = CallHistoryAdapter(
            entries = emptyList(),
            onVoiceCall = { placeVoiceCall(it) },
            onVideoCall = { placeVideoCall(it) }
        )
        binding.callLogList.layoutManager = LinearLayoutManager(this)
        binding.callLogList.adapter = callHistoryAdapter

        binding.filterAll.isSelected = true
        val filterListener = View.OnClickListener { v ->
            binding.filterAll.isSelected = false
            binding.filterMissed.isSelected = false
            binding.filterIncoming.isSelected = false
            binding.filterOutgoing.isSelected = false
            v.isSelected = true
            currentFilter = when (v.id) {
                R.id.filterMissed -> android.provider.CallLog.Calls.MISSED_TYPE
                R.id.filterIncoming -> android.provider.CallLog.Calls.INCOMING_TYPE
                R.id.filterOutgoing -> android.provider.CallLog.Calls.OUTGOING_TYPE
                else -> -1
            }
            loadCallHistory()
        }
        binding.filterAll.setOnClickListener(filterListener)
        binding.filterMissed.setOnClickListener(filterListener)
        binding.filterIncoming.setOnClickListener(filterListener)
        binding.filterOutgoing.setOnClickListener(filterListener)
    }

    private fun loadCallHistory() {
        val entries = CallLogHelper.getCallLog(this, currentFilter)
        callHistoryAdapter.updateData(entries)
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        if (entries.isEmpty()) {
            binding.callLogList.visibility = View.GONE
            binding.tvEmptyCalls.visibility = View.VISIBLE
            binding.tvEmptyCalls.text = if (hasPermission) "No call history found" else "Grant READ_CALL_LOG permission to see call logs"
        } else {
            binding.callLogList.visibility = View.VISIBLE
            binding.tvEmptyCalls.visibility = View.GONE
        }
    }

    // ─── DIALER ──────────────────────────────────────────────────

    private fun setupDialer() {
        val numView = binding.dialerNumber

        dialerSearchAdapter = ContactsAdapter(emptyList(), { placeVoiceCall(it) }, { placeVideoCall(it) })
        binding.dialerSearchList.layoutManager = LinearLayoutManager(this)
        binding.dialerSearchList.adapter = dialerSearchAdapter

        val clickListener = View.OnClickListener { v ->
            val digit = when (v.id) {
                R.id.btn0 -> "0"; R.id.btn1 -> "1"; R.id.btn2 -> "2"
                R.id.btn3 -> "3"; R.id.btn4 -> "4"; R.id.btn5 -> "5"
                R.id.btn6 -> "6"; R.id.btn7 -> "7"; R.id.btn8 -> "8"
                R.id.btn9 -> "9"; R.id.btnStar -> "*"; R.id.btnHash -> "#"
                else -> null
            }
            if (digit != null) {
                dialerNumber.append(digit)
                numView.text = dialerNumber.toString()
                searchByDialerDigits()
            }
        }

        data class DLbl(val digit: String, val letters: String)
        val labels = mapOf(
            R.id.btn1 to DLbl("1", ""), R.id.btn2 to DLbl("2", "ABC"), R.id.btn3 to DLbl("3", "DEF"),
            R.id.btn4 to DLbl("4", "GHI"), R.id.btn5 to DLbl("5", "JKL"), R.id.btn6 to DLbl("6", "MNO"),
            R.id.btn7 to DLbl("7", "PQRS"), R.id.btn8 to DLbl("8", "TUV"), R.id.btn9 to DLbl("9", "WXYZ"),
            R.id.btn0 to DLbl("0", "+"), R.id.btnStar to DLbl("*", ""), R.id.btnHash to DLbl("#", "")
        )
        val btnIds = listOf(R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5,
            R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9, R.id.btnStar, R.id.btnHash)
        for (id in btnIds) {
            val label = labels[id] ?: continue
            val btn = findViewById<Button>(id)
            val ss = SpannableString("${label.digit}\n${label.letters}")
            if (label.letters.isNotEmpty()) {
                ss.setSpan(RelativeSizeSpan(0.45f), label.digit.length + 1, ss.length, 0)
                ss.setSpan(ForegroundColorSpan(0xFF667788.toInt()), label.digit.length + 1, ss.length, 0)
            }
            btn.text = ss
            btn.setOnClickListener(clickListener)
        }

        binding.btnBackspace.setOnClickListener {
            if (dialerNumber.isNotEmpty()) {
                dialerNumber.deleteCharAt(dialerNumber.length - 1)
                numView.text = dialerNumber.toString()
                searchByDialerDigits()
            } else {
                binding.dialerSearchList.visibility = View.GONE
            }
        }

        binding.btnVoiceCall.setOnClickListener {
            val num = dialerNumber.toString().trim()
            if (num.isNotEmpty()) placeVoiceCall(num)
            else Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show()
        }

        binding.btnVideoCall.setOnClickListener {
            val num = dialerNumber.toString().trim()
            if (num.isNotEmpty()) placeVideoCall(num)
            else Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchByDialerDigits() {
        val digits = dialerNumber.toString().trim()
        if (digits.length < 1 || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            binding.dialerSearchList.visibility = View.GONE
            return
        }
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val selectionArgs = arrayOf("%$digits%")
            val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
            val results = mutableListOf<ContactEntry>()
            val seen = mutableSetOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: ""
                    val num = it.getString(1) ?: ""
                    if (num.isNotEmpty() && seen.add(num)) {
                        results.add(ContactEntry(name.ifEmpty { num }, num))
                    }
                }
            }
            // Also T9 search by name
            val nameCursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null
            )
            val t9Digits = digits.map { c ->
                when (c) {
                    '2' -> "[ABC]"; '3' -> "[DEF]"; '4' -> "[GHI]"
                    '5' -> "[JKL]"; '6' -> "[MNO]"; '7' -> "[PQRS]"
                    '8' -> "[TUV]"; '9' -> "[WXYZ]"
                    '0' -> "[0+]"; '1' -> "[1]"; '*' -> "[*]"; '#' -> "[#]"
                    else -> "[$c]"
                }
            }.joinToString("")
            val t9Pattern = Regex(t9Digits, RegexOption.IGNORE_CASE)
            nameCursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: ""
                    val num = it.getString(1) ?: ""
                    if (name.isNotEmpty() && num.isNotEmpty() && seen.add(num)) {
                        val nameUpper = name.replace("[^A-Za-z0-9+]".toRegex(), "")
                        if (t9Pattern.containsMatchIn(nameUpper)) {
                            results.add(ContactEntry(name, num))
                        }
                    }
                }
            }
            results.sortByDescending { it.number.startsWith(digits) }
            val show = results.take(10)
            if (show.isNotEmpty()) {
                dialerSearchAdapter.updateData(show)
                binding.dialerSearchList.visibility = View.VISIBLE
            } else {
                binding.dialerSearchList.visibility = View.GONE
            }
        } catch (e: Exception) {
            binding.dialerSearchList.visibility = View.GONE
        }
    }

    // ─── CONTACTS ────────────────────────────────────────────────

    private fun setupContacts() {
        contactsAdapter = ContactsAdapter(
            contacts = emptyList(),
            onVoiceCall = { placeVoiceCall(it) },
            onVideoCall = { placeVideoCall(it) }
        )
        binding.contactList.layoutManager = LinearLayoutManager(this)
        binding.contactList.adapter = contactsAdapter

        binding.contactSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterContacts(newText ?: "")
                return true
            }
        })
    }

    private fun loadContacts() {
        allContacts = queryContacts("")
        contactsAdapter.updateData(allContacts)
    }

    private fun queryContacts(filter: String): List<ContactEntry> {
        val list = mutableListOf<ContactEntry>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = if (filter.isNotEmpty()) {
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            } else null
            val selectionArgs = if (filter.isNotEmpty()) arrayOf("%$filter%") else null
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

            val cursor: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            val seen = mutableSetOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    val key = "$name|$number"
                    if (seen.add(key)) {
                        list.add(ContactEntry(name, number))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun filterContacts(query: String) {
        val filtered = if (query.isEmpty()) allContacts
        else allContacts.filter { it.name.contains(query, ignoreCase = true) || it.number.contains(query) }
        contactsAdapter.updateData(filtered)
    }

    // ─── RECORDINGS ──────────────────────────────────────────────

    private fun setupRecordings() {
        recordingsAdapter = RecordingsAdapter(
            recordings = recordings,
            onPlay = { playRecording(it) },
            onShare = { shareRecording(it) },
            onDelete = { deleteRecording(it) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = recordingsAdapter
    }

    private fun refreshRecordings() {
        try {
            recordings.clear()
            val files = EnvironmentUtils.getAllRecordingFiles(this)
            var id = 0L
            for (file in files) {
                val parentName = file.parentFile?.name ?: "Unknown"
                val fileName = file.nameWithoutExtension
                val nameParts = fileName.split("_")
                val isIncoming = nameParts.getOrNull(1) == "IN"
                recordings.add(Recording(
                    id = id++, fileName = file.name, filePath = file.absolutePath,
                    duration = 0L, timestamp = file.lastModified(),
                    isIncoming = isIncoming, contactName = parentName, phoneNumber = "-"
                ))
            }
            recordingsAdapter.updateData(recordings)
            updateEmptyState()
        } catch (e: Exception) { android.util.Log.e("MainActivity", "Error loading recordings", e) }
    }

    private fun updateEmptyState() {
        binding.tvEmpty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (recordings.isEmpty()) View.GONE else View.VISIBLE
    }

    // ─── PLAYBACK ────────────────────────────────────────────────

    private fun playRecording(recording: Recording) {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
                Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show(); return
            }
            if (!recording.file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(recording.filePath)
                setOnCompletionListener { this@MainActivity.mediaPlayer?.release(); this@MainActivity.mediaPlayer = null }
                prepare(); start()
            }
        } catch (e: Exception) { Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun shareRecording(recording: Recording) {
        try {
            if (!recording.file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", recording.file)
            startActivity(Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND; type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share recording"))
        } catch (e: Exception) { Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun deleteRecording(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Delete recording with ${recording.contactName}?")
            .setPositiveButton("Delete") { _, _ ->
                if (recording.file.delete()) { refreshRecordings() }
                else Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ─── PERMISSIONS ─────────────────────────────────────────────

    private fun checkPermissions() {
        val needed = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showStoragePermissionDialog()
        }
    }

    private fun showStoragePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage Access Needed")
            .setMessage("Grant 'Files and media' permission to save recordings.")
            .setPositiveButton("Grant") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            .setNegativeButton("Use app storage", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = grantResults.indexOfFirst { it != PackageManager.PERMISSION_GRANTED }
            if (denied >= 0) {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_LONG).show()
            } else {
                if (binding.tabRecents.visibility == View.VISIBLE) loadCallHistory()
                if (binding.tabContacts.visibility == View.VISIBLE) loadContacts()
            }
        }
    }

    // ─── MEDIA PROJECTION ────────────────────────────────────────

    private fun requestMediaProjection() {
        AlertDialog.Builder(this)
            .setTitle("Screen Recording Permission")
            .setMessage("Grant once to enable call recording (both sides). Required for capturing device audio during calls.")
            .setPositiveButton("Grant") { _, _ ->
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), MP_REQUEST_CODE)
            }
            .setNegativeButton("Later", null).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MP_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            ProjectionGlobals.resultCode = resultCode
            ProjectionGlobals.data = data
            Toast.makeText(this, "MediaProjection granted", Toast.LENGTH_LONG).show()
        }
    }

    // ─── SETTINGS ────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "1.0" }
        val mpStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ProjectionGlobals.data != null) "Granted" else "Not granted"
        val accStatus = if (CallRecorderAccessibilityService.isServiceRunning) "Enabled" else "Disabled"
        val permOk = getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val themeLabels = mapOf("system" to "System default", "dark" to "Dark (blue)", "light" to "White & blue")
        val message = buildString {
            appendLine("\u2022 Recording: ${if (prefs.isEnabled) "ON" else "OFF"}")
            appendLine("\u2022 VoIP/Video: ${if (prefs.recordVoip) "ON" else "OFF"}")
            appendLine("\u2022 MediaProjection: $mpStatus")
            appendLine("\u2022 Accessibility: $accStatus")
            appendLine("\u2022 Theme: ${themeLabels[prefs.themeMode] ?: prefs.themeMode}")
            appendLine("\u2022 Permissions: ${if (permOk) "All OK" else "Missing"}")
            appendLine("")
            appendLine("v$version \u2022 Developed by Siva")
        }
        AlertDialog.Builder(this)
            .setTitle("About & Settings")
            .setMessage(message)
            .setPositiveButton("Theme") { _, _ -> showThemePicker() }
            .setNeutralButton("Grant MP") { _, _ ->
                if (ProjectionGlobals.data == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) requestMediaProjection()
                else Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun showThemePicker() {
        val options = arrayOf("System default", "Dark (blue)", "White & blue")
        val values = arrayOf("system", "dark", "light")
        val current = prefs.themeMode
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Select Theme")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val selected = values[which]
                if (selected != current) {
                    prefs.themeMode = selected
                    recreate()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        try { refreshRecordings() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        mediaPlayer?.release(); mediaPlayer = null
        super.onDestroy()
    }
}
