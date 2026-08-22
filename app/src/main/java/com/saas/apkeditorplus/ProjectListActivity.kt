package com.saas.apkeditorplus

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.projects.ProjectListScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme

class ProjectListActivity : BaseActivity() {
    private lateinit var store: ProjectStore
    private var projects by mutableStateOf<List<ProjectStore.Project>>(emptyList())

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        store = ProjectStore(this)
        projects = store.list()
        setContent {
            ApkEditorTheme {
                ProjectListScreen(
                    projects = projects,
                    onBack = ::finish,
                    onOpen = ::openProject,
                    onDelete = { project ->
                        if (store.delete(project.id)) projects = store.list()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) projects = store.list()
    }

    private fun openProject(project: ProjectStore.Project) {
        val changes = Bundle().apply {
            project.modifiedFiles.forEach(::putString)
        }
        startActivity(Intent(this, FullEditActivity::class.java).apply {
            putExtra("apkPath", project.apkPath)
            putExtra("modifiedFiles", changes)
            putStringArrayListExtra("deletedEntries", ArrayList(project.deletedEntries))
        })
    }
}
