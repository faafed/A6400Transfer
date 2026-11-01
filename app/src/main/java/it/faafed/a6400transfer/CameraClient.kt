package it.faafed.a6400transfer

import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/** Una foto elencata dalla fotocamera. */
data class PhotoItem(
    val folder: String,
    val title: String,
    val thumbUrl: String?,
    val downloadUrl: String?,
    val size: Long
) {
    /** Chiave stabile per ricordare le foto già scaricate. */
    val key: String get() = "$folder/$title"
}

class CameraException(message: String, val httpCode: Int = -1) : Exception(message)

/**
 * Client per l'API SOAP/UPnP usata dalla modalità "Invia a smartphone" delle Sony Alpha
 * (la stessa di Imaging Edge Mobile). Indirizzo tipico: 192.168.122.1:64321.
 *
 * Solo librerie JDK: nessuna dipendenza Android, quindi testabile anche sul PC.
 */
class CameraClient(
    private val host: String = "192.168.122.1",
    private val port: Int = 64321
) {
    private val base: String get() = "http://$host:$port"

    private class Res(val url: String, val protocolInfo: String, val size: Long)

    // ---------------------------------------------------------------- SOAP

    private fun soap(path: String, action: String, body: String): Pair<Int, String> {
        val envelope = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>$body</s:Body></s:Envelope>"
        val bytes = envelope.toByteArray(Charsets.UTF_8)
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("SOAPACTION", "\"$action\"")
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** Mostra "Trasferimento" sulla fotocamera. Gli errori sono ignorati (funziona solo in modalità push). */
    fun transferStart() {
        try {
            soap(
                "/upnp/control/XPushList",
                "urn:schemas-sony-com:service:XPushList:1#X_TransferStart",
                "<u:X_TransferStart xmlns:u=\"urn:schemas-sony-com:service:XPushList:1\"></u:X_TransferStart>"
            )
        } catch (_: Exception) {
        }
    }

    /** Esce dalla modalità "Invia a smartphone" sulla fotocamera. */
    fun transferEnd() {
        try {
            soap(
                "/upnp/control/XPushList",
                "urn:schemas-sony-com:service:XPushList:1#X_TransferEnd",
                "<u:X_TransferEnd xmlns:u=\"urn:schemas-sony-com:service:XPushList:1\">" +
                    "<ErrCode>0</ErrCode></u:X_TransferEnd>"
            )
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- Elenco foto

    /**
     * Elenca le foto. Prima prova "PushRoot" (foto scelte sulla fotocamera); se la fotocamera
     * risponde con errore, passa a "PhotoRoot" (tutte le foto della scheda).
     */
    fun listPhotos(): List<PhotoItem> {
        val out = ArrayList<PhotoItem>()
        try {
            browse("PushRoot", "PushRoot", out, 0)
        } catch (e: CameraException) {
            if (e.httpCode < 0) throw e
            out.clear()
            browse("PhotoRoot", "PhotoRoot", out, 0)
        }
        return out
    }

    private fun parseXml(xml: String): org.w3c.dom.Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun browse(objectId: String, folder: String, out: MutableList<PhotoItem>, depth: Int) {
        if (depth > 6) return
        var start = 0
        while (true) {
            val body = "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                "<ObjectID>$objectId</ObjectID>" +
                "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                "<Filter>*</Filter>" +
                "<StartingIndex>$start</StartingIndex>" +
                "<RequestedCount>200</RequestedCount>" +
                "<SortCriteria></SortCriteria>" +
                "</u:Browse>"
            val (code, text) = soap(
                "/upnp/control/ContentDirectory",
                "urn:schemas-upnp-org:service:ContentDirectory:1#Browse",
                body
            )
            if (code != 200) throw CameraException("Elenco non disponibile ($objectId, HTTP $code)", code)

            val doc = parseXml(text)
            val resultNode = doc.getElementsByTagName("Result").item(0)
            val returned = doc.getElementsByTagName("NumberReturned").item(0)
                ?.textContent?.trim()?.toIntOrNull() ?: 0
            val total = doc.getElementsByTagName("TotalMatches").item(0)
                ?.textContent?.trim()?.toIntOrNull() ?: 0

            if (resultNode != null && !resultNode.textContent.isNullOrBlank()) {
                // Il <Result> contiene un secondo documento XML (DIDL-Lite) codificato come testo.
                val didl = parseXml(resultNode.textContent)

                val containers = didl.getElementsByTagName("container")
                val subDirs = ArrayList<Pair<String, String>>()
                for (i in 0 until containers.length) {
                    val c = containers.item(i) as org.w3c.dom.Element
                    val id = c.getAttribute("id")
                    val name = c.getElementsByTagName("dc:title").item(0)?.textContent?.trim() ?: id
                    if (id.isNotEmpty()) subDirs.add(Pair(id, name))
                }

                val items = didl.getElementsByTagName("item")
                for (i in 0 until items.length) {
                    val itemEl = items.item(i) as org.w3c.dom.Element
                    val title = itemEl.getElementsByTagName("dc:title").item(0)?.textContent?.trim() ?: continue
                    val resNodes = itemEl.getElementsByTagName("res")
                    val resList = ArrayList<Res>()
                    for (j in 0 until resNodes.length) {
                        val r = resNodes.item(j) as org.w3c.dom.Element
                        val url = r.textContent?.trim().orEmpty()
                        if (url.isEmpty()) continue
                        resList.add(
                            Res(
                                url,
                                r.getAttribute("protocolInfo"),
                                r.getAttribute("size").toLongOrNull() ?: 0L
                            )
                        )
                    }
                    val download = pickDownload(resList)
                    out.add(
                        PhotoItem(
                            folder = folder,
                            title = title,
                            thumbUrl = pickThumb(resList) ?: download,
                            downloadUrl = download,
                            size = resList.firstOrNull { r -> r.url == download }?.size ?: 0L
                        )
                    )
                }

                for ((id, name) in subDirs) browse(id, name, out, depth + 1)
            }

            start += returned
            if (returned <= 0 || start >= total) break
        }
    }

    /** Versione migliore: la più grande con "size"; per i RAW (nessuna size) la miniatura _LRG. */
    private fun pickDownload(res: List<Res>): String? {
        res.filter { it.size > 0 }.maxByOrNull { it.size }?.let { return it.url }
        res.firstOrNull { it.protocolInfo.contains("_LRG") }?.let { return it.url }
        return res.lastOrNull()?.url
    }

    private fun pickThumb(res: List<Res>): String? {
        for (tag in listOf("_TN", "_SM", "_LRG")) {
            res.firstOrNull { it.protocolInfo.contains(tag) }?.let { return it.url }
        }
        return null
    }

    // ---------------------------------------------------------------- Download

    /** Apre una connessione verso un URL della fotocamera. Il chiamante legge e chiude lo stream. */
    fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        if (conn.responseCode != 200) {
            val code = conn.responseCode
            conn.disconnect()
            throw CameraException("Download fallito (HTTP $code)", code)
        }
        return conn
    }

    fun fetchBytes(url: String): ByteArray {
        val conn = open(url)
        try {
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
