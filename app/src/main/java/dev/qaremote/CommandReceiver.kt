package dev.qaremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recibe ordenes de `adb shell am broadcast`. Protegido en el manifest con
 * android.permission.DUMP: solo shell/sistema pueden enviarlo.
 * Resultado: resultCode 0 = OK, 1 = ERROR; resultData = "OK: ..." / "ERROR: ...".
 */
class CommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val extras = intent.extras ?: Bundle()
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)

        fun finish(r: CmdResult) {
            if (!finished.compareAndSet(false, true)) return
            val line = (if (r.ok) "OK: " else "ERROR: ") + "$cmd · ${r.message}"
            if (r.ok) Log.i(RemoteAccessibilityService.TAG, line)
            else Log.e(RemoteAccessibilityService.TAG, line)
            pending.resultCode = if (r.ok) 0 else 1
            pending.resultData = line
            pending.finish()
        }

        main.postDelayed({ finish(CmdResult(false, "timeout interno")) }, 28_000)
        main.post {
            val svc = RemoteAccessibilityService.instance
            if (svc == null) finish(CmdResult(false, "servicio de accesibilidad no activo"))
            else svc.execute(cmd, extras) { finish(it) }
        }
    }

    companion object {
        const val ACTION = "dev.qaremote.CMD"
    }
}
