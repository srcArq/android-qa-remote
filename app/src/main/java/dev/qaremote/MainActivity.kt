package dev.qaremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply { text = "QA Remote"; textSize = 24f })
        status = TextView(this).apply { textSize = 18f; setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(status)
        col.addView(Button(this).apply {
            text = "Abrir ajustes de accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
            text = """
                Cómo usarlo:
                1. Pulsa «Abrir ajustes de accesibilidad» y activa «QA Remote» (en Xiaomi: Accesibilidad › Servicios descargados).
                2. Conecta el móvil al PC con la depuración USB activada.
                3. Desde el PC usa qa-remote.ps1 o qa-remote.mjs para tocar, deslizar, escribir y leer la pantalla.
                4. Al terminar, desactiva el servicio (botón «Desactivar» de la notificación).

                Solo acepta órdenes del shell de ADB. No usa internet ni guarda datos personales.
            """.trimIndent()
        })
        setContentView(ScrollView(this).apply { addView(col, MATCH_PARENT, WRAP_CONTENT) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val on = RemoteAccessibilityService.instance != null
        status.text = if (on) "● Servicio ACTIVO: el PC puede controlar este móvil"
        else "○ Servicio desactivado"
        status.setTextColor(if (on) Color.rgb(0, 130, 60) else Color.rgb(170, 60, 0))
    }
}
