package com.oojoo.farm.slave.service

import android.util.Log
import com.oojoo.farm.slave.vision.CameraHolder
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * 라이브 카메라 MJPEG 스트림 — `multipart/x-mixed-replace`.
 *
 * 외부 라이브러리 없이 raw ServerSocket 로 구현:
 * - 서비스 시작 시 :[PORT] 에서 상시 수신 대기 (클라이언트 없으면 프레임
 *   생성 없이 대기만 하므로 비용이 거의 없다).
 * - 클라이언트 접속 시 최신 프레임([CameraHolder.latestFrame])을
 *   ~5fps 로 multipart 파트로 스트리밍.
 * - 마스터 앱은 slaves.stream_url (하트비트로 보고) 을 받아
 *   WebView <img src> 로 렌더링한다.
 *
 * LAN 직결 스트림 — 인증은 없다 (이 시스템의 PRD 신뢰 모델과 동일,
 * 농장 로컬 네트워크 내부 사용 전제).
 */
object MjpegStreamer {
    private const val TAG = "MjpegStreamer"
    const val PORT = 8613
    private const val BOUNDARY = "frame"
    private const val FRAME_INTERVAL_MS = 200L   // ~5fps
    private const val FRAME_STALE_MS = 3000L     // 이보다 오래된 프레임은 재전송
    private const val CLIENT_TIMEOUT_MS = 30_000

    @Volatile
    private var running = false
    private var server: ServerSocket? = null

    val active: Boolean get() = running

    /** LAN IP 기반 스트림 URL. IP 확보 불가 시 null (heartbeat 보고 생략). */
    fun streamUrl(): String? {
        val ip = lanIp() ?: return null
        return "http://$ip:$PORT/stream.mjpg"
    }

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", PORT))
                }
                server = ss
                Log.i(TAG, "mjpeg streamer listening on 0.0.0.0:$PORT")
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running) continue else break
                        throw e
                    }
                    Thread({ serve(client) }, "mjpeg-client").also {
                        it.isDaemon = true
                        it.start()
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "streamer stopped: ${e.message}")
            } finally {
                running = false
                server = null
                try { ss?.close() } catch (_: Exception) {}
            }
        }
        t.name = "mjpeg-accept"
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = CLIENT_TIMEOUT_MS
            val out = client.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: multipart/x-mixed-replace; boundary=").append(BOUNDARY).append("\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Pragma: no-cache\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray()
            out.write(header)
            while (client.isConnected && !client.isClosed) {
                val frame = CameraHolder.latestFrame
                if (frame != null && System.currentTimeMillis() - CameraHolder.latestFrameAt < FRAME_STALE_MS) {
                    val partHead = buildString {
                        append("--").append(BOUNDARY).append("\r\n")
                        append("Content-Type: image/jpeg\r\n")
                        append("Content-Length: ").append(frame.size).append("\r\n\r\n")
                    }.toByteArray()
                    out.write(partHead)
                    out.write(frame)
                    out.write(CRLF)
                    out.flush()
                }
                Thread.sleep(FRAME_INTERVAL_MS)
            }
        } catch (_: Exception) {
            // 클라이언트 단절/타임아웃 — 정상 종료
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private val CRLF = "\r\n".toByteArray()

    /** 첫 번째 site-local IPv4 (wlan0 등). 없으면 null. */
    private fun lanIp(): String? {
        return try {
            for (ifc in NetworkInterface.getNetworkInterfaces()) {
                if (!ifc.isUp || ifc.isLoopback) continue
                for (addr in ifc.interfaceAddresses) {
                    val a = addr.address
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        return a.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
