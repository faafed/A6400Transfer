package it.faafed.a6400transfer

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Schermata unica: connessione al Wi-Fi della fotocamera, griglia di miniature,
 * selezione e download nella Galleria (Pictures/Sony A6400).
 */
class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val blue = Color.parseColor("#1565C0")

    private lateinit var prefs: SharedPreferences
    private lateinit var cm: ConnectivityManager
    private val client = CameraClient()

    private val photos = ArrayList<PhotoItem>()
    private val selected = HashSet<String>()
    private val done: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val ioExec = Executors.newSingleThreadExecutor()
    private val thumbExec = Executors.newFixedThreadPool(2)
    private val thumbCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024L) / 8L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var downloading = false
    @Volatile private var loading = false

    private lateinit var connPanel: LinearLayout
    private lateinit var ssidEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var grid: GridView
    private lateinit var btnDownload: Button
    private val photoAdapter = PhotoAdapter()

    // ------------------------------------------------------------------ ciclo di vita

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("a6400", MODE_PRIVATE)
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        done.addAll(prefs.getStringSet("done", emptySet()) ?: emptySet())
        setContentView(buildUi())
        ssidEdit.setText(prefs.getString("ssid", ""))
        passEdit.setText(prefs.getString("pass", ""))
        updateButtons()
    }

    override fun onDestroy() {
        releaseNetwork()
        ioExec.shutdownNow()
        thumbExec.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interfaccia

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
        return r
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(12), dp(12), dp(8))
        root.setBackgroundColor(Color.WHITE)

        // Titolo + pulsante per mostrare/nascondere il pannello di connessione
        val title = TextView(this)
        title.text = "A6400 Transfer"
        title.textSize = 22f
        title.setTextColor(blue)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        val btnWifi = makeButton("Wi-Fi") {
            connPanel.visibility = if (connPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleRow.addView(btnWifi, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(titleRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Pannello connessione
        connPanel = LinearLayout(this)
        connPanel.orientation = LinearLayout.VERTICAL

        val help = TextView(this)
        help.text = "1. Sulla fotocamera: tasto Riproduzione, poi tasto Invia (o Menu > Rete > " +
            "Invia a smartphone), scegli le foto.\n" +
            "2. Premi il tasto Cestino: compaiono SSID e password del Wi-Fi.\n" +
            "3. Inseriscili qui sotto e tocca Connetti."
        help.textSize = 13f
        help.setTextColor(Color.DKGRAY)
        connPanel.addView(help, LinearLayout.LayoutParams(MATCH, WRAP))

        ssidEdit = EditText(this)
        ssidEdit.hint = "SSID (es. DIRECT-xxxx:ILCE-6400)"
        ssidEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        ssidEdit.setSingleLine()
        connPanel.addView(ssidEdit, LinearLayout.LayoutParams(MATCH, WRAP))

        passEdit = EditText(this)
        passEdit.hint = "Password"
        passEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        passEdit.setSingleLine()
        connPanel.addView(passEdit, LinearLayout.LayoutParams(MATCH, WRAP))

        connPanel.addView(
            row(
                makeButton("Connetti") { connectWithCredentials() },
                makeButton("Sono già connesso") { connectAlready() }
            ),
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        root.addView(connPanel, LinearLayout.LayoutParams(MATCH, WRAP))

        // Stato + barra di avanzamento
        status = TextView(this)
        status.text = "Non connesso."
        status.textSize = 13f
        status.setTextColor(Color.DKGRAY)
        status.setPadding(0, dp(6), 0, dp(2))
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        root.addView(progress, LinearLayout.LayoutParams(MATCH, WRAP))

        // Griglia miniature
        grid = GridView(this)
        grid.numColumns = 3
        grid.horizontalSpacing = dp(4)
        grid.verticalSpacing = dp(4)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.adapter = photoAdapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val key = photos[position].key
            if (!selected.add(key)) selected.remove(key)
            photoAdapter.notifyDataSetChanged()
            updateButtons()
        }
        root.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Barra inferiore
        btnDownload = makeButton("Scarica (0)") { downloadSelected() }
        root.addView(
            row(
                makeButton("Nuove") {
                    selected.clear()
                    for (p in photos) if (p.key !in done) selected.add(p.key)
                    photoAdapter.notifyDataSetChanged()
                    updateButtons()
                },
                makeButton("Tutte") {
                    selected.clear()
                    for (p in photos) selected.add(p.key)
                    photoAdapter.notifyDataSetChanged()
                    updateButtons()
                },
                makeButton("Nessuna") {
                    selected.clear()
                    photoAdapter.notifyDataSetChanged()
                    updateButtons()
                }
            ),
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        root.addView(
            row(
                makeButton("Aggiorna") { loadPhotos() },
                btnDownload,
                makeButton("Fine") { endTransfer() }
            ),
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        return root
    }

    private fun setStatus(s: String) {
        runOnUiThread { status.text = s }
    }

    private fun updateButtons() {
        btnDownload.text = "Scarica (${selected.size})"
        btnDownload.isEnabled = selected.isNotEmpty() && !downloading
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
                setStatus("Connesso. Leggo l'elenco delle foto...")
                loadPhotos()
            }

            override fun onUnavailable() {
                setStatus("Connessione non riuscita. Controlla che la fotocamera sia in modalità Invia a smartphone.")
            }

            override fun onLost(network: Network) {
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
                    photoAdapter.notifyDataSetChanged()
                    updateButtons()
                    if (list.isEmpty()) {
                        status.text = "Nessuna foto. Sulla fotocamera scegli le foto da inviare e tocca Aggiorna."
                    } else {
                        val already = list.count { it.key in done }
                        status.text = "${list.size} foto ($already già scaricate). Tocca per selezionare."
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
                photos.clear()
                selected.clear()
                photoAdapter.notifyDataSetChanged()
                updateButtons()
                connPanel.visibility = View.VISIBLE
                status.text = "Trasferimento chiuso."
            }
        }
    }

    // ------------------------------------------------------------------ download

    private fun downloadSelected() {
        if (downloading) return
        val todo = photos.filter { it.key in selected && it.downloadUrl != null }
        if (todo.isEmpty()) return
        downloading = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progress.progress = 0
        progress.visibility = View.VISIBLE
        updateButtons()

        ioExec.execute {
            var ok = 0
            var failed = 0
            var lastError = ""
            for ((index, item) in todo.withIndex()) {
                try {
                    setStatus("Scarico ${index + 1}/${todo.size}: ${item.title}")
                    saveToGallery(item) { pct -> runOnUiThread { progress.progress = pct } }
                    ok++
                    done.add(item.key)
                    prefs.edit().putStringSet("done", HashSet(done)).apply()
                    runOnUiThread {
                        selected.remove(item.key)
                        photoAdapter.notifyDataSetChanged()
                        updateButtons()
                    }
                } catch (e: Exception) {
                    failed++
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            runOnUiThread {
                downloading = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progress.visibility = View.GONE
                updateButtons()
                status.text = if (failed == 0) {
                    "Fatto: $ok foto salvate nella Galleria (cartella Sony A6400)."
                } else {
                    "Salvate $ok, errori $failed ($lastError). Riprova con Aggiorna."
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
                val bmp = decodeSampled(bytes, 320) ?: return@execute
                thumbCache.put(url, bmp)
                runOnUiThread {
                    if (cell.boundKey == key) cell.img.setImageBitmap(bmp)
                }
            } catch (_: Exception) {
            }
        }
    }

    private inner class Cell(ctx: Context) : FrameLayout(ctx) {
        val img = ImageView(ctx)
        val check = TextView(ctx)
        val label = TextView(ctx)
        var boundKey: String? = null

        init {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setBackgroundColor(Color.parseColor("#DDDDDD"))
            addView(img, FrameLayout.LayoutParams(MATCH, MATCH))

            label.textSize = 10f
            label.setTextColor(Color.WHITE)
            label.setBackgroundColor(Color.parseColor("#99000000"))
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.setPadding(dp(3), dp(2), dp(3), dp(2))
            addView(label, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

            val circle = GradientDrawable()
            circle.shape = GradientDrawable.OVAL
            circle.setColor(blue)
            check.background = circle
            check.text = "✓"
            check.setTextColor(Color.WHITE)
            check.gravity = Gravity.CENTER
            check.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            addView(check, lp)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec) // cella quadrata
        }

        fun bind(item: PhotoItem, isSelected: Boolean, isDone: Boolean) {
            boundKey = item.key
            label.text = (if (isDone) "✓ " else "") + item.title
            check.visibility = if (isSelected) View.VISIBLE else View.GONE
            img.alpha = if (isSelected) 0.6f else 1f
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
        override fun getCount(): Int = photos.size
        override fun getItem(position: Int): Any = photos[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? Cell) ?: Cell(this@MainActivity)
            val item = photos[position]
            cell.bind(item, item.key in selected, item.key in done)
            return cell
        }
    }
}
