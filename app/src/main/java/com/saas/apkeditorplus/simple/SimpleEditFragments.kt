package com.saas.apkeditorplus.simple

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.saas.apkeditorplus.SimpleEditActivity
import com.saas.apkeditorplus.ui.simple.SimpleArchiveEntry
import com.saas.apkeditorplus.ui.simple.SimpleEditPage
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme

abstract class SimpleEditPageFragment : Fragment() {
    protected abstract val tabIndex: Int
    private var currentPath by mutableStateOf("")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ApkEditorTheme { PageContent() } }
        }

    @Composable
    private fun PageContent() {
        val activity = requireActivity() as SimpleEditActivity
        activity.entriesRevision
        val entries = if (tabIndex == 0) activity.childrenForPath(currentPath) else activity.entriesForTab(tabIndex)
        SimpleEditPage(
            path = if (tabIndex == 0) currentPath else "",
            loading = entries.isEmpty() && activity.entriesRevision == 0,
            entries = entries,
            modifiedEntries = activity.modifiedNames,
            playingEntryName = activity.playingEntryName,
            onNavigateUp = if (tabIndex == 0 && currentPath.isNotBlank()) ({ navigateUp() }) else null,
            onOpen = { entry -> open(activity, entry) },
            onReplace = activity::chooseReplacement,
            onExport = activity::chooseExport,
            onClearReplacement = activity::clearReplacement,
            thumbnailLoader = activity::loadThumbnail
        )
    }

    private fun open(activity: SimpleEditActivity, entry: SimpleArchiveEntry) {
        if (tabIndex == 0 && entry.isDirectory) {
            currentPath = entry.entryName
        } else {
            activity.openEntry(entry, tabIndex)
        }
    }

    private fun navigateUp() {
        val trimmed = currentPath.trimEnd('/')
        currentPath = trimmed.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }
    }
}

class SimpleFilesFragment : SimpleEditPageFragment() {
    override val tabIndex: Int = 0
}

class SimpleImagesFragment : SimpleEditPageFragment() {
    override val tabIndex: Int = 1
}

class SimpleAudioFragment : SimpleEditPageFragment() {
    override val tabIndex: Int = 2
}
