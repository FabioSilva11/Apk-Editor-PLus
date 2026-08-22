package com.saas.apkeditorplus

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.*

data class AppInfo(
    val name: String,
    val packageName: String,
    val sourceDir: String,
    var icon: Drawable? = null,
    val lastModified: Long = 0L
)

class AppAdapter(private val context: Context) : BaseAdapter() {

    private var apps: List<AppInfo> = listOf()
    private val packageManager: PackageManager = context.packageManager
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val iconCache = LruCache<String, Drawable>(64)

    fun setApps(newApps: List<AppInfo>) {
        apps = newApps.sortedBy { it.name.lowercase(Locale.getDefault()) }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = apps.size

    override fun getItem(position: Int): AppInfo = apps[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View = convertView ?: inflater.inflate(R.layout.item_applist, parent, false)
        val iconView: ImageView = view.findViewById(R.id.app_icon)
        val nameView: TextView = view.findViewById(R.id.app_name)
        val packageView: TextView = view.findViewById(R.id.app_desc1)

        val app = getItem(position)
        nameView.text = app.name
        packageView.text = app.packageName

        // Carregamento de ícone com cache
        val cachedIcon = iconCache.get(app.packageName)
        if (cachedIcon != null) {
            iconView.setImageDrawable(cachedIcon)
        } else {
            // Em uma thread separada seria melhor, mas seguiremos o fluxo original com cache
            try {
                // Tentando obter o ícone "real" do arquivo APK no disco
                val archiveInfo = packageManager.getPackageArchiveInfo(app.sourceDir, 0)
                val appInfo = archiveInfo?.applicationInfo
                if (appInfo != null) {
                    appInfo.sourceDir = app.sourceDir
                    appInfo.publicSourceDir = app.sourceDir
                    val icon = appInfo.loadIcon(packageManager)
                    iconCache.put(app.packageName, icon)
                    iconView.setImageDrawable(icon)
                } else {
                    // Fallback para o ícone do sistema se não conseguir ler o arquivo
                    val icon = packageManager.getApplicationIcon(app.packageName)
                    iconCache.put(app.packageName, icon)
                    iconView.setImageDrawable(icon)
                }
            } catch (e: Exception) {
                try {
                    val icon = packageManager.getApplicationIcon(app.packageName)
                    iconCache.put(app.packageName, icon)
                    iconView.setImageDrawable(icon)
                } catch (e2: Exception) {
                    iconView.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }

        return view
    }
}
