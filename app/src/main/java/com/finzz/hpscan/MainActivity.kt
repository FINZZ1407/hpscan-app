package com.finzz.hpscan

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    data class Item(
        val label: String,
        val pkg: String,
        val score: Int,
        val level: String,
        val reasons: List<String>,
        val icon: Drawable?
    )

    private val items = mutableListOf<Item>()
    private lateinit var summary: TextView
    private lateinit var sysBox: CheckBox
    private lateinit var list: ListView
    private lateinit var adapter: BaseAdapter

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val risky = mapOf(
        "ACCESS_BACKGROUND_LOCATION" to 3, "ACCESS_FINE_LOCATION" to 1,
        "RECORD_AUDIO" to 2, "CAMERA" to 1, "READ_SMS" to 2, "RECEIVE_SMS" to 2,
        "READ_CALL_LOG" to 2, "READ_CONTACTS" to 1, "SYSTEM_ALERT_WINDOW" to 2,
        "REQUEST_INSTALL_PACKAGES" to 1, "QUERY_ALL_PACKAGES" to 1,
        "RECEIVE_BOOT_COMPLETED" to 1, "PACKAGE_USAGE_STATS" to 2
    )
    private val trustedStores = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.xiaomi.mipicks",
        "com.huawei.appmarket", "com.heytap.market", "com.oppo.market",
        "com.vivo.appstore", "com.transsnet.store", "com.amazon.venezia"
    )
    private val fakeName = Regex(
        "(\\.sys\\.|\\.system|\\.service|\\.update|\\.sync|\\.helper|\\.wifi|\\.battery)",
        RegexOption.IGNORE_CASE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        root.addView(TextView(this).apply {
            text = "HP Scanner"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(null, Typeface.BOLD)
        })
        summary = TextView(this).apply {
            setTextColor(0xFFB0B0B0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(summary)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sysBox = CheckBox(this).apply {
            text = "Sertakan aplikasi sistem"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, _ -> scan() }
        }
        bar.addView(sysBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Pindai ulang"
            setOnClickListener { scan() }
        })
        root.addView(bar)

        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View = row(items[p])
        }
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + items[pos].pkg)
                    )
                )
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        scan()
    }

    private fun levelColor(level: String) = when (level) {
        "TINGGI" -> 0xFFFF5252.toInt()
        "SEDANG" -> 0xFFFFC107.toInt()
        else -> 0xFF66BB6A.toInt()
    }

    private fun row(it: Item): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        r.addView(ImageView(this).apply { setImageDrawable(it.icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        col.addView(TextView(this).apply {
            text = "[${it.level}] ${it.score}  ${it.label}"
            setTextColor(levelColor(it.level))
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        col.addView(TextView(this).apply {
            text = it.pkg
            setTextColor(0xFF808080.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        col.addView(TextView(this).apply {
            text = if (it.reasons.isEmpty()) "Tidak ada tanda mencurigakan"
            else it.reasons.joinToString("\n• ", "• ")
            setTextColor(0xFFD0D0D0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return r
    }

    private fun scan() {
        summary.text = "Memindai..."
        val includeSystem = sysBox.isChecked
        Thread {
            val res = try { doScan(includeSystem) } catch (e: Exception) { null }
            runOnUiThread {
                items.clear()
                if (res == null) {
                    summary.text = "Gagal memindai. Coba lagi."
                } else {
                    items.addAll(res.first)
                    summary.text = res.second
                }
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun doScan(includeSystem: Boolean): Pair<List<Item>, String> {
        val pm = packageManager
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33)
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        else pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        val launcher = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.toSet()

        val am = getSystemService(AccessibilityManager::class.java)
        val acc = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { it.resolveInfo.serviceInfo.packageName }.toSet()
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admins = (dpm.activeAdmins ?: emptyList<ComponentName>()).map { it.packageName }.toSet()
        val nl = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "")
            .split(":").mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()

        val out = mutableListOf<Item>()
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem && !includeSystem) continue
            if (pi.packageName == packageName) continue

            val granted = mutableSetOf<String>()
            pi.requestedPermissions?.forEachIndexed { i, p ->
                val f = pi.requestedPermissionsFlags?.getOrNull(i) ?: 0
                if ((f and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0)
                    granted.add(p.removePrefix("android.permission."))
            }

            var score = 0
            val why = mutableListOf<String>()
            for ((p, w) in risky) if (p in granted) { score += w; why.add(p.lowercase()) }
            if (pi.packageName in acc) { score += 5; why.add("LAYANAN AKSESIBILITAS AKTIF") }
            if (pi.packageName in nl) { score += 4; why.add("membaca notifikasi") }
            if (pi.packageName in admins) { score += 5; why.add("ADMIN PERANGKAT") }
            if (!isSystem && pi.packageName !in launcher) { score += 4; why.add("TANPA IKON (tersembunyi)") }

            val inst: String? = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pi.packageName).installingPackageName
                else pm.getInstallerPackageName(pi.packageName)
            } catch (e: Exception) { null }
            if (!isSystem && inst !in trustedStores) {
                score += 2
                why.add("sideload (${inst ?: "sumber tidak diketahui"})")
            }
            if (!isSystem && fakeName.containsMatchIn(pi.packageName)) { score += 2; why.add("nama menyerupai sistem") }

            val level = if (score >= 12) "TINGGI" else if (score >= 6) "SEDANG" else "RENDAH"
            out.add(Item(pm.getApplicationLabel(ai).toString(), pi.packageName, score, level, why, pm.getApplicationIcon(ai)))
        }
        out.sortWith(compareByDescending<Item> { it.score }.thenBy { it.label.lowercase() })

        val txt = "${out.size} aplikasi • ${out.count { it.level == "TINGGI" }} tinggi • " +
                "${out.count { it.level == "SEDANG" }} sedang\n" +
                "Aksesibilitas aktif: ${if (acc.isEmpty()) "tidak ada" else acc.joinToString()}\n" +
                "Ketuk aplikasi untuk membuka pengaturan / mencopot."
        return Pair(out, txt)
    }
}
