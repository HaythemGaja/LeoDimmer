package com.universal.tvdimmer

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * LeoDimmer v2.4 -- the phone, without a cloud.
 *
 * A small HTTP server on the box itself. Open the printed address in a phone
 * browser on the same Wi-Fi, type the four-digit PIN, and the modes, the tune
 * key, the lamp level and colour, blackout and the sleep timer are all there.
 * No account, no pairing, no traffic leaving the house.
 *
 * The security model is deliberately small, and it is worth being honest about
 * it:
 *
 *  - the PIN is required for every request, including the page itself;
 *  - repeated wrong guesses are rate-limited (20, then a minute of silence);
 *  - it serves nothing but this app's own state, and it stops with the dimmer;
 *  - it is a LAN remote. There is no TLS and no port forwarding, and it must
 *    never be exposed to the internet.
 */
object WebRemote {

    private const val PORT = 8099
    private const val MAX_FAILURES = 20
    private const val BLOCK_MILLIS = 60_000L

    private var server: ServerSocket? = null
    private var worker: Thread? = null
    private var app: Context? = null
    private var failures = 0
    private var blockedUntil = 0L

    /** Starting twice is a no-op; the port stays where it is. */
    @Synchronized
    fun start(context: Context) {
        if (worker != null) return
        app = context.applicationContext
        val thread = Thread({
            var socket: ServerSocket? = null
            try {
                socket = ServerSocket(PORT).apply {
                    reuseAddress = true
                    soTimeout = 1000
                }
                synchronized(this) { server = socket }
                while (!Thread.currentThread().isInterrupted) {
                    val client = try {
                        socket.accept()
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        client.use { handle(it) }
                    } catch (_: Exception) {
                        // One malformed request must never take the remote down.
                    }
                }
            } catch (_: Exception) {
                // Port taken, or the socket is not allowed: the app simply has
                // no web remote, and the row in the app still says so.
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                    // Already closed.
                }
                synchronized(this) { server = null }
            }
        }, "leodimmer-web")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
        try {
            server?.close()
        } catch (_: Exception) {
            // Already closed.
        }
        server = null
    }

    @Synchronized
    fun isRunning(): Boolean = worker != null

    /** The address to type on a phone, e.g. http://192.168.1.20:8099 */
    fun address(): String = "http://${localIpv4() ?: "box-ip"}:$PORT"

    fun port(): Int = PORT

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.asSequence()
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    // ---- Requests ----

    private fun handle(client: Socket) {
        client.soTimeout = 4000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val out = client.getOutputStream()
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") {
            respond(out, "405 Method Not Allowed", "text/plain", "GET only")
            return
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val context = app ?: return

        if (System.currentTimeMillis() < blockedUntil) {
            respond(out, "429 Too Many Requests", "text/plain", "Too many PIN attempts")
            return
        }
        val expected = Settings.webPin(context)
        if (!pinMatches(query["pin"] ?: "", expected)) {
            respond(out, "401 Unauthorized", "text/html", pinPage())
            return
        }

        when (path) {
            "/", "/index.html" -> respond(out, "200 OK", "text/html", page(expected))
            "/api/state" -> respond(out, "200 OK", "application/json", stateJson(context))
            "/api/cmd" -> {
                val what = query["do"] ?: ""
                val value = query["v"]?.toIntOrNull() ?: 0
                command(context, what, value)
                respond(out, "200 OK", "application/json", stateJson(context))
            }
            else -> respond(out, "404 Not Found", "text/plain", "No such page")
        }
    }

    /** Constant-ish comparison with a simple failure counter. */
    private fun pinMatches(given: String, expected: String): Boolean {
        if (expected.isEmpty()) return false
        if (given.length == expected.length && given == expected) {
            failures = 0
            return true
        }
        failures += 1
        if (failures >= MAX_FAILURES) {
            failures = 0
            blockedUntil = System.currentTimeMillis() + BLOCK_MILLIS
        }
        return false
    }

    private fun command(context: Context, what: String, value: Int) {
        when (what) {
            "mood" -> if (value in Settings.MOOD_DAY..Settings.MOOD_SLEEP) {
                OverlayService.send(context, OverlayService.ACTION_SET_MOOD, value)
            }
            "cycle" -> OverlayService.send(context, OverlayService.ACTION_CYCLE_MOOD)
            "tune" -> OverlayService.send(
                context, OverlayService.ACTION_TUNE, value.coerceIn(-10, 10)
            )
            "colour" -> OverlayService.send(
                context, OverlayService.ACTION_TUNE_COLOUR, if (value < 0) -1 else 1
            )
            "level" -> {
                Settings.setAmbientLevel(context, value)
                OverlayService.send(context, OverlayService.ACTION_SET_MOOD, Settings.MOOD_SLEEP)
            }
            "lamp" -> {
                val wantOn = value != 0
                if (wantOn != Settings.isAmbient(context)) {
                    OverlayService.send(context, OverlayService.ACTION_TOGGLE_AMBIENT)
                }
            }
            "blackout" -> OverlayService.send(context, OverlayService.ACTION_TOGGLE_BLACKOUT)
            "timer" -> OverlayService.send(
                context, OverlayService.ACTION_SET_SLEEP_TIMER, value
            )
        }
    }

    private fun stateJson(context: Context): String {
        val mood = Settings.getMood(context)
        return "{" +
            "\"mode\":\"${Settings.moodName(mood)}\"," +
            "\"mood\":$mood," +
            "\"brightness\":${Settings.getBrightness(context)}," +
            "\"blackout\":${Settings.isBlackout(context)}," +
            "\"lamp\":${Settings.isAmbient(context)}," +
            "\"level\":${Settings.getAmbientLevel(context)}," +
            "\"colour\":\"${Settings.ambientColorName(context)}\"," +
            "\"timer\":${Settings.getSleepTimerMinutes(context)}" +
            "}"
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key.isEmpty()) continue
            map[decode(key)] = decode(pair.substringAfter('=', ""))
        }
        return map
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (_: Exception) {
        text
    }

    private fun respond(out: OutputStream, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ---- Pages ----

    private fun pinPage(): String = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LeoDimmer PIN</title>
<style>
body{margin:0;background:#0b0b0d;color:#ededed;font:16px system-ui,sans-serif;
display:flex;align-items:center;justify-content:center;height:100vh}
form{text-align:center}
input{font-size:28px;text-align:center;width:6ch;letter-spacing:.3em;background:#1b1b20;
color:#ededed;border:1px solid #2a2a32;border-radius:12px;padding:12px;margin:14px 0}
button{font:inherit;background:#ffc857;color:#0b0b0d;border:0;border-radius:12px;padding:12px 28px}
</style></head><body>
<form action="/" method="get">
  <div>LeoDimmer PIN</div>
  <input name="pin" inputmode="numeric" pattern="[0-9]{4}" maxlength="4" autofocus>
  <div><button>Open</button></div>
</form>
</body></html>
""".trimIndent()

    private fun page(pin: String): String = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LeoDimmer</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#0b0b0d;color:#ededed;font:16px/1.4 system-ui,sans-serif;
padding:18px;max-width:520px;margin:auto}
h1{font-size:20px;font-weight:400;margin:0 0 4px}
p{color:#9a9a9a;margin:0 0 14px}
button{font:inherit;color:#ededed;background:#1b1b20;border:1px solid #2a2a32;
border-radius:12px;padding:14px 10px}
button:active{background:#2a2a32}
button.on{background:#ffc857;color:#0b0b0d;border-color:#ffc857}
.modes{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;margin-bottom:10px}
.row{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:8px}
.big{font-size:20px;padding:20px 10px}
.lamp{height:30px;border-radius:12px;border:1px solid #2a2a32;margin-bottom:14px;background:#141418}
</style></head><body>
<h1>LeoDimmer</h1>
<p id="sub">Connecting…</p>
<div class="lamp" id="lamp"></div>
<div class="modes">
  <button data-mood="0" id="m0">Day</button>
  <button data-mood="1" id="m1">Night</button>
  <button data-mood="2" id="m2">Sleep</button>
</div>
<div class="row"><button data-tune="-2" class="big">Less</button><button data-tune="2" class="big">More</button></div>
<div class="row"><button data-colour="-1">Colour &lt;</button><button data-colour="1">Colour &gt;</button></div>
<div class="row"><button data-lamp="1">Lamp on</button><button data-lamp="0">Lamp off</button></div>
<div class="row"><button data-blackout="1">Blackout</button><button data-timer="0">Timer off</button></div>
<script>
var PIN = "$pin";
function cmd(what, v) {
  fetch('/api/cmd?pin=' + PIN + '&do=' + what + '&v=' + v, {cache:'no-store'})
    .then(state).catch(function () {});
}
var palette = {
  'Warm white': [255,180,107], 'Candle': [255,138,61], 'Amber': [255,160,0],
  'Sunset': [255,106,61], 'Rose': [255,84,112], 'Violet': [155,107,255],
  'Ice': [207,227,255], 'Moonlight': [111,155,255]
};
function lampColour(name, level) {
  var c = palette[name] || [255,180,107], f = level / 100;
  return 'rgb(' + Math.round(c[0]*f) + ',' + Math.round(c[1]*f) + ',' + Math.round(c[2]*f) + ')';
}
function state() {
  fetch('/api/state?pin=' + PIN, {cache:'no-store'})
    .then(function (r) { return r.json(); })
    .then(function (s) {
      document.getElementById('sub').textContent = s.mode + ' \u00b7 ' + s.brightness + '%' +
        (s.lamp ? ' \u00b7 lamp ' + s.colour + ' ' + s.level + '%' : '') +
        (s.blackout ? ' \u00b7 blackout' : '') +
        (s.timer ? ' \u00b7 timer ' + s.timer + ' min' : '');
      for (var i = 0; i < 3; i++) {
        document.getElementById('m' + i).className = (s.mood === i) ? 'on' : '';
      }
      document.getElementById('lamp').style.background =
        s.lamp ? lampColour(s.colour, s.level) : '#141418';
    })
    .catch(function () { document.getElementById('sub').textContent = 'Box unreachable'; });
}
document.querySelectorAll('[data-mood]').forEach(function (b) {
  b.onclick = function () { cmd('mood', b.dataset.mood); };
});
document.querySelectorAll('[data-tune]').forEach(function (b) {
  b.onclick = function () { cmd('tune', b.dataset.tune); };
});
document.querySelectorAll('[data-colour]').forEach(function (b) {
  b.onclick = function () { cmd('colour', b.dataset.colour); };
});
document.querySelectorAll('[data-lamp]').forEach(function (b) {
  b.onclick = function () { cmd('lamp', b.dataset.lamp); };
});
document.querySelectorAll('[data-blackout]').forEach(function (b) {
  b.onclick = function () { cmd('blackout', 1); };
});
document.querySelectorAll('[data-timer]').forEach(function (b) {
  b.onclick = function () { cmd('timer', b.dataset.timer); };
});
state();
setInterval(state, 4000);
</script>
</body></html>
""".trimIndent()
}
