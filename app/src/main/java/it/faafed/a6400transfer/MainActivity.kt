package it.faafed.a6400transfer

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Schermata unica: connessione al Wi-Fi della fotocamera, griglia di miniature,
 * vista ingrandita con zoom, selezione e download nella Galleria (Pictures/Sony A6400).
 */
class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // Tavolozza "Sala buia"
    private val bg = Color.parseColor("#0D1B1E")
    private val surface = Color.parseColor("#17302F")
    private val accent = Color.parseColor("#3DDBB0")
    private val onAccent = Color.parseColor("#06221C")
    private val textMain = Color.parseColor("#E8F3F0")
    private val muted = Color.parseColor("#7DA39D")

    private lateinit var prefs: SharedPreferences
    private lateinit var cm: ConnectivityManager
    private val client = CameraClient()

    private val photos = ArrayList<PhotoItem>()
    private val visible = ArrayList<PhotoItem>()
    private val selected = HashSet<String>()
    private val done: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val ioExec = Executors.newSingleThreadExecutor()
    private val thumbExec = Executors.newFixedThreadPool(2)
    private val fullExec = Executors.newSingleThreadExecutor()
    private val thumbCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024L) / 8L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val fullCache = LruCache<String, Bitmap>(2)

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var downloading = false
    @Volatile private var loading = false
    @Volatile private var downloadingKey: String? = null
    @Volatile private var downloadPct = 0

    private val tabNames = listOf("Tutte", "Nuove", "Scaricate")
    private var tab = 0
    private val tabViews = ArrayList<Pair<TextView, View>>()

    private lateinit var connPanel: LinearLayout
    private lateinit var ssidEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var progress: ProgressLine
    private lateinit var grid: GridView
    private lateinit var pillBar: LinearLayout
    private lateinit var pillCount: TextView
    private lateinit var btnDownload: TextView
    private val photoAdapter = PhotoAdapter()

    // vista ingrandita
    private lateinit var viewer: FrameLayout
    private lateinit var zoom: ZoomImageView
    private lateinit var vTop: View
    private lateinit var vBottom: View
    private lateinit var vTitle: TextView
    private lateinit var vSelect: TextView
    private lateinit var vDownload: TextView
    private lateinit var vHint: TextView
    private var vList: List<PhotoItem> = emptyList()
    private var vIndex = 0
    private var vToken = 0
    private var barsVisible = true

    // ------------------------------------------------------------------ ciclo di vita

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        prefs = getSharedPreferences("a6400", MODE_PRIVATE)
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        done.addAll(prefs.getStringSet("done", emptySet()) ?: emptySet())
        setContentView(buildUi())
        ssidEdit.setText(prefs.getString("ssid", ""))
        passEdit.setText(prefs.getString("pass", ""))
        applyFilter()
    }

    override fun onDestroy() {
        releaseNetwork()
        ioExec.shutdownNow()
        thumbExec.shutdownNow()
        fullExec.shutdownNow()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (viewer.visibility == View.VISIBLE) closeViewer() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ interfaccia

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun ripple(content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    /** Pulsante a pillola: pieno (azione principale) o scuro (secondario). */
    private fun pill(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setTextColor(if (filled) onAccent else textMain)
        t.setPadding(dp(18), dp(11), dp(18), dp(11))
        t.background = ripple(rounded(if (filled) accent else surface, 24))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun input(hint: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = type
        e.setSingleLine()
        e.setTextColor(textMain)
        e.setHintTextColor(muted)
        e.textSize = 15f
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        e.background = rounded(bg, 14)
        return e
    }

    private fun weighted(margin: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.setMargins(margin, 0, margin, 0)
        return lp
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(14), dp(10), dp(14), 0)
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // Titolo, indicatore di connessione, menu e Wi-Fi
        val title = TextView(this)
        title.text = "La tua A6400"
        title.textSize = 22f
        title.setTextColor(textMain)
        title.typeface = Typeface.DEFAULT_BOLD

        statusDot = View(this)
        setConnected(false)

        val btnMenu = pill("⋯", false) {}
        btnMenu.setOnClickListener { showMenu(btnMenu) }
        val btnWifi = pill("Wi-Fi", false) {
            connPanel.visibility = if (connPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(title, LinearLayout.LayoutParams(WRAP, WRAP))
        val dotLp = LinearLayout.LayoutParams(dp(10), dp(10))
        dotLp.setMargins(dp(10), 0, 0, 0)
        titleRow.addView(statusDot, dotLp)
        titleRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        titleRow.addView(btnMenu, LinearLayout.LayoutParams(WRAP, WRAP))
        val wifiLp = LinearLayout.LayoutParams(WRAP, WRAP)
        wifiLp.setMargins(dp(8), 0, 0, 0)
        titleRow.addView(btnWifi, wifiLp)
        content.addView(titleRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Pannello connessione
        connPanel = LinearLayout(this)
        connPanel.orientation = LinearLayout.VERTICAL
        connPanel.setPadding(dp(16), dp(14), dp(16), dp(14))
        connPanel.background = rounded(surface, 20)

        val help = TextView(this)
        help.text = "1. Sulla fotocamera: tasto Riproduzione, poi tasto Invia (o Menu > Rete > " +
            "Invia a smartphone), scegli le foto.\n" +
            "2. Premi il tasto Cestino: compaiono SSID e password del Wi-Fi.\n" +
            "3. Inseriscili qui sotto e tocca Connetti."
        help.textSize = 13f
        help.setTextColor(muted)
        connPanel.addView(help, LinearLayout.LayoutParams(MATCH, WRAP))

        ssidEdit = input("SSID (es. DIRECT-xxxx:ILCE-6400)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val inLp = LinearLayout.LayoutParams(MATCH, WRAP)
        inLp.topMargin = dp(10)
        connPanel.addView(ssidEdit, inLp)

        passEdit = input("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        connPanel.addView(passEdit, LinearLayout.LayoutParams(inLp))

        val connRow = LinearLayout(this)
        connRow.orientation = LinearLayout.HORIZONTAL
        connRow.addView(pill("Connetti", true) { connectWithCredentials() }, weighted(dp(3)))
        connRow.addView(pill("Sono già connesso", false) { connectAlready() }, weighted(dp(3)))
        val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
        rowLp.topMargin = dp(12)
        connPanel.addView(connRow, rowLp)

        val panelLp = LinearLayout.LayoutParams(MATCH, WRAP)
        panelLp.topMargin = dp(12)
        content.addView(connPanel, panelLp)

        // Stato + barra di avanzamento
        status = TextView(this)
        status.text = "Non connesso."
        status.textSize = 13f
        status.setTextColor(muted)
        status.setPadding(dp(2), dp(10), 0, dp(6))
        content.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        progress = ProgressLine(this)
        progress.visibility = View.GONE
        val progLp = LinearLayout.LayoutParams(MATCH, dp(4))
        progLp.bottomMargin = dp(6)
        content.addView(progress, progLp)

        // Schede Tutte / Nuove / Scaricate
        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        for ((i, name) in tabNames.withIndex()) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val tv = TextView(this)
            tv.text = name
            tv.textSize = 15f
            tv.setPadding(0, dp(8), 0, dp(6))
            val line = View(this)
            line.background = rounded(accent, 2)
            col.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP))
            col.addView(line, LinearLayout.LayoutParams(MATCH, dp(2)))
            col.setOnClickListener {
                tab = i
                applyFilter()
            }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(22)
            tabRow.addView(col, lp)
            tabViews.add(Pair(tv, line))
        }
        val tabLp = LinearLayout.LayoutParams(MATCH, WRAP)
        tabLp.bottomMargin = dp(8)
        content.addView(tabRow, tabLp)

        // Griglia miniature
        grid = GridView(this)
        grid.numColumns = 2
        grid.horizontalSpacing = dp(8)
        grid.verticalSpacing = dp(8)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.clipToPadding = false
        grid.setPadding(0, 0, 0, dp(96))
        grid.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        grid.adapter = photoAdapter
        grid.setOnItemClickListener { _, _, position, _ -> openViewer(position) }
        content.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Barra di scarico che galleggia in basso
        pillBar = LinearLayout(this)
        pillBar.orientation = LinearLayout.HORIZONTAL
        pillBar.gravity = Gravity.CENTER_VERTICAL
        pillBar.setPadding(dp(20), dp(8), dp(8), dp(8))
        pillBar.background = rounded(surface, 32)
        pillBar.elevation = dp(10).toFloat()
        pillCount = TextView(this)
        pillCount.textSize = 15f
        pillCount.setTextColor(textMain)
        pillBar.addView(pillCount, LinearLayout.LayoutParams(0, WRAP, 1f))
        btnDownload = pill("Scarica", true) { downloadItems(photos.filter { it.key in selected && it.downloadUrl != null }) }
        pillBar.addView(btnDownload, LinearLayout.LayoutParams(WRAP, WRAP))
        val pillLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        pillLp.setMargins(dp(14), 0, dp(14), dp(14))
        root.addView(pillBar, pillLp)

        buildViewer(root)
        return root
    }

    private fun buildViewer(root: FrameLayout) {
        viewer = FrameLayout(this)
        viewer.setBackgroundColor(Color.BLACK)
        viewer.visibility = View.GONE
        viewer.isClickable = true // non lascia passare i tocchi alla griglia

        zoom = ZoomImageView(this)
        zoom.onTap = { toggleBars() }
        zoom.onSwipe = { d -> showViewerPhoto(vIndex + d) }
        viewer.addView(zoom, FrameLayout.LayoutParams(MATCH, MATCH))

        // Barra in alto: chiudi + posizione e nome
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(8), dp(8), dp(8), dp(20))
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0))
        val close = TextView(this)
        close.text = "✕"
        close.textSize = 20f
        close.gravity = Gravity.CENTER
        close.setTextColor(textMain)
        close.setOnClickListener { closeViewer() }
        top.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        vTitle = TextView(this)
        vTitle.textSize = 14f
        vTitle.setTextColor(textMain)
        vTitle.gravity = Gravity.CENTER
        vTitle.maxLines = 1
        vTitle.ellipsize = TextUtils.TruncateAt.END
        top.addView(vTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(View(this), LinearLayout.LayoutParams(dp(48), dp(48)))
        vTop = top
        viewer.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // Suggerimento / stato di caricamento dell'originale
        vHint = TextView(this)
        vHint.textSize = 12f
        vHint.setTextColor(textMain)
        vHint.setPadding(dp(14), dp(6), dp(14), dp(6))
        vHint.background = rounded(0x99000000.toInt(), 16)
        vHint.visibility = View.GONE
        val hintLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        hintLp.bottomMargin = dp(100)
        viewer.addView(vHint, hintLp)

        // Barra in basso: seleziona + scarica questa
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setPadding(dp(16), dp(24), dp(16), dp(16))
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
        vSelect = pill("Seleziona", false) { vList.getOrNull(vIndex)?.let { toggleSelect(it.key) } }
        bottom.addView(vSelect, LinearLayout.LayoutParams(WRAP, WRAP))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        vDownload = pill("Scarica questa", true) { vList.getOrNull(vIndex)?.let { downloadItems(listOf(it)) } }
        bottom.addView(vDownload, LinearLayout.LayoutParams(WRAP, WRAP))
        vBottom = bottom
        viewer.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        root.addView(viewer, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Seleziona nuove")
        menu.menu.add(0, 2, 1, "Seleziona tutte")
        menu.menu.add(0, 3, 2, "Deseleziona")
        menu.menu.add(0, 4, 3, "Aggiorna elenco")
        menu.menu.add(0, 5, 4, "Fine trasferimento")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    selected.clear()
                    for (p in photos) if (p.key !in done) selected.add(p.key)
                    selectionChanged()
                }
                2 -> {
                    selected.clear()
                    for (p in photos) selected.add(p.key)
                    selectionChanged()
                }
                3 -> {
                    selected.clear()
                    selectionChanged()
                }
                4 -> loadPhotos()
                5 -> endTransfer()
            }
            true
        }
        menu.show()
    }

    private fun setStatus(s: String) {
        runOnUiThread { status.text = s }
    }

    private fun setConnected(on: Boolean) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(if (on) accent else muted)
        statusDot.background = d
    }

    /** Ricalcola l'elenco mostrato in base alla scheda e aggiorna i conteggi. */
    private fun applyFilter() {
        visible.clear()
        for (p in photos) {
            val isDone = p.key in done
            if (tab == 0 || (tab == 1 && !isDone) || (tab == 2 && isDone)) visible.add(p)
        }
        val counts = intArrayOf(
            photos.size,
            photos.count { it.key !in done },
            photos.count { it.key in done }
        )
        for ((i, pair) in tabViews.withIndex()) {
            val on = i == tab
            pair.first.text = "${tabNames[i]} ${counts[i]}"
            pair.first.setTextColor(if (on) accent else muted)
            pair.first.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            pair.second.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        photoAdapter.notifyDataSetChanged()
        updateButtons()
    }

    private fun selectionChanged() {
        photoAdapter.notifyDataSetChanged()
        updateButtons()
    }

    private fun toggleSelect(key: String) {
        if (!selected.add(key)) selected.remove(key)
        selectionChanged()
    }

    private fun updateButtons() {
        val canDownload = selected.isNotEmpty() && !downloading
        btnDownload.isEnabled = canDownload
        btnDownload.alpha = if (canDownload) 1f else 0.4f
        pillCount.text = if (downloading) "Scarico in corso…" else "${selected.size} foto"
        pillBar.visibility = if (selected.isNotEmpty() || downloading) View.VISIBLE else View.GONE
        refreshViewer()
    }

    // ------------------------------------------------------------------ vista ingrandita

    private fun openViewer(position: Int) {
        if (position !in visible.indices) return
        vList = ArrayList(visible)
        barsVisible = true
        vTop.visibility = View.VISIBLE
        vBottom.visibility = View.VISIBLE
        viewer.visibility = View.VISIBLE
        showViewerPhoto(position)
    }

    private fun closeViewer() {
        vToken++
        viewer.visibility = View.GONE
        zoom.clear()
        photoAdapter.notifyDataSetChanged()
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val v = if (barsVisible) View.VISIBLE else View.GONE
        vTop.visibility = v
        vBottom.visibility = v
    }

    private fun showViewerPhoto(index: Int) {
        if (index !in vList.indices) return
        vIndex = index
        val item = vList[index]
        val token = ++vToken
        vTitle.text = "${index + 1} / ${vList.size} · ${item.title}"
        refreshViewer()

        val cachedFull = fullCache.get(item.key)
        if (cachedFull != null) {
            zoom.show(cachedFull, false)
            vHint.visibility = View.GONE
            return
        }
        val thumb = item.thumbUrl?.let { thumbCache.get(it) }
        if (thumb != null) zoom.show(thumb, false) else zoom.clear()

        val url = item.downloadUrl ?: return
        vHint.text = "Carico l'originale…"
        vHint.visibility = View.VISIBLE
        fullExec.execute {
            if (token != vToken) return@execute // ho già cambiato foto: salto
            try {
                val bytes = client.fetchBytes(url)
                if (token != vToken) return@execute
                val bmp = decodeSampled(bytes, 2000) ?: throw CameraException("Immagine non leggibile")
                fullCache.put(item.key, bmp)
                runOnUiThread {
                    if (token == vToken) {
                        zoom.show(bmp, thumb != null)
                        vHint.text = "Pizzica o tocca due volte per ingrandire"
                        vHint.postDelayed({
                            if (token == vToken) vHint.visibility = View.GONE
                        }, 2500)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (token == vToken) vHint.text = "Originale non disponibile"
                }
            }
        }
    }

    /** Allinea i pulsanti della vista ingrandita a selezione e download. */
    private fun refreshViewer() {
        if (!::viewer.isInitialized || viewer.visibility != View.VISIBLE) return
        val item = vList.getOrNull(vIndex) ?: return
        val isSel = item.key in selected
        vSelect.text = if (isSel) "✓ Selezionata" else "Seleziona"
        vSelect.setTextColor(if (isSel) onAccent else textMain)
        vSelect.background = ripple(rounded(if (isSel) accent else surface, 24))

        val isDone = item.key in done
        val busy = downloading && downloadingKey == item.key
        vDownload.text = when {
            busy -> "Scarico $downloadPct%"
            isDone -> "✓ Scaricata"
            else -> "Scarica questa"
        }
        val canDownload = !downloading && !isDone && item.downloadUrl != null
        vDownload.isEnabled = canDownload
        vDownload.alpha = if (canDownload || busy) 1f else 0.5f
    }

    // ------------------------------------------------------------------ rete

    private fun connectWithCredentials() {
        val ssid = ssidEdit.text.toString().trim()
        val pass = passEdit.text.toString().trim()
        if (ssid.isEmpty() || pass.length < 8) {
            setStatus("Inserisci SSID e password della fotocamera (almeno 8 caratteri).")
            return
        }
        prefs.edit().putString("ssid", ssid).putString("pass", pass).apply()
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        requestWifi(req, "Connessione a $ssid... conferma la richiesta che compare sul telefono.")
    }

    private fun connectAlready() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        requestWifi(req, "Cerco il Wi-Fi della fotocamera...")
    }

    private fun requestWifi(req: NetworkRequest, message: String) {
        releaseNetwork()
        setStatus(message)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Senza questo, Android instrada il traffico sulla rete mobile
                // perché il Wi-Fi della fotocamera non ha Internet.
                cm.bindProcessToNetwork(network)
                runOnUiThread { setConnected(true) }
                setStatus("Connesso. Leggo l'elenco delle foto...")
                loadPhotos()
            }

            override fun onUnavailable() {
                setStatus("Connessione non riuscita. Controlla che la fotocamera sia in modalità Invia a smartphone.")
            }

            override fun onLost(network: Network) {
                runOnUiThread { setConnected(false) }
                setStatus("Wi-Fi della fotocamera perso.")
            }
        }
        netCallback = cb
        cm.requestNetwork(req, cb, 30000)
    }

    private fun releaseNetwork() {
        val cb = netCallback
        if (cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        netCallback = null
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ elenco foto

    private fun loadPhotos() {
        if (loading) return
        loading = true
        ioExec.execute {
            try {
                client.transferStart()
                val list = client.listPhotos()
                runOnUiThread {
                    photos.clear()
                    photos.addAll(list)
                    selected.clear()
                    applyFilter()
                    if (list.isEmpty()) {
                        status.text = "Nessuna foto. Sulla fotocamera scegli le foto da inviare e tocca Aggiorna."
                    } else {
                        val already = list.count { it.key in done }
                        status.text = "${list.size} foto ($already già scaricate). Tocca una foto per ingrandirla."
                        connPanel.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                setStatus("Errore: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                loading = false
            }
        }
    }

    private fun endTransfer() {
        ioExec.execute {
            client.transferEnd()
            runOnUiThread {
                releaseNetwork()
                if (viewer.visibility == View.VISIBLE) closeViewer()
                photos.clear()
                selected.clear()
                applyFilter()
                setConnected(false)
                connPanel.visibility = View.VISIBLE
                status.text = "Trasferimento chiuso."
            }
        }
    }

    // ------------------------------------------------------------------ download

    private fun downloadItems(todo: List<PhotoItem>) {
        if (downloading || todo.isEmpty()) return
        downloading = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progress.value = 0
        progress.visibility = View.VISIBLE
        updateButtons()

        ioExec.execute {
            var ok = 0
            var failed = 0
            var lastError = ""
            for ((index, item) in todo.withIndex()) {
                try {
                    downloadingKey = item.key
                    downloadPct = 0
                    setStatus("Scarico ${index + 1}/${todo.size}: ${item.title}")
                    saveToGallery(item) { pct ->
                        downloadPct = pct
                        runOnUiThread {
                            progress.value = pct
                            refreshViewer()
                        }
                    }
                    ok++
                    done.add(item.key)
                    prefs.edit().putStringSet("done", HashSet(done)).apply()
                    runOnUiThread {
                        selected.remove(item.key)
                        applyFilter()
                    }
                } catch (e: Exception) {
                    failed++
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            downloadingKey = null
            runOnUiThread {
                downloading = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progress.visibility = View.GONE
                updateButtons()
                status.text = if (failed == 0) {
                    "Fatto: $ok foto salvate nella Galleria (cartella Sony A6400)."
                } else {
                    "Salvate $ok, errori $failed ($lastError). Riprova con Aggiorna elenco."
                }
            }
        }
    }

    private enum class Kind { IMAGE, VIDEO, OTHER }

    private fun saveToGallery(item: PhotoItem, onProgress: (Int) -> Unit) {
        val url = item.downloadUrl ?: throw CameraException("Indirizzo del file mancante")
        val conn = client.open(url)
        try {
            val headerMime = conn.contentType?.substringBefore(';')?.trim().orEmpty()
            val total = conn.contentLengthLong
            var name = item.title
            val ext = name.substringAfterLast('.', "").lowercase()

            val kind = when {
                headerMime.startsWith("video/") || ext in setOf("mp4", "mov", "mts") -> Kind.VIDEO
                headerMime.startsWith("image/") || ext in setOf("jpg", "jpeg", "heif", "hif", "png") -> Kind.IMAGE
                else -> Kind.OTHER
            }
            val mime = when (kind) {
                Kind.IMAGE -> if (headerMime.startsWith("image/")) headerMime else "image/jpeg"
                Kind.VIDEO -> if (headerMime.startsWith("video/")) headerMime else "video/mp4"
                Kind.OTHER -> if (headerMime.isEmpty()) "application/octet-stream" else headerMime
            }
            // I RAW arrivano come JPEG convertito: correggo l'estensione (es. DSC00001.ARW -> DSC00001.JPG).
            if (mime == "image/jpeg" && ext != "jpg" && ext != "jpeg") {
                name = name.substringBeforeLast('.', name) + ".JPG"
            }

            val collection = when (kind) {
                Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                Kind.OTHER -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val baseDir = when (kind) {
                Kind.IMAGE -> Environment.DIRECTORY_PICTURES
                Kind.VIDEO -> Environment.DIRECTORY_MOVIES
                Kind.OTHER -> Environment.DIRECTORY_DOWNLOADS
            }

            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/Sony A6400")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)

            val resolver = contentResolver
            val uri = resolver.insert(collection, values)
                ?: throw CameraException("Impossibile creare il file nella Galleria")
            try {
                conn.inputStream.use { input ->
                    val out = resolver.openOutputStream(uri)
                        ?: throw CameraException("Impossibile scrivere il file")
                    out.use {
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            it.write(buf, 0, n)
                            written += n
                            if (total > 0) {
                                val pct = (written * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        if (total > 0 && written != total) throw CameraException("Download incompleto")
                    }
                }
                val finished = ContentValues()
                finished.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, finished, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ miniature

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun loadThumb(url: String, cell: Cell) {
        val key = cell.boundKey
        thumbExec.execute {
            if (cell.boundKey != key) return@execute // la cella è stata riusata: salto
            try {
                val bytes = client.fetchBytes(url)
                val bmp = decodeSampled(bytes, 480) ?: return@execute
                thumbCache.put(url, bmp)
                runOnUiThread {
                    if (cell.boundKey == key) cell.img.setImageBitmap(bmp)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Barra di avanzamento sottile con angoli tondi. */
    private inner class ProgressLine(ctx: Context) : View(ctx) {
        var value = 0
            set(v) {
                field = v
                invalidate()
            }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = surface }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = accent }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, trackPaint)
            canvas.drawRoundRect(0f, 0f, width * value / 100f, height.toFloat(), r, r, fillPaint)
        }
    }

    private inner class Cell(ctx: Context) : FrameLayout(ctx) {
        val img = ImageView(ctx)
        val dot = TextView(ctx)
        val hit = FrameLayout(ctx)
        val label = TextView(ctx)
        var boundKey: String? = null
        var boundItem: PhotoItem? = null

        private val ring = GradientDrawable()
        private val dotOn = GradientDrawable()
        private val dotOff = GradientDrawable()

        init {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }

            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setBackgroundColor(surface)
            addView(img, FrameLayout.LayoutParams(MATCH, MATCH))

            label.textSize = 11f
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.setPadding(dp(8), dp(3), dp(8), dp(3))
            label.background = rounded(0x99000000.toInt(), 10)
            val labelLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
            labelLp.setMargins(dp(8), 0, dp(8), dp(8))
            addView(label, labelLp)

            ring.shape = GradientDrawable.RECTANGLE
            ring.cornerRadius = dp(16).toFloat()
            ring.setStroke(dp(3), accent)
            ring.setColor(Color.TRANSPARENT)

            dotOn.shape = GradientDrawable.OVAL
            dotOn.setColor(accent)
            dotOff.shape = GradientDrawable.OVAL
            dotOff.setColor(0x66000000)
            dotOff.setStroke(dp(2), textMain)

            dot.gravity = Gravity.CENTER
            dot.textSize = 13f
            dot.typeface = Typeface.DEFAULT_BOLD
            dot.setTextColor(onAccent)
            hit.addView(dot, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            hit.setOnClickListener { boundItem?.let { toggleSelect(it.key) } }
            addView(hit, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 5 / 4, MeasureSpec.EXACTLY))
        }

        fun bind(item: PhotoItem, isSelected: Boolean, isDone: Boolean) {
            boundKey = item.key
            boundItem = item
            label.text = (if (isDone) "✓ " else "") + item.title
            label.setTextColor(if (isDone) accent else textMain)
            dot.background = if (isSelected) dotOn else dotOff
            dot.text = if (isSelected) "✓" else ""
            foreground = if (isSelected) ring else null
            val url = item.thumbUrl
            val cached = if (url != null) thumbCache.get(url) else null
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageBitmap(null)
                if (url != null) loadThumb(url, this)
            }
        }
    }

    private inner class PhotoAdapter : BaseAdapter() {
        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? Cell) ?: Cell(this@MainActivity)
            val item = visible[position]
            cell.bind(item, item.key in selected, item.key in done)
            return cell
        }
    }
}
