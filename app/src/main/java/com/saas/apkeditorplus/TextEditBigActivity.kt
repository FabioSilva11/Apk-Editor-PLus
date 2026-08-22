package com.saas.apkeditorplus

import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.editor.TextEditorScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.SymbolInputView
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.subscribeAlways
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File
import java.util.regex.PatternSyntaxException

class TextEditBigActivity : BaseActivity() {
    private lateinit var editor: CodeEditor
    private lateinit var symbolInput: SymbolInputView
    private var filePath: String? = null
    private var originalText: String? = null
    private var fileName by mutableStateOf("")
    private var searchVisible by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    private var replacement by mutableStateOf("")
    private var positionText by mutableStateOf("")
    private var canUndo by mutableStateOf(false)
    private var canRedo by mutableStateOf(false)
    private var wordWrap by mutableStateOf(false)
    private var lineNumbers by mutableStateOf(true)
    private var regex by mutableStateOf(false)
    private var matchCase by mutableStateOf(true)
    private var wholeWord by mutableStateOf(false)
    private var showSymbolInput = true
    private var largeFile = false

    companion object {
        private const val REQUEST_EXTERNAL_EDITOR = 7104
        val SYMBOLS = arrayOf("->", "{", "}", "(", ")", ",", ".", ";", "\"", "?", "+", "-", "*", "/", "<", ">", "[", "]", ":")
        val SYMBOL_INSERT_TEXT = arrayOf("\t", "{}", "}", "(", ")", ",", ".", ";", "\"", "?", "+", "-", "*", "/", "<", ">", "[", "]", ":")
    }

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePath = intent.getStringExtra("filePath")
        fileName = intent.getStringExtra("fileName") ?: getString(R.string.unnamed)
        val bigFileLimit = prefs.getInt(AppSettings.EDITOR_BIG_FILE_KB, 64).coerceAtLeast(16) * 1024L
        largeFile = filePath?.let(::File)?.length()?.let { it > bigFileLimit } == true
        editor = CodeEditor(this)
        symbolInput = SymbolInputView(this).apply {
            bindEditor(editor)
            addSymbols(SYMBOLS, SYMBOL_INSERT_TEXT)
        }
        lineNumbers = prefs.getBoolean(AppSettings.EDITOR_LINE_NUMBERS, true)
        wordWrap = prefs.getBoolean(AppSettings.EDITOR_WORD_WRAP, true) && !largeFile
        showSymbolInput = prefs.getBoolean(AppSettings.EDITOR_SYMBOL_INPUT, true)
        setupEditor()
        loadFile()

        setContent {
            ApkEditorTheme {
                TextEditorScreen(
                    fileName, editor, symbolInput, showSymbolInput, searchVisible, searchQuery, replacement,
                    positionText, canUndo, canRedo, wordWrap, lineNumbers, regex, matchCase, wholeWord,
                    onBack = ::handleBack,
                    onSave = ::saveFile,
                    onUndo = { editor.undo() },
                    onRedo = { editor.redo() },
                    onToggleSearch = { searchVisible = !searchVisible; if (!searchVisible) editor.searcher.stopSearch() },
                    onSearchChange = { searchQuery = it; updateSearch() },
                    onReplacementChange = { replacement = it },
                    onPrevious = { editor.searcher.gotoPrevious() },
                    onNext = { editor.searcher.gotoNext() },
                    onReplace = { if (searchQuery.isNotEmpty()) editor.searcher.replaceCurrentMatch(replacement) },
                    onReplaceAll = { if (searchQuery.isNotEmpty()) editor.searcher.replaceAll(replacement) },
                    onWordWrapChange = { wordWrap = it; editor.isWordwrap = it; prefs.edit().putBoolean(AppSettings.EDITOR_WORD_WRAP, it).apply() },
                    onLineNumbersChange = { lineNumbers = it; editor.isLineNumberEnabled = it; prefs.edit().putBoolean(AppSettings.EDITOR_LINE_NUMBERS, it).apply() },
                    onRegexChange = { regex = it; updateSearch() },
                    onMatchCaseChange = { matchCase = it; updateSearch() },
                    onWholeWordChange = { wholeWord = it; updateSearch() },
                    onChooseLanguage = ::chooseLanguage,
                    onChooseTheme = ::chooseTheme
                )
            }
        }
        if (prefs.getBoolean(AppSettings.EXTERNAL_EDITOR, false)) {
            tryLaunchExternalEditor()
        }
    }

    private fun searchOptions(): EditorSearcher.SearchOptions {
        val type = when { regex -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION; wholeWord -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD; else -> EditorSearcher.SearchOptions.TYPE_NORMAL }
        return EditorSearcher.SearchOptions(type, !matchCase, RegexBackrefGrammar.DEFAULT)
    }

    private fun updateSearch() {
        if (searchQuery.isBlank()) editor.searcher.stopSearch()
        else runCatching { editor.searcher.search(searchQuery, searchOptions()) }
            .onFailure { if (it !is PatternSyntaxException) it.printStackTrace() }
    }

    private fun updatePositionText() {
        val cursor = editor.cursor
        val content = editor.text
        val line = cursor.leftLine
        val column = cursor.leftColumn
        positionText = buildString {
            append("${line + 1}:$column;${cursor.left} ")
            if (cursor.isSelected) append("(${cursor.right - cursor.left} caracteres)")
            else if (content.getColumnCount(line) == column) {
                val separator = content.getLine(line).lineSeparator
                append("(<${if (separator == LineSeparator.NONE) "EOF" else separator.name}>)")
            } else append("(${content.getLine(line).codePointStringAt(column).escapeCodePointIfNecessary()})")
            if (editor.searcher.hasQuery()) {
                val count = editor.searcher.matchedPositionCount
                val index = editor.searcher.currentMatchedPositionIndex
                append(if (count == 0) " (sem resultados)" else " (${index + 1} de $count)")
            }
        }
    }

    private fun updateUndoRedo() {
        canUndo = editor.canUndo()
        canRedo = editor.canRedo()
    }

    private fun setupEditor() {
        editor.setTextSize(prefs.getInt(AppSettings.EDITOR_FONT_SIZE, 14).toFloat())
        editor.isLineNumberEnabled = lineNumbers
        editor.isWordwrap = wordWrap
        editor.subscribeAlways<SelectionChangeEvent> { updatePositionText() }
        editor.subscribeAlways<PublishSearchResultEvent> { updatePositionText() }
        editor.subscribeAlways<ContentChangeEvent> { updatePositionText(); updateUndoRedo() }
        if (largeFile) return
        runCatching {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            loadTextMateTheme("darcula", "darcula.json", true)
            loadTextMateTheme("light", "light.json", false)
            loadTextMateTheme("ayu-dark", "ayu-dark.json", true)
            loadTextMateTheme("quietlight", "quietlight.json", false)
            loadTextMateTheme("solarized_dark", "solarized_dark.json", true)
            loadGrammar("xml", "text.xml", "xml.json")
            loadGrammar("java", "source.java", "java.json")
            loadGrammar("smali", "source.smali", "smali.json")
            val scope = when (filePath?.substringAfterLast('.', "")?.lowercase()) {
                "java" -> "source.java"
                "smali" -> "source.smali"
                else -> "text.xml"
            }
            editor.setEditorLanguage(TextMateLanguage.create(scope, true))
            ThemeRegistry.getInstance().setTheme(prefs.getString(AppSettings.EDITOR_THEME, "light") ?: "light")
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
            applyCustomColors()
        }
    }

    private fun applyCustomColors() {
        if (!prefs.getBoolean(AppSettings.EDITOR_CUSTOM_COLORS, false)) return
        val scheme = editor.colorScheme
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, prefs.getInt(AppSettings.EDITOR_BACKGROUND, 0xFF002B36.toInt()))
        val lineColor = prefs.getInt(AppSettings.EDITOR_LINE_COLOR, 0xFFFDF6E3.toInt())
        scheme.setColor(EditorColorScheme.LINE_NUMBER, lineColor)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, lineColor)
        val syntaxIds = intArrayOf(
            EditorColorScheme.KEYWORD, EditorColorScheme.LITERAL, EditorColorScheme.COMMENT,
            EditorColorScheme.OPERATOR, EditorColorScheme.FUNCTION_NAME, EditorColorScheme.IDENTIFIER_NAME,
            EditorColorScheme.ATTRIBUTE_NAME, EditorColorScheme.ATTRIBUTE_VALUE, EditorColorScheme.HTML_TAG
        )
        syntaxIds.forEachIndexed { index, id ->
            scheme.setColor(id, prefs.getInt("editor_syntax_${index + 1}", SettingActivity.DEFAULT_SYNTAX_COLORS[index]))
        }
    }

    private fun loadTextMateTheme(name: String, file: String, dark: Boolean) {
        ThemeRegistry.getInstance().loadTheme(
            ThemeModel(IThemeSource.fromInputStream(assets.open("textmate/$file"), file, null), name).apply { isDark = dark }
        )
    }

    private fun loadGrammar(name: String, scope: String, file: String) {
        GrammarRegistry.getInstance().loadGrammar(
            DefaultGrammarDefinition.withGrammarSource(
                IGrammarSource.fromInputStream(assets.open("textmate/$file"), file, null), name, scope
            )
        )
    }

    private fun chooseLanguage() {
        val labels = arrayOf("XML", "Java", "Smali", "Sem destaque")
        AlertDialog.Builder(this).setTitle(R.string.switch_language).setItems(labels) { _, index ->
            val scope = listOf("text.xml", "source.java", "source.smali", null)[index]
            editor.setEditorLanguage(scope?.let { TextMateLanguage.create(it, true) })
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
            applyCustomColors()
        }.show()
    }

    private fun chooseTheme() {
        val labels = arrayOf("Darcula", "Light", "Ayu Dark", "Quiet Light", "Solarized Dark")
        val values = arrayOf("darcula", "light", "ayu-dark", "quietlight", "solarized_dark")
        AlertDialog.Builder(this).setTitle(R.string.switch_color_scheme).setItems(labels) { _, index ->
            ThemeRegistry.getInstance().setTheme(values[index])
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
            applyCustomColors()
            prefs.edit().putString(AppSettings.EDITOR_THEME, values[index]).apply()
        }.show()
    }

    private fun tryLaunchExternalEditor() {
        val file = filePath?.let(::File)?.takeIf { it.isFile } ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull() ?: return
        val extension = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "text/plain"
        val editIntent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (editIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(editIntent, REQUEST_EXTERNAL_EDITOR)
        } else {
            Toast.makeText(this, "Nenhum editor externo compatível foi encontrado.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Compatibilidade com editores externos")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXTERNAL_EDITOR) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun loadFile() {
        filePath?.let { path ->
            File(path).takeIf { it.isFile }?.readText()?.let { content ->
                editor.setText(content)
                originalText = content
                updatePositionText()
                updateUndoRedo()
            }
        }
    }

    private fun saveFile() {
        val path = filePath ?: return
        runCatching {
            val current = editor.text.toString()
            File(path).writeText(current)
            originalText = current
            setResult(RESULT_OK)
            Toast.makeText(this, R.string.file_saved_toast, Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, getString(R.string.error_saving, it.message), Toast.LENGTH_SHORT).show() }
    }

    private fun handleBack() {
        if (originalText == editor.text.toString()) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_changes)
            .setMessage(R.string.unsaved_changes_msg)
            .setPositiveButton(R.string.save) { _, _ -> saveFile(); finish() }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(R.string.colormixer_cancel, null)
            .show()
    }


    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() = handleBack()

    override fun onDestroy() {
        editor.release()
        super.onDestroy()
    }
}

fun CharSequence.codePointStringAt(index: Int): String = String(Character.toChars(Character.codePointAt(this, index)))

fun String.escapeCodePointIfNecessary() = when (this) {
    "\n" -> "\\n"; "\t" -> "\\t"; "\r" -> "\\r"; " " -> "<ws>"; else -> this
}
