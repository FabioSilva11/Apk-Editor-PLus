package com.saas.apkeditorplus.full

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.saas.apkeditorplus.FullEditActivity
import com.saas.apkeditorplus.ui.full.DiffScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiffFragment : Fragment() {
    private var diffs by mutableStateOf<List<FileDiff>>(emptyList())
    private var loading by mutableStateOf(false)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val revision = host().changesRevision
                LaunchedEffect(revision) {
                    loading = true
                    val snapshot = host().pendingChangesSnapshot()
                    diffs = withContext(Dispatchers.IO) {
                        DiffRepository.build(apkPath(), snapshot)
                    }
                    loading = false
                }
                ApkEditorTheme {
                    DiffScreen(
                        diffs = diffs,
                        loading = loading,
                        onDiscard = host()::discardModifiedEntry
                    )
                }
            }
        }

    private fun host(): FullEditActivity = requireActivity() as FullEditActivity
    private fun apkPath(): String = requireArguments().getString(ARG_APK_PATH).orEmpty()

    companion object {
        private const val ARG_APK_PATH = "apk_path"
        fun newInstance(apkPath: String) = DiffFragment().apply {
            arguments = Bundle().apply { putString(ARG_APK_PATH, apkPath) }
        }
    }
}
