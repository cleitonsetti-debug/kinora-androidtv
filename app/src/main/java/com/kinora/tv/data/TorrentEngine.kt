package com.kinora.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Motor P2P (fontes com "infoHash" dos addons), feito com libtorrent4j.
 *
 * Fluxo: o addon devolve infoHash (+ fileIdx e trackers) -> Streams cria uma fonte "torrent:HASH?f=IDX&tr=..."
 * -> o player chama prepare(): baixa os metadados, escolhe o arquivo de video, baixa so ele em ordem e
 * serve o arquivo num servidor HTTP local (127.0.0.1) com suporte a Range. O ExoPlayer toca esse endereco;
 * quando ele pede um trecho que ainda nao chegou, o servidor espera a peca e da prioridade (deadline) a ela.
 * Uma fonte P2P por vez; os arquivos ficam no cache do app e sao apagados ao fechar o player.
 */
object TorrentEngine {

    class Prepared(val url: String, val format: String)

    class P2PException(msg: String) : Exception(msg)

    private class Active(
        val hash: String,
        val th: TorrentHandle,
        val fileIdx: Int,
        val file: File,
        val fileOffset: Long,
        val size: Long,
        val pieceLen: Int,
        val lastPiece: Int,
        val name: String,
    ) {
        @Volatile var closed = false
        @Volatile var aheadFrom = -1
    }

    private class Spec(val hash: String, val fileIdx: Int, val trackers: List<String>)

    private val VIDEO_EXT = listOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp")

    /** trackers publicos usados quando o addon nao informa nenhum (o DHT tambem e usado) */
    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
    )

    private val lock = Any()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "kinora-p2p").apply { isDaemon = true } }
    private var baseDir: File? = null
    private var sm: SessionManager? = null
    @Volatile private var failed: String? = null
    @Volatile private var active: Active? = null
    private var server: ServerSocket? = null

    /** Chamado ao abrir o app: guarda a pasta e limpa sobras de uma sessao anterior. */
    fun init(ctx: Context) {
        if (baseDir != null) return
        val d = File(ctx.cacheDir, "torrents")
        baseDir = d
        pool.execute { try { d.deleteRecursively() } catch (_: Throwable) {} }
    }

    fun isTorrent(url: String): Boolean = url.startsWith("torrent:")

    fun buildUrl(hash: String, fileIdx: Int, trackers: List<String>): String {
        val sb = StringBuilder("torrent:").append(hash.lowercase()).append("?f=").append(fileIdx)
        for (tr in trackers) sb.append("&tr=").append(urlEncode(tr))
        return sb.toString()
    }

    private fun parse(url: String): Spec? {
        val body = url.removePrefix("torrent:")
        val q = body.indexOf('?')
        val hash = (if (q >= 0) body.substring(0, q) else body).lowercase()
        if (!Regex("^[0-9a-f]{40}$").matches(hash)) return null
        var idx = -1
        val trs = ArrayList<String>()
        if (q >= 0) {
            for (part in body.substring(q + 1).split('&')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val k = part.substring(0, eq)
                val v = try { URLDecoder.decode(part.substring(eq + 1), "UTF-8") } catch (_: Exception) { "" }
                if (k == "f") idx = v.toIntOrNull() ?: -1
                else if (k == "tr" && v.isNotEmpty()) trs.add(v)
            }
        }
        return Spec(hash, idx, trs)
    }

    private fun session(): SessionManager? {
        synchronized(lock) {
            sm?.let { return it }
            if (failed != null) return null
            return try {
                val sp = SettingsPack()
                sp.connectionsLimit(200)
                sp.activeDownloads(2)
                val s = SessionManager(false)
                s.start(SessionParams(sp))
                sm = s
                s
            } catch (e: Throwable) {
                // biblioteca nativa ausente ou Android antigo demais
                failed = e.toString()
                NetLog.add("p2p://engine", 0, e.javaClass.simpleName)
                null
            }
        }
    }

    private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    private fun mimeOf(name: String): String = when (ext(name)) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private fun rateText(bytesPerSec: Int): String {
        val mb = bytesPerSec / 1048576.0
        return if (mb >= 1) String.format("%.1f MB/s", mb) else "${bytesPerSec / 1024} KB/s"
    }

    private fun sizeText(bytes: Long): String = String.format("%.1f GB", bytes / 1073741824.0)

    /** Linha de estado para o player enquanto carrega (null se nao houver fonte P2P ativa). */
    fun statusLine(t: Strings): String? {
        val a = active ?: return null
        return try {
            val st = a.th.status()
            t.f2("p2p_buffer", rateText(st.downloadRate()), st.numPeers().toString())
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Prepara uma fonte "torrent:...": metadados, arquivo de video, primeiras pecas e servidor local.
     * Lanca P2PException com a mensagem para o usuario se nao der certo.
     */
    suspend fun prepare(url: String, t: Strings, onStatus: (String) -> Unit): Prepared {
        val spec = parse(url)
        val prev = active
        if (spec != null && prev != null && prev.hash == spec.hash) {
            // mesmo torrent (ex.: proximo episodio de um pacote): reaproveita, so troca o arquivo
            prev.closed = true
            active = null
        } else {
            stop()
        }
        if (spec == null) throw P2PException(t("p2p_nometa"))
        val s = withContext(Dispatchers.IO) { session() } ?: throw P2PException(t("p2p_unavailable"))
        val root = baseDir ?: throw P2PException(t("p2p_unavailable"))
        val saveDir = File(root, spec.hash)
        val sha = Sha1Hash.parseHex(spec.hash)
        var th: TorrentHandle? = null
        var ok = false
        try {
            onStatus(t.f("p2p_meta", "0"))
            val trs = spec.trackers.ifEmpty { DEFAULT_TRACKERS }
            val magnet = StringBuilder("magnet:?xt=urn:btih:").append(spec.hash)
            for (tr in trs) magnet.append("&tr=").append(urlEncode(tr))
            withContext(Dispatchers.IO) {
                saveDir.mkdirs()
                if (s.find(sha) == null) s.download(magnet.toString(), saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }

            // 1) metadados (lista de arquivos), ate 60 s
            var waited = 0
            while (true) {
                val h = withContext(Dispatchers.IO) { s.find(sha) }
                if (h != null && h.isValid) {
                    th = h
                    val hasMeta = withContext(Dispatchers.IO) { h.torrentFile() != null }
                    if (hasMeta) break
                    val peers = withContext(Dispatchers.IO) { h.status().numPeers() }
                    onStatus(t.f("p2p_meta", peers.toString()))
                } else if (waited > 0 && waited % 3000 == 0) {
                    // a remocao anterior do mesmo torrent terminou agora: adiciona de novo
                    withContext(Dispatchers.IO) { s.download(magnet.toString(), saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD) }
                }
                if (waited >= 60000) throw P2PException(t("p2p_nometa"))
                delay(500)
                waited += 500
            }
            val h = th ?: throw P2PException(t("p2p_nometa"))

            // 2) arquivo de video: o fileIdx do addon ou o maior video do torrent
            val a = withContext(Dispatchers.IO) {
                val ti = h.torrentFile() ?: throw P2PException(t("p2p_nometa"))
                val fs = ti.files()
                val n = fs.numFiles()
                var idx = spec.fileIdx
                if (idx !in 0 until n || fs.fileSize(idx) <= 0 || ext(fs.fileName(idx)) !in VIDEO_EXT) {
                    idx = -1
                    var best = 0L
                    for (i in 0 until n) {
                        val sz = fs.fileSize(i)
                        if (ext(fs.fileName(i)) in VIDEO_EXT && sz > best) {
                            best = sz
                            idx = i
                        }
                    }
                    if (idx < 0 && spec.fileIdx in 0 until n && fs.fileSize(spec.fileIdx) > 0) idx = spec.fileIdx
                }
                if (idx < 0) throw P2PException(t("p2p_nofile"))
                val size = fs.fileSize(idx)
                if (root.usableSpace in 1 until size + 200L * 1048576) throw P2PException(t.f("p2p_space", sizeText(size)))

                h.prioritizeFiles(Array(n) { if (it == idx) Priority.TOP_PRIORITY else Priority.IGNORE })
                h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                val off = fs.fileOffset(idx)
                val pl = ti.pieceLength()
                Active(
                    hash = spec.hash,
                    th = h,
                    fileIdx = idx,
                    file = File(saveDir, fs.filePath(idx)),
                    fileOffset = off,
                    size = size,
                    pieceLen = pl,
                    lastPiece = ((off + size - 1) / pl).toInt(),
                    name = fs.fileName(idx),
                )
            }

            // 3) primeiras pecas e a ultima (indice do MKV/MP4) antes de abrir o player, ate 3 min
            val first = (a.fileOffset / a.pieceLen).toInt()
            val need = (first..minOf(first + 1, a.lastPiece)).toMutableList()
            if (a.lastPiece !in need) need.add(a.lastPiece)
            withContext(Dispatchers.IO) {
                need.forEachIndexed { i, p -> h.setPieceDeadline(p, 300 + i * 300) }
            }
            waited = 0
            while (true) {
                val (have, st) = withContext(Dispatchers.IO) { Pair(need.all { h.havePiece(it) }, h.status()) }
                if (have) break
                onStatus(t.f2("p2p_buffer", rateText(st.downloadRate()), st.numPeers().toString()))
                if (waited >= 180000) throw P2PException(t("p2p_slow"))
                delay(500)
                waited += 500
            }

            val port = withContext(Dispatchers.IO) { ensureServer() }
            active = a
            ok = true
            val safe = a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return Prepared("http://127.0.0.1:$port/${a.hash}/${a.fileIdx}/$safe", guessStreamFormat(safe))
        } finally {
            if (!ok) {
                val dead = th
                pool.execute {
                    try {
                        val h2 = dead ?: s.find(sha)
                        if (h2 != null && h2.isValid) s.remove(h2, SessionHandle.DELETE_FILES)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /** Para a fonte P2P atual e apaga o que foi baixado. */
    fun stop() {
        val a = active ?: return
        active = null
        a.closed = true
        val s = sm ?: return
        pool.execute {
            try {
                if (a.th.isValid) s.remove(a.th, SessionHandle.DELETE_FILES)
            } catch (_: Throwable) {}
        }
    }

    // -------------------------------------------------------------------------
    // Servidor HTTP local (so 127.0.0.1)
    // -------------------------------------------------------------------------
    private fun ensureServer(): Int {
        synchronized(lock) {
            server?.let { if (!it.isClosed) return it.localPort }
            val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server = ss
            pool.execute {
                while (!ss.isClosed) {
                    try {
                        val c = ss.accept()
                        pool.execute { serve(c) }
                    } catch (_: Throwable) {
                        break
                    }
                }
            }
            return ss.localPort
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun writeHead(out: OutputStream, status: String, headers: List<String>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        for (h in headers) sb.append(h).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun serve(sock: Socket) {
        try {
            sock.use { c ->
                c.soTimeout = 30000
                val inp = BufferedInputStream(c.getInputStream())
                val out = BufferedOutputStream(c.getOutputStream(), 65536)
                val reqLine = readLine(inp) ?: return
                var range = ""
                while (true) {
                    val l = readLine(inp) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0 && l.substring(0, i).trim().equals("range", true)) range = l.substring(i + 1).trim()
                }
                val parts = reqLine.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val seg = (parts.getOrNull(1) ?: "").trimStart('/').split('/')
                val a = active
                if (a == null || a.closed || seg.size < 2 || seg[0] != a.hash || seg[1] != a.fileIdx.toString()) {
                    writeHead(out, "404 Not Found", listOf("Content-Length: 0"))
                    out.flush()
                    return
                }
                var start = 0L
                var end = a.size - 1
                var partial = false
                if (range.startsWith("bytes=")) {
                    val r = range.removePrefix("bytes=").substringBefore(',')
                    val dash = r.indexOf('-')
                    if (dash >= 0) {
                        val sPart = r.substring(0, dash).trim()
                        val ePart = r.substring(dash + 1).trim()
                        if (sPart.isEmpty()) {
                            val suffix = ePart.toLongOrNull() ?: 0L
                            start = maxOf(0L, a.size - suffix)
                        } else {
                            start = sPart.toLongOrNull() ?: 0L
                            ePart.toLongOrNull()?.let { end = minOf(it, a.size - 1) }
                        }
                        partial = true
                    }
                }
                if (start >= a.size || start > end) {
                    writeHead(out, "416 Range Not Satisfiable", listOf("Content-Range: bytes */${a.size}", "Content-Length: 0"))
                    out.flush()
                    return
                }
                val hs = arrayListOf(
                    "Content-Type: ${mimeOf(a.name)}",
                    "Accept-Ranges: bytes",
                    "Content-Length: ${end - start + 1}",
                )
                if (partial) hs.add("Content-Range: bytes $start-$end/${a.size}")
                writeHead(out, if (partial) "206 Partial Content" else "200 OK", hs)
                if (method != "HEAD") stream(a, start, end, out)
                out.flush()
            }
        } catch (_: Throwable) {
            // o player fechou a conexao (seek ou saida): normal
        }
    }

    /** Da prioridade as proximas pecas a partir de [piece] (cerca de 16 MB a frente). */
    private fun readAhead(a: Active, piece: Int) {
        if (a.aheadFrom == piece) return
        val window = maxOf(4, (16 * 1048576) / a.pieceLen)
        if (a.aheadFrom >= 0 && (piece < a.aheadFrom || piece > a.aheadFrom + window * 2)) a.th.clearPieceDeadlines()
        a.aheadFrom = piece
        val last = minOf(a.lastPiece, piece + window)
        var i = 0
        for (p in piece..last) {
            if (!a.th.havePiece(p)) a.th.setPieceDeadline(p, 500 + i * 400)
            i++
        }
    }

    private fun waitPiece(a: Active, piece: Int): Boolean {
        readAhead(a, piece)
        val t0 = System.currentTimeMillis()
        while (!a.th.havePiece(piece)) {
            if (a.closed || active !== a) return false
            if (System.currentTimeMillis() - t0 > 120000) return false
            Thread.sleep(100)
        }
        return true
    }

    private fun stream(a: Active, start: Long, end: Long, out: OutputStream) {
        val buf = ByteArray(65536)
        var raf: RandomAccessFile? = null
        try {
            var pos = start
            while (pos <= end) {
                if (a.closed || active !== a) return
                val piece = ((a.fileOffset + pos) / a.pieceLen).toInt()
                if (!waitPiece(a, piece)) return
                val pieceEnd = (piece.toLong() + 1) * a.pieceLen - a.fileOffset   // fim da peca dentro do arquivo
                val chunkEnd = minOf(end + 1, pieceEnd)
                if (raf == null) raf = RandomAccessFile(a.file, "r")
                raf.seek(pos)
                var retries = 0
                while (pos < chunkEnd) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), chunkEnd - pos).toInt())
                    if (n <= 0) {
                        // a peca chegou mas ainda nao foi gravada no arquivo
                        if (++retries > 50 || a.closed) return
                        Thread.sleep(100)
                        raf.seek(pos)
                        continue
                    }
                    out.write(buf, 0, n)
                    pos += n
                }
            }
        } finally {
            try { raf?.close() } catch (_: Throwable) {}
        }
    }
}
