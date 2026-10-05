package dev.qaremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/** Resultado de un comando: ok + mensaje corto. */
data class CmdResult(val ok: Boolean, val message: String)

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "QAREMOTE"
        private const val CHANNEL_ID = "qaremote_active"
        private const val NOTIF_ID = 1

        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showActiveNotification()
        Log.i(TAG, "Servicio conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    // ------------------------------------------------------------ notificacion

    private fun showActiveNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "QA Remote activo", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val settings = PendingIntent.getActivity(
            this, 1,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        @Suppress("DEPRECATION")
        val n = b.setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("QA Remote activo")
            .setContentText("QA Remote activo · el PC puede controlar este móvil")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Desactivar", settings).build())
            .build()
        n.flags = n.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        try {
            nm.notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso de notificaciones: concedelo desde la app")
        }
    }

    // ------------------------------------------------------------ comandos

    fun execute(cmd: String, a: Bundle, done: (CmdResult) -> Unit) {
        try {
            when (cmd) {
                "tap" -> gesture(point(a.f("x"), a.f("y")), 60, done)
                "longtap" -> gesture(point(a.f("x"), a.f("y")), a.l("ms", 800), done)
                "swipe" -> {
                    val p = Path().apply { moveTo(a.f("x"), a.f("y")); lineTo(a.f("x2"), a.f("y2")) }
                    gesture(p, a.l("ms", 300), done)
                }
                "back" -> done(global(GLOBAL_ACTION_BACK, "back"))
                "home" -> done(global(GLOBAL_ACTION_HOME, "home"))
                "recents" -> done(global(GLOBAL_ACTION_RECENTS, "recents"))
                "text" -> done(setText(a.text()))
                "tapText" -> tapNode(findByText(a.text()), "texto '${a.text()}'", done)
                "tapId" -> tapNode(findById(a.text()), "id '${a.text()}'", done)
                "dump" -> done(dump())
                "wait" -> waitFor(a.text(), a.l("ms", 5000).coerceAtMost(25_000), done)
                else -> done(CmdResult(false, "comando desconocido: $cmd"))
            }
        } catch (e: Exception) {
            done(CmdResult(false, "${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private fun gesture(path: Path, durationMs: Long, done: (CmdResult) -> Unit) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000)))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = done(CmdResult(true, "gesto completado"))
            override fun onCancelled(d: GestureDescription?) = done(CmdResult(false, "gesto cancelado"))
        }, main)
        if (!ok) done(CmdResult(false, "dispatchGesture rechazado"))
    }

    private fun global(action: Int, name: String) =
        if (performGlobalAction(action)) CmdResult(true, name) else CmdResult(false, "$name rechazado")

    private fun setText(value: String): CmdResult {
        val node = roots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            ?: return CmdResult(false, "no hay campo con foco de entrada")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            CmdResult(true, "texto escrito (${value.length} car.)")
        else CmdResult(false, "ACTION_SET_TEXT rechazado")
    }

    private fun tapNode(node: AccessibilityNodeInfo?, what: String, done: (CmdResult) -> Unit) {
        if (node == null) return done(CmdResult(false, "no encontrado: $what"))
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return done(CmdResult(true, "click en $what"))
                }
                break
            }
            n = n.parent
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return done(CmdResult(false, "sin bounds: $what"))
        gesture(point(r.exactCenterX(), r.exactCenterY()), 60) { res ->
            done(CmdResult(res.ok, if (res.ok) "toque en centro de $what" else res.message))
        }
    }

    // ------------------------------------------------------------ nodos

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = try { windows.mapNotNull { it.root } } catch (e: Exception) { emptyList() }
        return list.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    private fun visibleNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 80) return
            if (n.isVisibleToUser) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        roots().forEach { walk(it, 0) }
        return out
    }

    private fun norm(s: CharSequence?): String {
        if (s == null) return ""
        return Normalizer.normalize(s.toString(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().trim()
    }

    private fun findByText(value: String): AccessibilityNodeInfo? {
        val q = norm(value)
        if (q.isEmpty()) return null
        val nodes = visibleNodes()
        fun labels(n: AccessibilityNodeInfo) = listOf(norm(n.text), norm(n.contentDescription))
        return nodes.firstOrNull { n -> labels(n).any { it == q } }
            ?: nodes.firstOrNull { n -> labels(n).any { it.isNotEmpty() && it.contains(q) } }
    }

    private fun findById(value: String): AccessibilityNodeInfo? {
        if (value.isBlank()) return null
        return visibleNodes().firstOrNull { n ->
            val id = n.viewIdResourceName ?: return@firstOrNull false
            id == value || id.endsWith(":id/$value")
        }
    }

    private fun waitFor(value: String, timeoutMs: Long, done: (CmdResult) -> Unit) {
        val start = SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - start
                when {
                    findByText(value) != null -> done(CmdResult(true, "visible tras $elapsed ms: '$value'"))
                    elapsed >= timeoutMs -> done(CmdResult(false, "timeout $timeoutMs ms esperando '$value'"))
                    else -> main.postDelayed(this, 250)
                }
            }
        }
        tick.run()
    }

    private fun dump(): CmdResult {
        val arr = JSONArray()
        val r = Rect()
        for (n in visibleNodes()) {
            n.getBoundsInScreen(r)
            arr.put(JSONObject().apply {
                put("class", n.className?.toString() ?: "")
                put("text", n.text?.toString() ?: "")
                put("desc", n.contentDescription?.toString() ?: "")
                put("viewId", n.viewIdResourceName ?: "")
                put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
                put("clickable", n.isClickable)
                put("package", n.packageName?.toString() ?: "")
            })
        }
        val json = JSONObject().put("ts", System.currentTimeMillis()).put("nodes", arr).toString()
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "dump.json")
        file.writeText(json)
        json.chunked(3000).forEachIndexed { i, c -> Log.i(TAG, "DUMP[$i] $c") }
        return CmdResult(true, "dump ${arr.length()} nodos en ${file.absolutePath}")
    }
}

@Suppress("DEPRECATION")
private fun Bundle.raw(k: String): String? = get(k)?.toString()

private fun Bundle.f(k: String): Float =
    raw(k)?.toFloatOrNull() ?: throw IllegalArgumentException("falta extra numerico '$k'")

private fun Bundle.l(k: String, def: Long): Long = raw(k)?.toFloatOrNull()?.toLong() ?: def

private fun Bundle.text(): String {
    val b64 = getString("b64")
    if (!b64.isNullOrEmpty()) return String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    return raw("value") ?: ""
}
