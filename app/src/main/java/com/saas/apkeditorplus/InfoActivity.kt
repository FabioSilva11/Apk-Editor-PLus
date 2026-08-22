package com.saas.apkeditorplus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import com.saas.apkeditorplus.ui.info.InfoScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme

class InfoActivity : BaseActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var commits by mutableStateOf<List<GitHubCommit>>(emptyList())
    private var loading by mutableStateOf(true)
    private var errorText by mutableStateOf<String?>(null)

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContent {
            ApkEditorTheme {
                InfoScreen(
                    commits = commits,
                    loading = loading,
                    error = errorText,
                    onBack = ::finish,
                    onRetry = ::fetchCommits,
                    onOpenCommit = { commit ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FabioSilva11/Apk-Editor-PLus/commit/${commit.sha}")))
                    }
                )
            }
        }
        fetchCommits()
    }

    private fun fetchCommits() {
        loading = true
        errorText = null
        
        executor.execute {
            try {
                val url = URL("https://api.github.com/repos/FabioSilva11/Apk-Editor-PLus/commits?per_page=10")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val listType = object : TypeToken<List<GitHubCommit>>() {}.type
                    val commits: List<GitHubCommit> = Gson().fromJson(response, listType)

                    mainHandler.post {
                        loading = false
                        this.commits = commits
                    }
                } else {
                    mainHandler.post {
                        loading = false
                        errorText = getString(R.string.error_loading_commits, connection.responseCode)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    loading = false
                    errorText = getString(R.string.connection_error)
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }


    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
