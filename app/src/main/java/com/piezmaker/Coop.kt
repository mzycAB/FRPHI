package com.piezmaker

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** 房间里的一个玩家（服务端分配 id：0 = 服务端自己） */
class CoopPlayer(val id: Int, val name: String, val ip: String) {
    var color = 0            // 0=没框住 1=蓝（写谱） 2=绿（打谱）
    var confirmed = false    // 开始联机的"确认"
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("ip", ip); put("color", color); put("ok", confirmed)
    }

    companion object {
        fun from(o: JSONObject): CoopPlayer = CoopPlayer(
            o.optInt("id", 0), o.optString("name", "?"), o.optString("ip", "?")
        ).also { it.color = o.optInt("color", 0); it.confirmed = o.optBoolean("ok", false) }
    }
}

/**
 * 联机（TCP + 一行一条 JSON）。
 *
 * 设计上刻意做得很朴素：
 * - 同步的是**整份谱面草稿文本**（DraftCodec，含剪贴板），改动防抖 150ms 发一次；
 *   一万音符也就几十 KB，手机同网段完全够用，而且不用做增量合并（最后写入者胜）。
 * - 渲染/开关（autoplay、显示隐藏音符、缩放、倍速……）都不同步，各设备自己管。
 * - 服务端是唯一数据源；客户端本地不落盘，退出房间什么都没留。
 */
object Coop {

    const val ROLE_NONE = 0
    const val ROLE_HOST = 1
    const val ROLE_CLIENT = 2

    const val DEFAULT_PORT = 25566
    /** 快速联机的 UDP 发现端口 */
    const val QUICK_UDP_PORT = 25567

    // ---- 安全上限（防止对端发超大包把内存吃光）----
    private const val MAX_MEDIA_BYTES = 64 * 1024 * 1024     // 收到的媒体文件上限
    private const val MAX_SEND_BYTES = 48 * 1024 * 1024      // 发送的媒体文件上限
    private const val MAX_CHART_CHARS = 8 * 1024 * 1024      // 谱面文本上限
    private const val MAX_CLIENTS = 8                        // 一个房间最多几台
    private const val MAX_BAD_PW = 3                         // 同一连接密码错几次就断
    const val COLOR_NONE = 0
    const val COLOR_BLUE = 1
    const val COLOR_GREEN = 2

    var role = ROLE_NONE
        private set
    var password = ""
    var hostIp = ""
    var port = DEFAULT_PORT
    /** 房间对应的项目 id：服务端 = 那个副本；客户端 = 临时项目 */
    var roomTabId = ""
    /** 服务端：副本的来源项目 id（"退出保存至项目"要用） */
    var roomSourceTabId = ""
    /** 游戏延迟（秒）：蓝方按继续后，绿方延迟多久开始 */
    var gameDelaySec = 1.0
    /** 快速联机的一次性令牌：广播里只发它，**不发真密码** */
    var quickToken = ""
        private set

    /** 自己的玩家 id（服务端恒为 0） */
    var myId = 0
        private set

    /** 房间元信息（bpm/流速/判定线位置/横竖线…），客户端靠它渲染 */
    var metaJson = ""

    // ---- 媒体（音频/背景图）：服务端把文件发给客户端，客户端也能有完整体验 ----
    /** 服务端：自己这份的音频/背景图 uri（开房时传进来） */
    private var hostMusicUri = ""
    private var hostImageUri = ""
    /** 客户端：收到的文件路径（退出房间时删掉 roommedia/） */
    var mediaMusicPath: String? = null
        private set
    var mediaImagePath: String? = null
        private set
    /** 还在接收媒体（界面用来挡一下"进编辑"） */
    var mediaPending = false
        private set
    /** (kind, path)：kind = "music" / "image" */
    var onMedia: ((String, String) -> Unit)? = null
    private var curKind = ""
    private var curName = ""
    private var curBuf: java.io.ByteArrayOutputStream? = null

    val players = CopyOnWriteArrayList<CoopPlayer>()
    val currentChart: String get() = chartText
    private var chartText = ""

    private val main = Handler(Looper.getMainLooper())

    // ---- 联机日志（排查"连不上"用）----
    private val logLines = ArrayDeque<String>()
    private val logFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    /** 记一行日志（主界面「联机日志」能看、能复制） */
    fun log(msg: String) {
        synchronized(logLines) {
            logLines.addLast(logFmt.format(java.util.Date()) + "  " + msg)
            while (logLines.size > 400) logLines.removeFirst()
        }
    }

    fun logText(): String = synchronized(logLines) {
        if (logLines.isEmpty()) "（还没有日志）" else logLines.joinToString("\n")
    }

    // ---- 回调（都在主线程） ----
    var onChart: ((String) -> Unit)? = null
    var onPlayers: (() -> Unit)? = null
    var onStart: ((Double) -> Unit)? = null
    var onClosed: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    /** 有人点了「退出你写我打」→ 双方一起结束这一局 */
    var onStop: (() -> Unit)? = null

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    fun isInRoom(): Boolean = role != ROLE_NONE

    /** 本机所有 IPv4（给日志看，排查"填错网卡"） */
    fun ipCandidates(): List<String> {
        val out = ArrayList<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        a.hostAddress?.let { out.add((nif.name ?: "?") + "=" + it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * 本机在局域网里的 IPv4。
     * **必须优先 Wi-Fi / 热点**：手机开着移动数据时，网卡列表里 rmnet 可能排在 wlan 前面，
     * 拿运营商地址给对面填，对面永远连不上（这就是"点击联机连不上"的常见原因）。
     */
    fun localIp(): String {
        var fallback = ""
        try {
            val nifs = NetworkInterface.getNetworkInterfaces().toList().sortedBy { nif ->
                val n = nif.name ?: ""
                when {
                    n.startsWith("wlan") -> 0
                    n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> 1
                    n.startsWith("eth") -> 2
                    n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") -> 9
                    else -> 5
                }
            }
            for (nif in nifs) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (fallback.isEmpty()) fallback = ip
                        val n = nif.name ?: ""
                        if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("eth")) return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return fallback.ifEmpty { "127.0.0.1" }
    }

    fun deviceName(): String {
        val m = Build.MODEL ?: "Android"
        return if (m.startsWith(Build.MANUFACTURER ?: "", ignoreCase = true)) m
        else "${Build.MANUFACTURER} $m".trim()
    }

    // ---------------------------------------------------------------- 快速联机（UDP 广播发现）
    private var beacon: DatagramSocket? = null
    /** 主界面 onCreate 里塞进来（拿 WifiManager 用） */
    var appContext: Context? = null
    private var mcast: android.net.wifi.WifiManager.MulticastLock? = null

    /** 部分机型不拿这个锁就收不到 255.255.255.255 的广播 */
    private fun acquireMcast() {
        if (mcast != null) return
        try {
            val wm = appContext?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return
            mcast = wm.createMulticastLock("frphi-coop").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMcast() {
        try { mcast?.release() } catch (_: Exception) {}
        mcast = null
    }

    /** 房间信息（快速客户端发现到的） */
    class QuickRoom(val ip: String, val port: Int, val pw: String, val name: String)

    /** 开始向局域网广播"我这儿有快速房间"。**只广播一次性令牌，不广播真密码** */
    fun quickBeaconStart(p: Int, token: String) {
        quickToken = token
        quickBeaconStop()
        val s = try { DatagramSocket() } catch (_: Exception) { return }
        s.broadcast = true
        acquireMcast()
        beacon = s
        val payload = JSONObject().put("t", "qroom").put("port", p).put("pw", token)
            .put("name", deviceName()).toString().toByteArray(Charsets.UTF_8)
        Thread {
            while (!s.isClosed && role == ROLE_HOST) {
                try {
                    s.send(
                        DatagramPacket(payload, payload.size,
                            InetAddress.getByName("255.255.255.255"), QUICK_UDP_PORT)
                    )
                } catch (_: Exception) {}
                try { Thread.sleep(500) } catch (_: Exception) { break }
            }
        }.apply { isDaemon = true }.start()
    }

    fun quickBeaconStop() {
        try { beacon?.close() } catch (_: Exception) {}
        beacon = null
        releaseMcast()
    }

    /** 在局域网里找快速房间（阻塞，调用方放后台线程）；找不到返回 null */
    fun quickDiscover(timeoutMs: Int): QuickRoom? {
        var sock: DatagramSocket? = null
        acquireMcast()
        return try {
            sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(QUICK_UDP_PORT))
                soTimeout = 300
            }
            val buf = ByteArray(512)
            val end = System.currentTimeMillis() + timeoutMs
            var found: QuickRoom? = null
            while (found == null && System.currentTimeMillis() < end) {
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (_: Exception) { continue }
                val txt = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val o = try { JSONObject(txt) } catch (_: Exception) { continue }
                if (o.optString("t") != "qroom") continue
                val ip = (pkt.address?.hostAddress ?: continue).removePrefix("/")
                found = QuickRoom(ip, o.optInt("port", DEFAULT_PORT), o.optString("pw"), o.optString("name"))
            }
            found
        } catch (_: Exception) {
            null
        } finally {
            try { sock?.close() } catch (_: Exception) {}
            releaseMcast()
        }
    }

    // ---------------------------------------------------------------- 服务端
    /**
     * 开房间。返回 null = 成功，否则是给用户看的错误文案。
     * 端口被占用（含"同一个 ip/端口/密码 又开一次"）→ "此通道已被占用"。
     */
    fun host(pw: String, p: Int, chart: String, meta: String = "", musicUri: String = "", imageUri: String = ""): String? {
        if (isInRoom()) leave(notify = false)
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p), 8) }
        } catch (e: Exception) {
            log("开房失败：绑定端口 $p 出错 ${e.javaClass.simpleName} ${e.message}")
            return "此通道已被占用"
        }
        log("开房成功：端口 $p，本机 IP ${localIp()}，密码 ${pwMask(pw)}")
        log("本机网卡：" + ipCandidates().joinToString("，"))
        role = ROLE_HOST
        password = pw
        port = p
        hostIp = localIp()
        myId = 0
        chartText = chart
        metaJson = meta
        hostMusicUri = musicUri
        hostImageUri = imageUri
        firstWriteLogged = false
        server = ss
        players.clear()
        players.add(CoopPlayer(0, deviceName(), hostIp))
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                Thread { serveClient(s) }.start()
            }
        }.apply { isDaemon = true }.start()
        // 心跳：每 10 秒发一次，客户端日志里能看到"收到消息：ping"→ 通道还活着
        Thread {
            while (role == ROLE_HOST && server != null) {
                try { Thread.sleep(10_000) } catch (_: Exception) { break }
                if (role == ROLE_HOST) broadcast(JSONObject().put("t", "ping"))
            }
        }.apply { isDaemon = true }.start()
        return null
    }

    private fun serveClient(s: Socket) {
        var who: CoopPlayer? = null
        var badPw = 0
        log("收到连接：${s.inetAddress?.hostAddress}")
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                val o = try { JSONObject(line) } catch (_: Exception) { continue }
                when (o.optString("t")) {
                    "join" -> {
                        if (clients.size >= MAX_CLIENTS) {
                            log("拒绝加入：房间已满（$MAX_CLIENTS 台）")
                            sendTo(s, JSONObject().put("t", "joinerr").put("msg", "房间已满"))
                            break
                        }
                        val given = o.optString("pw")
                        // 快速联机用一次性令牌，真密码不出现在广播里
                        val ok = given == password || (quickToken.isNotEmpty() && given == quickToken)
                        if (!ok) {
                            badPw++
                            log("拒绝加入：密码不对（第 $badPw 次，来自 ${s.inetAddress?.hostAddress}）")
                            sendTo(s, JSONObject().put("t", "joinerr").put("msg", "密码错误"))
                            if (badPw >= MAX_BAD_PW) break
                            try { Thread.sleep(300) } catch (_: Exception) {}
                            continue
                        }
                        val ip = (s.inetAddress?.hostAddress ?: "?").removePrefix("/")
                        val id = (players.maxOfOrNull { it.id } ?: 0) + 1
                        val pl = CoopPlayer(id, o.optString("name", "设备$id"), ip)
                        players.add(pl)
                        who = pl
                        clients.add(s)
                        log("客户端加入成功：${pl.name} @ $ip（id=$id）")
                        sendTo(
                            s, JSONObject().put("t", "joinok").put("id", id)
                                .put("chart", chartText).put("meta", metaJson)
                                .put("players", playersJson())
                        )
                        broadcast(JSONObject().put("t", "players").put("p", playersJson()))
                        post { onStatus?.invoke("有设备加入：${pl.name}") }
                        // 把音频/背景图也发过去（大文件，单独线程慢慢发）
                        Thread { sendMedia(s) }.apply { isDaemon = true }.start()
                    }
                    "chart" -> {
                        val d = o.optString("d", "")
                        if (d.length > MAX_CHART_CHARS) {
                            log("对端发来的谱面过大（${d.length} 字），已忽略")
                        } else {
                            chartText = d
                            log("收到谱面 ${d.length} 字（来自客户端）")
                            post { onChart?.invoke(chartText) }
                            broadcastExcept(s, JSONObject().put("t", "chart").put("d", chartText))
                        }
                    }
                    "frame" -> { applyFrame(o.optInt("i", -1), o.optInt("c", 0)) }
                    "confirm" -> { applyConfirm(o.optInt("i", -1)) }
                    "start" -> {
                        val d = o.optDouble("delay", gameDelaySec)
                        broadcast(JSONObject().put("t", "start").put("delay", d))
                        post { onStart?.invoke(d) }
                    }
                    "stop" -> {
                        broadcast(JSONObject().put("t", "stop"))
                        post { onStop?.invoke() }
                    }
                    "kick" -> { applyKick(o.optInt("i", -1)) }
                    "bye" -> break
                }
            }
        } catch (e: Throwable) {
            log("客户端连接异常：${e.javaClass.simpleName} ${e.message}")
        } finally {
            log("连接断开：${s.inetAddress?.hostAddress}")
            clients.remove(s)
            writers.remove(s)
            try { s.close() } catch (_: Exception) {}
            val me = who
            if (me != null && role == ROLE_HOST) {
                players.removeAll { it.id == me.id }
                broadcast(JSONObject().put("t", "players").put("p", playersJson()))
                post { onPlayers?.invoke() }
            }
        }
    }

    private fun applyFrame(i: Int, c: Int) {
        val p = players.firstOrNull { it.id == i } ?: return
        // 一个颜色只能有一个玩家：新框住谁，就把同色的旧玩家取消
        if (c != COLOR_NONE) for (o in players) if (o !== p && o.color == c) o.color = COLOR_NONE
        p.color = if (p.color == c) COLOR_NONE else c
        broadcast(JSONObject().put("t", "players").put("p", playersJson()))
        post { onPlayers?.invoke() }
    }

    private fun applyConfirm(i: Int) {
        val p = players.firstOrNull { it.id == i } ?: return
        p.confirmed = true
        broadcast(JSONObject().put("t", "players").put("p", playersJson()))
        post { onPlayers?.invoke() }
    }

    private fun applyKick(i: Int) {
        // 踢的是房主（i==0）或房主踢自己 → 直接关房间
        if (i == 0) {
            log("房主被踢 / 房主关闭房间")
            broadcast(JSONObject().put("t", "bye").put("msg", "房主关闭了房间"))
            leave(notify = false)
            post { onClosed?.invoke("房间已关闭") }
            return
        }
        val p = players.firstOrNull { it.id == i } ?: return
        players.removeAll { it.id == i }
        broadcast(JSONObject().put("t", "players").put("p", playersJson()))
        // 通知被踢的人
        val victim = clients.firstOrNull { it.inetAddress?.hostAddress?.removePrefix("/") == p.ip }
        victim?.let { sendTo(it, JSONObject().put("t", "bye").put("msg", "你已被踢出房间")) }
        post { onPlayers?.invoke() }
    }

    // ---------------------------------------------------------------- 客户端
    /** 加入房间。返回 null = 成功，否则是错误文案 */
    fun join(ip: String, p: Int, pw: String): String? {
        if (isInRoom()) leave(notify = false)
        log("正在连接 $ip:$p …")
        val s = try {
            Socket().apply { connect(InetSocketAddress(ip, p), 4000); soTimeout = 6000 }
        } catch (e: Exception) {
            log("连接失败：${e.javaClass.simpleName} ${e.message}")
            return "连不上 $ip:$p（${e.javaClass.simpleName}）"
        }
        log("TCP 已连上 $ip:$p，正在握手")
        return try {
            val out = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            writers[s] = out                       // 之后 send() 复用这一个
            out.write(JSONObject().put("t", "join").put("pw", pw).put("name", deviceName()).toString() + "\n")
            out.flush()
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: throw IllegalStateException("服务器没有响应")
            val o = JSONObject(line)
            log("收到握手回复：${o.optString("t")}")
            if (o.optString("t") == "joinerr") {
                try { s.close() } catch (_: Exception) {}
                writers.remove(s)
                return o.optString("msg", "加入失败")
            }
            role = ROLE_CLIENT
            password = pw
            hostIp = ip
            port = p
            myId = o.optInt("id", 1)
            chartText = o.optString("chart", "")
            metaJson = o.optString("meta", "")
            firstWriteLogged = false
            socket = s
            players.clear()
            val arr = o.optJSONArray("players") ?: JSONArray()
            for (k in 0 until arr.length()) players.add(CoopPlayer.from(arr.getJSONObject(k)))
            s.soTimeout = 0
            Thread { readLoop(s, reader) }.apply { isDaemon = true }.start()
            log("加入成功：id=$myId，玩家 ${players.size} 人")
            null
        } catch (e: Exception) {
            log("握手失败：${e.javaClass.simpleName} ${e.message}")
            try { s.close() } catch (_: Exception) {}
            "加入失败：${e.message ?: "未知错误"}"
        }
    }

    private fun readLoop(s: Socket, reader: BufferedReader) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val o = try { JSONObject(line) } catch (_: Exception) {
                    log("收到无法解析的消息（${line.length} 字），已跳过")
                    continue
                }
                val type = o.optString("t")
                if (type != "chunk") log("收到消息：$type")
                when (type) {
                    "chart" -> {
                        val d = o.optString("d", "")
                        if (d.length > MAX_CHART_CHARS) {
                            log("服务端发来的谱面过大（${d.length} 字），已忽略")
                        } else {
                            chartText = d
                            log("收到谱面 ${chartText.length} 字")
                            post { onChart?.invoke(chartText) }
                        }
                    }
                    "players" -> {
                        players.clear()
                        val arr = o.optJSONArray("p") ?: JSONArray()
                        for (k in 0 until arr.length()) players.add(CoopPlayer.from(arr.getJSONObject(k)))
                        post { onPlayers?.invoke() }
                    }
                    "start" -> {
                        val d = o.optDouble("delay", gameDelaySec)
                        post { onStart?.invoke(d) }
                    }
                    "stop" -> post { onStop?.invoke() }
                    "file" -> {
                        curKind = o.optString("k", "music")
                        curName = o.optString("name", "media")
                        curBuf = java.io.ByteArrayOutputStream()
                        post { mediaPending = true }
                    }
                    "chunk" -> {
                        try {
                            val bytes = android.util.Base64.decode(o.optString("d"), android.util.Base64.NO_WRAP)
                            val buf = curBuf
                            if (buf != null && buf.size() + bytes.size <= MAX_MEDIA_BYTES) {
                                buf.write(bytes)
                            } else {
                                curBuf = null
                                log("媒体文件超过 ${MAX_MEDIA_BYTES / 1024 / 1024}MB，已丢弃")
                            }
                        } catch (_: Exception) {}
                    }
                    "fileend" -> {
                        val buf = curBuf
                        val kind = curKind
                        val name = curName
                        curBuf = null
                        if (buf != null) {
                            // 文件名来自对端：只留文件名本身，挡掉 ../ 之类的路径穿越
                            val f = java.io.File(mediaDir(), sanitizeName(name, kind))
                            try {
                                f.parentFile?.mkdirs()
                                f.writeBytes(buf.toByteArray())
                                if (kind == "image") mediaImagePath = f.absolutePath
                                else mediaMusicPath = f.absolutePath
                                log("收到媒体 $kind：$name（${f.length() / 1024} KB）")
                                post { onMedia?.invoke(kind, f.absolutePath) }
                            } catch (_: Exception) {}
                        }
                    }
                    "mediaend" -> post { mediaPending = false }
                    "bye" -> {
                        val msg = o.optString("msg", "房间已关闭")
                        log("服务端要求退出：$msg")
                        post { closeAndNotify(msg) }
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            log("读取异常：${e.javaClass.simpleName} ${e.message}")
        } finally {
            if (role == ROLE_CLIENT) post { closeAndNotify("与服务器的连接已断开") }
        }
    }

    // ---------------------------------------------------------------- 媒体传输
    private fun mediaDir(): java.io.File = java.io.File(appContext?.filesDir, "roommedia")

    /** 服务端：把音频 + 背景图发给刚加入的客户端 */
    private fun sendMedia(s: Socket) {
        try {
            if (hostMusicUri.isNotEmpty()) sendOneFile(s, "music", hostMusicUri)
            if (hostImageUri.isNotEmpty()) sendOneFile(s, "image", hostImageUri)
        } catch (_: Exception) {}
        sendTo(s, JSONObject().put("t", "mediaend"))
    }

    private fun sendOneFile(s: Socket, kind: String, uriStr: String) {
        val ctx = appContext ?: return
        val bytes = try {
            ctx.contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return
        if (bytes.size > MAX_SEND_BYTES) {
            log("媒体太大（${bytes.size / 1024 / 1024}MB > ${MAX_SEND_BYTES / 1024 / 1024}MB），跳过：$kind")
            return
        }
        val name = mediaName(uriStr, kind)
        log("发送媒体 $kind：$name（${bytes.size / 1024} KB）")
        sendTo(s, JSONObject().put("t", "file").put("k", kind).put("name", name).put("size", bytes.size))
        val step = 48 * 1024
        var i = 0
        while (i < bytes.size) {
            val n = minOf(step, bytes.size - i)
            val b64 = android.util.Base64.encodeToString(bytes, i, n, android.util.Base64.NO_WRAP)
            sendTo(s, JSONObject().put("t", "chunk").put("d", b64))
            i += n
        }
        sendTo(s, JSONObject().put("t", "fileend"))
    }

    /** 密码只记长度，日志里绝不出现明文（导出日志会被发给别人看） */
    private fun pwMask(pw: String): String = if (pw.isEmpty()) "（空）" else "${pw.length} 位"

    /**
     * 文件名消毒：只留文件名本身，去掉路径分隔符和一切可疑字符。
     * 对端可以随便发 name，不消毒就能用 "../../shared_prefs/xx.xml" 写到目录外面。
     */
    private fun sanitizeName(raw: String, kind: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        if (n.isEmpty()) n = if (kind == "image") "bg.png" else "music.mp3"
        if (n.length > 64) {
            val ext = n.substringAfterLast('.', "")
            n = n.take(56) + if (ext.isNotEmpty()) "." + ext.take(6) else ""
        }
        if (!n.contains('.')) n += if (kind == "image") ".png" else ".mp3"
        return n
    }

    /** 猜一个安全文件名（保留扩展名，按 uri 最后一段） */
    private fun mediaName(uriStr: String, kind: String): String {
        val seg = try {
            android.net.Uri.parse(uriStr).lastPathSegment ?: ""
        } catch (_: Exception) {
            ""
        }
        return kind + "_" + sanitizeName(seg, kind)
    }

    // ---------------------------------------------------------------- 通用
    /** 发谱面（服务端广播给所有人，客户端发给服务端） */
    fun sendChart(text: String) {
        chartText = text
        if (role == ROLE_HOST) {
            log("发送谱面 ${text.length} 字 → ${clients.size} 台客户端")
            broadcast(JSONObject().put("t", "chart").put("d", text))
        } else {
            log("发送谱面 ${text.length} 字 → 服务端")
            send(JSONObject().put("t", "chart").put("d", text))
        }
    }

    fun framePlayer(id: Int, color: Int) {
        if (role == ROLE_HOST) applyFrame(id, color)
        else send(JSONObject().put("t", "frame").put("i", id).put("c", color))
    }

    fun confirmPlayer(id: Int) {
        if (role == ROLE_HOST) applyConfirm(id)
        else send(JSONObject().put("t", "confirm").put("i", id))
    }

    fun startGame(delay: Double) {
        gameDelaySec = delay
        if (role == ROLE_HOST) {
            broadcast(JSONObject().put("t", "start").put("delay", delay))
            onStart?.invoke(delay)
        } else send(JSONObject().put("t", "start").put("delay", delay))
    }

    /** 双方一起退出「你写我打」：发一条 stop，然后本机也结束这一局 */
    fun stopGame() {
        if (role == ROLE_HOST) broadcast(JSONObject().put("t", "stop"))
        else if (role == ROLE_CLIENT) send(JSONObject().put("t", "stop"))
        post { onStop?.invoke() }
    }

    /**
     * 踢人（任何人都能用）：踢房主 / 房主踢自己 = 关闭房间；客户端踢自己 = 自己直接退出。
     */
    fun kick(id: Int) {
        if (id == myId && role == ROLE_CLIENT) {          // 踢自己：本地直接走，不必等对面回话
            leave(notify = false)
            post { onClosed?.invoke("已退出房间") }
            return
        }
        if (role == ROLE_HOST) applyKick(id) else send(JSONObject().put("t", "kick").put("i", id))
    }

    fun me(): CoopPlayer? = players.firstOrNull { it.id == myId }

    /**
     * 玩家列表的显示顺序：① 房主 ② 自己 ③ 其他人。
     * 自己是房主时只有 ①③（以前 myId==0 时房主会被加两遍，列表里就会出现两个一模一样的自己）。
     * 顺便按 id 去重，任何时候都不会出现同一个玩家两条。
     */
    fun orderedPlayers(): List<CoopPlayer> {
        val out = ArrayList<CoopPlayer>(players.size)
        val seen = HashSet<Int>()
        fun add(p: CoopPlayer?) {
            if (p == null) return
            if (seen.add(p.id)) out.add(p)
        }
        add(players.firstOrNull { it.id == 0 })                       // ① 房主
        if (myId != 0) add(players.firstOrNull { it.id == myId })      // ② 自己（房主就是自己时不再加）
        for (p in players) if (p.id != 0 && p.id != myId) add(p)      // ③ 其他人
        return out
    }

    fun blue(): CoopPlayer? = players.firstOrNull { it.color == COLOR_BLUE }
    fun green(): CoopPlayer? = players.firstOrNull { it.color == COLOR_GREEN }
    fun bothFramed(): Boolean = blue() != null && green() != null
    fun bothConfirmed(): Boolean = bothFramed() && blue()!!.confirmed && green()!!.confirmed

    /** 主动离开（服务端会关掉所有连接） */
    fun leave(notify: Boolean = true) {
        val wasRole = role
        val roleName = if (wasRole == ROLE_HOST) "服务端" else if (wasRole == ROLE_CLIENT) "客户端" else "无"
        log("退出房间（原角色=" + roleName + "）")
        role = ROLE_NONE
        firstWriteLogged = false
        quickBeaconStop()
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        for (c in clients) try { c.close() } catch (_: Exception) {}
        clients.clear()
        writers.clear()
        players.clear()
        chartText = ""
        quickToken = ""
        val wasClient = wasRole == ROLE_CLIENT
        if (wasClient) {
            mediaMusicPath = null
            mediaImagePath = null
            mediaPending = false
            try { mediaDir().deleteRecursively() } catch (_: Exception) {}
        }
        roomTabId = ""
        roomSourceTabId = ""
        myId = 0
        if (notify && wasRole != ROLE_NONE) post { onClosed?.invoke("已退出房间") }
    }

    /** 被动关闭（被踢 / 断线 / 服务端关了） */
    private fun closeAndNotify(msg: String) {
        if (role == ROLE_NONE) return
        leave(notify = false)
        onClosed?.invoke(msg)
    }

    // ---------------------------------------------------------------- 收发细节
    private fun playersJson(): JSONArray {
        val arr = JSONArray()
        for (p in players) arr.put(p.toJson())
        return arr
    }

    private fun send(o: JSONObject) {
        val s = socket ?: return
        sendTo(s, o)
    }

    /** 每个连接只建一个 Writer 并复用：每条消息都 new 一个 OutputStreamWriter 会随 GC 把底层流关掉 */
    private val writers = java.util.concurrent.ConcurrentHashMap<Socket, OutputStreamWriter>()

    /**
     * 每个连接只建一个 Writer 并复用：每条消息都 new 一个 OutputStreamWriter 会随 GC 把底层流关掉。
     * 这里加锁再做「查-建」：发送线程和媒体线程可能同时取同一个 socket 的 writer，
     * 并发建两个会有一个被丢掉，被丢掉的那个 GC 时可能连累底层流。
     */
    private fun writerOf(s: Socket): OutputStreamWriter? = try {
        writers[s] ?: synchronized(writers) {
            writers[s] ?: OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                .also { writers[s] = it }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 主线程**绝对不能**直接写 socket：Android 会抛 NetworkOnMainThreadException（之前被静默 catch 吞掉，
     * 表现就是"看着在发、对面什么都没收到"）。所以主线程发的一律丢到这个单线程里按顺序写。
     */
    private val sendPool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "coop-send").apply { isDaemon = true }
    }

    /** 进房间后是否已经成功写出过一条消息（只记一次日志，避免每次编辑都刷屏） */
    @Volatile
    private var firstWriteLogged = false

    private fun sendTo(s: Socket, o: JSONObject) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { sendPool.execute { writeNow(s, o) } } catch (_: Exception) {}
        } else {
            writeNow(s, o)
        }
    }

    private fun writeNow(s: Socket, o: JSONObject) {
        try {
            val w = writerOf(s) ?: run { log("发送失败：连接已关闭"); return }
            synchronized(w) {
                w.write(o.toString() + "\n")
                w.flush()
            }
            // 进房间后第一条真正写出去的消息记一行：日志里看到它 = 写通道是通的（不用再猜"是不是没连上"）
            if (!firstWriteLogged) {
                firstWriteLogged = true
                log("发送通道已就绪（首条消息写出成功）")
            }
        } catch (e: Exception) {
            log("发送失败：${e.javaClass.simpleName} ${e.message}")
            try { writers.remove(s) } catch (_: Exception) {}
        }
    }

    private fun broadcast(o: JSONObject) {
        for (c in clients) sendTo(c, o)
    }

    private fun broadcastExcept(from: Socket, o: JSONObject) {
        for (c in clients) if (c !== from) sendTo(c, o)
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/**
 * 把颜色往白色提亮（= 「淡化」）。t = 0 原色，t = 1 纯白。
 * 联机界面里所有"蓝/绿"都是从框色算出来的，不再另外手写一套颜色：
 * 框色本身 #2D7FF9（蓝）/ #22C55E（绿），按钮文字 = frameTint(框色, 0.45f)，
 * 填充 = 框色 + alpha（淡化），描边 = 框色本身。
 */
fun frameTint(c: Int, t: Float): Int {
    fun ch(shift: Int): Int {
        val v = (c shr shift) and 0xFF
        return (v + (255 - v) * t).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** 房间框的两种颜色（项目抽屉的蓝框/绿框、联机按钮都用这两个值） */
const val FRAME_BLUE = 0xFF2D7FF9.toInt()
const val FRAME_GREEN = 0xFF22C55E.toInt()

/** 按框色画一个"框"：填充 = 框色淡化，描边 = 框色本身 */
fun frameBg(frame: Int, radiusPx: Float): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply {
        setColor((frame and 0x00FFFFFF) or 0x33000000)
        setStroke(2, frame)
        cornerRadius = radiusPx
    }
