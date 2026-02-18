package com.example.ft_file_manager

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelSftp

class NetworkClientActivity : AppCompatActivity() {

    private val scanResults = mutableListOf<String>()
    private lateinit var scanAdapter: ScanAdapter // Ο νέος μας adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_client)

        // Αρχικοποίηση RecyclerView
        val rvScanResults = findViewById<RecyclerView>(R.id.rvScanResults)
        rvScanResults.layoutManager = LinearLayoutManager(this)
        scanAdapter = ScanAdapter(scanResults) { selectedIp ->
            // Όταν κάνεις κλικ σε μια IP από τη λίστα, να μπαίνει στο πεδίο Host
            findViewById<EditText>(R.id.etHost).setText(selectedIp)
        }
        rvScanResults.adapter = scanAdapter

        val btnScan = findViewById<Button>(R.id.btnScan)
        val progressBar = findViewById<ProgressBar>(R.id.scanProgress)

        val prefs = getSharedPreferences("network_prefs", Context.MODE_PRIVATE)
        findViewById<EditText>(R.id.etHost).setText(prefs.getString("last_host", ""))
        findViewById<EditText>(R.id.etUser).setText(prefs.getString("last_user", ""))
// ... και ούτω καθεξής

        btnScan.setOnClickListener {
            startNetworkScan(progressBar)
        }

        val etPort = findViewById<EditText>(R.id.etPort)
        val btnConnect = findViewById<Button>(R.id.btnConnect)

        findViewById<RadioButton>(R.id.rbSmb).setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                btnConnect.text = "Σύνδεση (SMB)"
                etPort.setText("445") // Αυτόματη αλλαγή σε SMB port
            }
        }

        findViewById<RadioButton>(R.id.rbSftp).setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                btnConnect.text = "Σύνδεση (SFTP)"
                etPort.setText("22") // Αυτόματη αλλαγή σε SFTP port
            }
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val host = findViewById<EditText>(R.id.etHost).text.toString()
            val user = findViewById<EditText>(R.id.etUser).text.toString()
            val pass = findViewById<EditText>(R.id.etPass).text.toString()
            val portString = findViewById<EditText>(R.id.etPort).text.toString()

            val isSmb = findViewById<RadioButton>(R.id.rbSmb).isChecked

            if (host.isNotEmpty()) {
                // Αν το πεδίο port είναι άδειο, παίρνει 445 για SMB ή 22 για SFTP
                val port = if (portString.isNotEmpty()) portString.toInt() else (if (isSmb) 445 else 22)

                if (isSmb) {
                    connectToSMB(host, user, pass, port) // Προστέθηκε το port
                } else {
                    connectToSFTP(host, user, pass, port) // Προστέθηκε το port
                }
            } else {
                Toast.makeText(this, "Παρακαλώ εισάγετε IP", Toast.LENGTH_SHORT).show()
            }
        }

        // ΕΛΕΓΧΟΣ ΓΙΑ FAVORITE
        val favoriteData = intent.getStringExtra("FAVORITE_SMB_DATA")
        if (!favoriteData.isNullOrEmpty()) {
            loadFavoriteIntoFields(favoriteData)
        }
    }

    private fun startNetworkScan(progressBar: ProgressBar) {
        progressBar.visibility = View.VISIBLE
        scanResults.clear()
        scanAdapter.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.IO) {
            val prefix = getNetworkPrefix()
            // Σάρωση δικτύου
            for (i in 1..254) {
                val testIp = "$prefix.$i"
                if (isIpReachable(testIp)) {
                    withContext(Dispatchers.Main) {
                        scanResults.add(testIp)
                        scanAdapter.notifyItemInserted(scanResults.size - 1)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                // Μετά το Ping Scan, ξεκινάμε και το NSD (Bonjour) για CoreELEC
                discoverServices()
            }
        }
    }

    private fun getNetworkPrefix(): String {
        // TODO: Κανονικά εδώ παίρνουμε την IP του κινητού και αφαιρούμε το τελευταίο ψηφίο
        return "192.168.1"
    }

    private fun isIpReachable(ip: String): Boolean {
        return try {
            val address = InetAddress.getByName(ip)
            address.isReachable(300) // 300ms timeout
        } catch (e: Exception) {
            false
        }
    }

    private fun testSMBConnection(host: String, user: String, pass: String) {
        // Η υλοποίηση του SMBj θα μπει εδώ
        Toast.makeText(this, "Προσπάθεια σύνδεσης στο $host", Toast.LENGTH_SHORT).show()
    }

    private fun discoverServices() {
        val nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_smb") || service.serviceName.contains("CoreELEC")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            val host = resolvedInfo.host.hostAddress
                            runOnUiThread {
                                val entry = "${resolvedInfo.serviceName} ($host)"
                                if (!scanResults.contains(entry)) {
                                    scanResults.add(entry)
                                    scanAdapter.notifyDataSetChanged()
                                }
                            }
                        }
                        override fun onResolveFailed(p0: NsdServiceInfo?, p1: Int) {}
                    })
                }
            }
            override fun onDiscoveryStarted(p0: String?) {}
            override fun onDiscoveryStopped(p0: String?) {}
            override fun onServiceLost(p0: NsdServiceInfo?) {}
            override fun onStartDiscoveryFailed(p0: String?, p1: Int) {}
            override fun onStopDiscoveryFailed(p0: String?, p1: Int) {}
        }
        nsdManager.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun connectToSFTP(host: String, user: String, pass: String, port: Int) { // Προσθήκη port εδώ
        lifecycleScope.launch(Dispatchers.IO) {
            val jsch = JSch()
            try {
                val session = jsch.getSession(user, host, port) // Χρήση της θύρας εδώ
                session.setPassword(pass)

                val config = java.util.Properties()
                config["StrictHostKeyChecking"] = "no"
                session.setConfig(config)

                session.connect(10000)
                val channel = session.openChannel("sftp") as ChannelSftp
                channel.connect()

                val files = channel.ls(".")

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NetworkClientActivity, "SFTP OK! Βρέθηκαν ${files.size} αρχεία", Toast.LENGTH_SHORT).show()
                }
                channel.disconnect()
                session.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NetworkClientActivity, "SFTP Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun connectToSMB(host: String, user: String, pass: String, port: Int) {
        val cleanHost = host.trim()

        lifecycleScope.launch(Dispatchers.IO) {
            val client = SMBClient()
            try {
                client.connect(cleanHost, port).use { connection ->
                    val auth = AuthenticationContext(user, pass.toCharArray(), "")
                    val session = connection.authenticate(auth)

                    val commonShares = listOf("Storage", "Videos", "Music", "Update")
                    var connectedShare: DiskShare? = null
                    var foundName = ""

                    for (name in commonShares) {
                        try {
                            val share = session.connectShare(name) as DiskShare
                            connectedShare = share
                            foundName = name
                            break
                        } catch (e: Exception) {
                            continue
                        }
                    }

                    if (connectedShare != null) {
                        // --- ΕΔΩ ΕΙΝΑΙ Η ΠΡΟΣΘΗΚΗ ΓΙΑ ΤΗΝ ΑΠΟΘΗΚΕΥΣΗ ---
                        val netPrefs = getSharedPreferences("network_settings", MODE_PRIVATE)
                        netPrefs.edit().apply {
                            putString("user_smb://$cleanHost", user)
                            putString("pass_smb://$cleanHost", pass)
                            apply()
                        }
                        // ----------------------------------------------

                        withContext(Dispatchers.Main) {
                            val intent = Intent(this@NetworkClientActivity, NetworkFileActivity::class.java).apply {
                                putExtra("HOST", cleanHost)
                                putExtra("USER", user)
                                putExtra("PASS", pass)
                                putExtra("SHARE", foundName)
                                putExtra("PORT", port)
                            }
                            startActivity(intent)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@NetworkClientActivity,
                                "STATUS_BAD_NETWORK_NAME: Δεν βρέθηκε ο φάκελος Storage ή Videos",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NetworkClientActivity, "Σφάλμα: ${e.message}", Toast.LENGTH_LONG).show()
                    val progressBar = findViewById<ProgressBar>(R.id.scanProgress)
                    startNetworkScan(progressBar)
                }
            }
        }
    }

    private fun loadFavoriteIntoFields(fav: String) {
        try {
            // fav μπορεί να είναι: "SMB: Host*smb://user:pass@192.168.1.5"
            // ή: "SMB: 192.168.1.5*smb://192.168.1.5"
            val fullUri = fav.substringAfter("*").trim()

            var host = ""
            var user = ""
            var pass = ""

            if (fullUri.contains("@")) {
                // ΠΕΡΙΠΤΩΣΗ 1: Το format έχει κωδικούς (user:pass@host)
                val uriCore = fullUri.removePrefix("smb://")
                val userPass = uriCore.substringBefore("@")
                host = uriCore.substringAfter("@")
                user = userPass.substringBefore(":")
                pass = userPass.substringAfter(":")
            } else {
                // ΠΕΡΙΠΤΩΣΗ 2: Το format είναι απλό (smb://host) - Ψάχνουμε στα Prefs
                host = fullUri.removePrefix("smb://")
                val netPrefs = getSharedPreferences("network_settings", MODE_PRIVATE)
                user = netPrefs.getString("user_smb://$host", "") ?: ""
                pass = netPrefs.getString("pass_smb://$host", "") ?: ""
            }

            // Τοποθέτηση στα πεδία
            findViewById<EditText>(R.id.etHost).setText(host)
            findViewById<EditText>(R.id.etUser).setText(user)
            findViewById<EditText>(R.id.etPass).setText(pass)
            findViewById<RadioButton>(R.id.rbSmb).isChecked = true
            findViewById<EditText>(R.id.etPort).setText("445")

            // Αν έχουμε host ΚΑΙ user, δοκιμάζουμε αυτόματη σύνδεση
            if (host.isNotEmpty() && user.isNotEmpty()) {
                Toast.makeText(this, "Αυτόματη σύνδεση στο $host", Toast.LENGTH_SHORT).show()
                connectToSMB(host, user, pass, 445)
            }
        } catch (e: Exception) {
            Log.e("FAV_ERROR", "Error parsing favorite", e)
        }
    }

    inner class ScanAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<ScanAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val text: TextView = v.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item
            holder.text.setTextColor(android.graphics.Color.WHITE)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}