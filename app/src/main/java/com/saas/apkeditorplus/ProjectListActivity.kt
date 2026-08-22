package com.saas.apkeditorplus

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.saas.apkeditorplus.ui.projects.ProjectListScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectListActivity : BaseActivity() {
    private lateinit var store: ProjectStore
    private var projects by mutableStateOf<List<ProjectStore.Project>>(emptyList())
    private var pendingRelink: ProjectStore.Project? = null

    private val sourceLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val project = pendingRelink.also { pendingRelink = null } ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val sources = File(filesDir, "project_sources").apply { mkdirs() }
                    val selected = File(sources, "${project.id}.apk.tmp")
                    contentResolver.openInputStream(uri)?.use { input ->
                        selected.outputStream().buffered().use(input::copyTo)
                    } ?: error("Não foi possível ler o APK selecionado")
                    store.validateSourceCandidate(project.id, selected.absolutePath)
                    val stable = File(sources, "${project.id}.apk")
                    selected.copyTo(stable, overwrite = true)
                    store.relinkSource(project.id, stable.absolutePath)
                }
            }
            result.onSuccess { restored ->
                projects = store.list()
                launchEditor(restored)
            }.onFailure { error ->
                Toast.makeText(this@ProjectListActivity, error.message ?: "Falha ao recuperar projeto", Toast.LENGTH_LONG).show()
            }.also {
                File(filesDir, "project_sources/${project.id}.apk.tmp").delete()
            }
        }
    }

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
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { store.verifySource(project) }
            when (status) {
                ProjectStore.SourceStatus.VALID -> launchEditor(project)
                ProjectStore.SourceStatus.UNVERIFIED -> showLegacyConfirmation(project)
                ProjectStore.SourceStatus.MISSING,
                ProjectStore.SourceStatus.CHANGED -> showRelinkDialog(project, status)
            }
        }
    }

    private fun launchEditor(project: ProjectStore.Project) {
        val changes = Bundle().apply {
            project.modifiedFiles.forEach(::putString)
        }
        startActivity(Intent(this, FullEditActivity::class.java).apply {
            putExtra("apkPath", project.apkPath)
            putExtra("projectId", project.id)
            putExtra("modifiedFiles", changes)
            putStringArrayListExtra("deletedEntries", ArrayList(project.deletedEntries))
        })
    }

    private fun showLegacyConfirmation(project: ProjectStore.Project) {
        AlertDialog.Builder(this)
            .setTitle("Validar APK original?")
            .setMessage("Este projeto foi salvo por uma versão antiga. Confirme o APK atual para protegê-lo contra trocas futuras.")
            .setPositiveButton("Validar e abrir") { _, _ ->
                lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { store.adoptCurrentSource(project.id) } }
                        .onSuccess { launchEditor(it) }
                        .onFailure { Toast.makeText(this@ProjectListActivity, it.message, Toast.LENGTH_LONG).show() }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showRelinkDialog(project: ProjectStore.Project, status: ProjectStore.SourceStatus) {
        val reason = if (status == ProjectStore.SourceStatus.MISSING) {
            "O APK original não está mais no local salvo."
        } else {
            "O arquivo de origem foi alterado e não corresponde mais ao APK deste projeto."
        }
        AlertDialog.Builder(this)
            .setTitle("APK original necessário")
            .setMessage("$reason Selecione novamente o mesmo APK original para preservar todas as edições.")
            .setPositiveButton("Selecionar APK") { _, _ ->
                pendingRelink = project
                sourceLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
