package com.tanix.myhome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class Kind { APP, ALL_APPS, BACK }

data class AppItem(
    val label: String,
    val pkg: String?,
    val launch: Intent?,
    val icon: Drawable?,
    val kind: Kind = Kind.APP
)

class MainActivity : Activity() {

    private lateinit var dateView: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: AppAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var showAll = false

    private val tick = object : Runnable {
        override fun run() {
            updateDate()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dateView = findViewById(R.id.date)
        settingsBtn = findViewById(R.id.settings)
        list = findViewById(R.id.list)

        adapter = AppAdapter(::onItemClick, ::onItemLongClick)
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.adapter = adapter
        list.itemAnimator = null

        settingsBtn.setOnClickListener { openSettings() }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // Home натиснуто, коли оболонка вже відкрита: повертаємось на головний ряд.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (showAll) {
            showAll = false
            loadApps()
        } else {
            list.scrollToPosition(0)
            list.post { focusFirst() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showAll) {
            showAll = false
            loadApps()
        }
        // На головному екрані «Назад» нічого не робить.
    }

    private fun updateDate() {
        val uk = Locale("uk", "UA")
        val text = SimpleDateFormat("EEEE, d MMMM", uk).format(Calendar.getInstance().time)
        dateView.text = text.replaceFirstChar { it.titlecase(uk) }
    }

    // ---------- Список програм ----------

    private fun loadApps() {
        adapter.submit(if (showAll) buildAll() else buildMain())
        list.post { focusFirst() }
    }

    private fun focusFirst() {
        list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
    }

    private fun query(category: String): List<Pair<ResolveInfo, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return packageManager.queryIntentActivities(intent, 0).map { it to category }
    }

    private fun toItem(ri: ResolveInfo, category: String): AppItem {
        val ai = ri.activityInfo
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setClassName(ai.packageName, ai.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return AppItem(
            label = ri.loadLabel(packageManager).toString(),
            pkg = ai.packageName,
            launch = launch,
            icon = ri.loadIcon(packageManager)
        )
    }

    private fun isHidden(pkg: String): Boolean {
        if (pkg == packageName) return true
        return resources.getStringArray(R.array.hidden_packages).any { it == pkg }
    }

    private fun pinnedIndex(pkg: String): Int {
        val pinned = resources.getStringArray(R.array.pinned_packages)
        val idx = pinned.indexOfFirst { pkg == it || pkg.startsWith("$it.") }
        return if (idx >= 0) idx else Int.MAX_VALUE
    }

    private fun special(label: String, kind: Kind, iconRes: Int) =
        AppItem(label, null, null, resources.getDrawable(iconRes, theme), kind)

    private fun buildMain(): List<AppItem> {
        val seen = HashSet<String>()
        val result = ArrayList<AppItem>()

        var found = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        if (found.isEmpty()) found = query(Intent.CATEGORY_LAUNCHER)
        for ((ri, cat) in found) {
            val pkg = ri.activityInfo.packageName
            if (isHidden(pkg) || !seen.add(pkg)) continue
            result += toItem(ri, cat)
        }

        // Закріплені програми без Leanback-версії теж показуємо на головному екрані.
        for ((ri, cat) in query(Intent.CATEGORY_LAUNCHER)) {
            val pkg = ri.activityInfo.packageName
            if (isHidden(pkg) || pinnedIndex(pkg) == Int.MAX_VALUE || !seen.add(pkg)) continue
            result += toItem(ri, cat)
        }

        result.sortWith(
            compareBy<AppItem> { pinnedIndex(it.pkg.orEmpty()) }
                .thenBy { it.label.lowercase() }
        )
        result += special(getString(R.string.all_apps), Kind.ALL_APPS, R.drawable.ic_grid)
        return result
    }

    private fun buildAll(): List<AppItem> {
        val seen = HashSet<String>()
        val result = ArrayList<AppItem>()
        val found = query(Intent.CATEGORY_LAUNCHER) + query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        for ((ri, cat) in found) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            result += toItem(ri, cat)
        }
        result.sortBy { it.label.lowercase() }
        result.add(0, special(getString(R.string.back), Kind.BACK, R.drawable.ic_back))
        return result
    }

    // ---------- Дії ----------

    private fun onItemClick(item: AppItem) {
        when (item.kind) {
            Kind.ALL_APPS -> { showAll = true; loadApps() }
            Kind.BACK -> { showAll = false; loadApps() }
            Kind.APP -> try {
                startActivity(item.launch)
            } catch (e: ActivityNotFoundException) {
                toast(R.string.cannot_open)
            } catch (e: SecurityException) {
                toast(R.string.cannot_open)
            }
        }
    }

    // Довге натискання OK відкриває налаштування програми (видалення, очищення кешу).
    private fun onItemLongClick(item: AppItem) {
        val pkg = item.pkg ?: return
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            toast(R.string.cannot_open)
        }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.cannot_open)
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}

class AppAdapter(
    private val onClick: (AppItem) -> Unit,
    private val onLongClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppItem> = emptyList()

    fun submit(newItems: List<AppItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tile: View = view.findViewById(R.id.tile)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val shine: View = view.findViewById(R.id.shine)
        private val label: TextView = view.findViewById(R.id.label)

        fun bind(item: AppItem) {
            icon.setImageDrawable(item.icon)
            label.text = item.label

            // Початковий (не вибраний) стан, потрібен, бо плитки перевикористовуються.
            tile.scaleX = 1f
            tile.scaleY = 1f
            tile.translationZ = 0f
            shine.alpha = 0f
            label.alpha = 0f

            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener { onLongClick(item); true }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val s = if (hasFocus) 1.16f else 1f
                tile.animate().scaleX(s).scaleY(s)
                    .translationZ(if (hasFocus) 24f else 0f)
                    .setDuration(160).start()
                shine.animate().alpha(if (hasFocus) 1f else 0f).setDuration(160).start()
                label.animate().alpha(if (hasFocus) 1f else 0f).setDuration(160).start()
            }
        }
    }
}
