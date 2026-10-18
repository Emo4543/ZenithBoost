package com.zenith.mobile

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var prevFilter = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#07070d")
        window.navigationBarColor = Color.parseColor("#07070d")
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#07070d"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
    }

    override fun onResume() {
        super.onResume()
        restoreDnd()
    }

    // Oyundan ZENITH'e dönünce Rahatsız Etme modunu eski haline getirir
    private fun restoreDnd() {
        if (prevFilter >= 0) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(prevFilter)
            }
            prevFilter = -1
        }
    }

    private fun availMem(): Long {
        val mi = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.availMem
    }

    private fun openSettings(action: String) {
        runOnUiThread {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e2: Exception) {
                }
            }
        }
    }

    inner class Bridge {

        @JavascriptInterface
        fun getGames(): String {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo.applicationInfo
                if (ai.packageName == packageName) continue
                @Suppress("DEPRECATION")
                val isGame = ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0) ||
                        ai.category == ApplicationInfo.CATEGORY_GAME
                arr.put(
                    JSONObject()
                        .put("pkg", ai.packageName)
                        .put("name", ri.loadLabel(pm).toString())
                        .put("game", isGame)
                )
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun getIcon(pkg: String): String {
            return try {
                val d = packageManager.getApplicationIcon(pkg)
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                d.setBounds(0, 0, 96, 96)
                d.draw(c)
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun getStats(): String {
            val mi = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val nm = getSystemService(NotificationManager::class.java)
            return JSONObject()
                .put("total", mi.totalMem)
                .put("avail", mi.availMem)
                .put("temp", temp)
                .put("battery", level)
                .put("dndGranted", nm.isNotificationPolicyAccessGranted)
                .toString()
        }

        // pkg boşsa sadece boost yapar, oyun açmaz
        @JavascriptInterface
        fun boostLaunch(pkg: String, kill: Boolean, dnd: Boolean): String {
            val result = JSONObject()
            val before = availMem()
            var cleaned = 0

            if (kill) {
                val pm = packageManager
                val protect = HashSet<String>()
                protect.add(packageName)
                if (pkg.isNotEmpty()) protect.add(pkg)
                val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                home?.activityInfo?.packageName?.let { protect.add(it) }
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    for (m in imm.enabledInputMethodList) protect.add(m.packageName)
                } catch (e: Exception) {
                }
                val am = getSystemService(ActivityManager::class.java)
                for (ai in pm.getInstalledApplications(0)) {
                    if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                    if (protect.contains(ai.packageName)) continue
                    try {
                        am.killBackgroundProcesses(ai.packageName)
                        cleaned++
                    } catch (e: Exception) {
                    }
                }
            }

            var dndState = "off"
            if (dnd) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    if (prevFilter < 0) prevFilter = nm.currentInterruptionFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    dndState = "on"
                } else {
                    dndState = "needs"
                }
            }

            try {
                Thread.sleep(700)
            } catch (e: Exception) {
            }
            val freed = (availMem() - before) / 1048576

            var launched = false
            if (pkg.isNotEmpty()) {
                val li = packageManager.getLaunchIntentForPackage(pkg)
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runOnUiThread {
                        try {
                            startActivity(li)
                        } catch (e: Exception) {
                        }
                    }
                    launched = true
                }
            }

            result.put("cleaned", cleaned)
            result.put("freedMB", if (freed > 0) freed else 0)
            result.put("dnd", dndState)
            result.put("launched", launched)
            return result.toString()
        }

        @JavascriptInterface
        fun openSettings(name: String) {
            when (name) {
                "display" -> openSettings(Settings.ACTION_DISPLAY_SETTINGS)
                "battery" -> openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "batteryopt" -> openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                "dev" -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                "dnd" -> openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                else -> openSettings(Settings.ACTION_SETTINGS)
            }
        }
    }
}
