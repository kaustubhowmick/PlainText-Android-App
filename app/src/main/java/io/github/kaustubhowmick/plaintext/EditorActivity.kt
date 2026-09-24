package io.github.kaustubhowmick.plaintext

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.kaustubhowmick.plaintext.editor.AccessMode
import io.github.kaustubhowmick.plaintext.editor.DiskStamp
import io.github.kaustubhowmick.plaintext.editor.Document
import io.github.kaustubhowmick.plaintext.editor.EditorView
import io.github.kaustubhowmick.plaintext.editor.Origin
import io.github.kaustubhowmick.plaintext.io.Encoding
import io.github.kaustubhowmick.plaintext.io.LineEnding
import io.github.kaustubhowmick.plaintext.storage.RecoveryStore
import io.github.kaustubhowmick.plaintext.storage.Settings
import java.text.DateFormat
import java.util.Date

/**
 * The only activity (design.md §3.2): owns the views, menus, dialogs, shortcuts,
 * back handling, and the unsaved-changes flow. Long-lived state lives in
 * [EditorSession], which survives configuration changes.
 */
class EditorActivity : Activity(), EditorView.Listener {

    private lateinit var settings: Settings
    private lateinit var recovery: RecoveryStore
    private lateinit var session: EditorSession
    private lateinit var editor: EditorView
    private lateinit var titleView: TextView
    private lateinit var progress: ProgressBar

    private val doc: Document get() = session.doc
    private val isDirty: Boolean get() = session.undo.isDirty || doc.metadataDirty

    /** True while text is set programmatically (load/restore): not an edit. */
    private var loading = false
    private var pendingRemoved = ""
    private var pendingCaret = 0

    private var activeDialog: Dialog? = null
    private var exiting = false
    private var lastTitle: String? = null
    private var backCallback: Any? = null
    private var backRegistered = false

    private val recoveryRunnable = Runnable { writeRecovery() }
    private val showProgressRunnable = Runnable { progress.visibility = View.VISIBLE }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        recovery = RecoveryStore(noBackupFilesDir)

        setContentView(R.layout.activity_editor)
        setActionBar(findViewById<Toolbar>(R.id.toolbar))
        actionBar?.setDisplayShowTitleEnabled(false)
        titleView = findViewById(R.id.title_view)
        progress = findViewById(R.id.progress)
        editor = findViewById(R.id.editor)
        editor.listener = this
        editor.addTextChangedListener(watcher)
        applyEdgeToEdge(findViewById(R.id.root))
        applyFont()
        applyWordWrap()

        @Suppress("DEPRECATION")
        val retained = lastNonConfigurationInstance as? EditorSession
        if (retained != null) {
            session = retained
            restoreRetained()
        } else {
            session = EditorSession(newUntitledDocument())
            session.undo.reset()
            if (savedInstanceState != null) restoreAfterProcessDeath(savedInstanceState) else coldStart()
        }
        updateTitle()
        updateBackCallback()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) writeRecovery()
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        session.work.main.removeCallbacks(recoveryRunnable)
        session.work.main.removeCallbacks(showProgressRunnable)
        if (isFinishing) session.work.shutdown()
        super.onDestroy()
    }

    @Deprecated("Framework API; used to retain the session across recreation.")
    override fun onRetainNonConfigurationInstance(): Any {
        session.retainedText = editor.text.toString()
        session.selStart = editor.selectionStart
        session.selEnd = editor.selectionEnd
        session.scrollX = editor.scrollX
        session.scrollY = editor.scrollY
        return session
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Small data only; the text itself goes to the recovery buffer (design.md §5.29).
        outState.putBoolean("dirty", isDirty)
        outState.putParcelable("uri", doc.uri)
        outState.putString("origin", doc.origin.name)
        outState.putInt("selStart", editor.selectionStart)
        outState.putInt("selEnd", editor.selectionEnd)
        session.pending?.let { outState.putBundle("pending", it.toBundle()) }
    }

    /** Recreation after a configuration change: the session already has everything. */
    private fun restoreRetained() {
        val text = session.retainedText ?: ""
        session.retainedText = null
        setEditorText(text)
        editor.setSelection(session.selStart.coerceIn(0, text.length), session.selEnd.coerceIn(0, text.length))
        val x = session.scrollX
        val y = session.scrollY
        editor.post { editor.scrollTo(x, y) }
    }

    /** The process was killed in the background; bring back what the user left. */
    @Suppress("DEPRECATION")
    private fun restoreAfterProcessDeath(state: Bundle) {
        session.pending = PendingAction.fromBundle(state.getBundle("pending"))
        if (state.getBoolean("dirty") && recovery.exists()) {
            restoreFromRecovery()
        }
    }

    /** A fresh start from the launcher or an intent. */
    private fun coldStart() {
        recovery.deleteIfStale(RECOVERY_MAX_AGE_MS)
        val meta = recovery.readMeta()
        if (meta != null) {
            val edited = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meta.time))
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle(R.string.recover_title)
                    .setMessage(getString(R.string.recover_message, meta.name, edited))
                    .setCancelable(false)
                    .setPositiveButton(R.string.restore) { _, _ ->
                        restoreFromRecovery()
                        handleLaunchIntent()
                    }
                    .setNegativeButton(R.string.discard) { _, _ ->
                        discardRecovery()
                        handleLaunchIntent()
                    }
            )
        } else {
            handleLaunchIntent()
        }
    }

    private fun handleLaunchIntent() {
        // Intents (Open with, Share) and the first-run folder prompt arrive with file I/O.
    }

    // ------------------------------------------------------------ text plumbing

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (loading) return
            pendingRemoved = if (session.undo.isApplying || count == 0) "" else s.subSequence(start, start + count).toString()
            pendingCaret = editor.selectionStart
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (loading) return
            val inserted = s.subSequence(start, start + count)
            session.lines.onReplace(start, before, inserted)
            session.revision++
            if (!session.undo.isApplying) {
                val composing = s is Spannable && BaseInputConnection.getComposingSpanStart(s) >= 0
                session.undo.record(start, pendingRemoved, inserted.toString(), pendingCaret, composing)
            }
            pendingRemoved = ""
        }

        override fun afterTextChanged(s: Editable) {
            if (!loading) onDocumentChanged()
        }
    }

    private fun onDocumentChanged() {
        updateTitle()
        updateBackCallback()
        scheduleRecovery()
        if (!isDirty && session.recoveryWritten) discardRecovery()
    }

    /** Replaces the whole text without recording an edit. */
    private fun setEditorText(text: String) {
        editor.viewOnly = false
        loading = true
        try {
            editor.setText(text)
        } finally {
            loading = false
        }
        session.lines.rebuild(text)
        session.revision++
        session.clearSnapshot()
        editor.applyTabStops()
        editor.viewOnly = doc.access.isViewOnly
    }

    /** Shows [text] as a freshly loaded document described by [newDoc]. */
    private fun loadDocument(text: String, newDoc: Document, caret: Int = 0) {
        session.doc = newDoc
        setEditorText(text)
        session.undo.reset()
        editor.setSelection(caret.coerceIn(0, text.length))
        editor.scrollTo(0, 0)
        updateTitle()
        updateBackCallback()
    }

    private fun newUntitledDocument() = Document(
        displayName = getString(R.string.untitled),
        encoding = settings.defaultEncoding,
        lineEnding = settings.defaultLineEnding,
    )

    // ---------------------------------------------------------- EditorView.Listener

    override fun onSelectionChanged(start: Int, end: Int) {
        if (loading) return
        // Moving the caret yourself ends the current undo group (design.md §5.10).
        if (start != end || end != session.undo.expectedCaret) session.undo.breakGroup()
    }

    override fun onScrolled() {}

    override fun onBeforeClipboardEdit() {
        session.undo.breakGroup()
    }

    override fun onZoomStep(zoomIn: Boolean) {}

    override fun onPinch(scale: Float, finished: Boolean) {}

    // ------------------------------------------------------------------ title/back

    private fun updateTitle() {
        val suffix = when {
            doc.deletedOnDisk -> getString(R.string.suffix_deleted)
            doc.access == AccessMode.VIEW_ONLY_PREVIEW -> getString(R.string.suffix_preview)
            doc.access == AccessMode.READ_ONLY_FILE || doc.access == AccessMode.VIEW_ONLY_BINARY ->
                getString(R.string.suffix_read_only)
            else -> ""
        }
        val t = (if (isDirty) "*" else "") + doc.displayName + suffix
        if (t == lastTitle) return
        lastTitle = t
        titleView.text = t
        title = t
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(t))
    }

    private fun needsBackIntercept() = isDirty

    /** Registers the predictive-back callback only while Back must be intercepted (design.md §5.8). */
    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val want = needsBackIntercept()
        if (want == backRegistered) return
        val cb = (backCallback ?: OnBackInvokedCallback { if (!handleBack()) finish() }.also { backCallback = it })
            as OnBackInvokedCallback
        if (want) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        }
        backRegistered = want
    }

    /** Returns true if Back was consumed. */
    private fun handleBack(): Boolean {
        if (isDirty) {
            runWithSavePrompt(PendingAction(PendingAction.Kind.EXIT))
            return true
        }
        return false
    }

    @Deprecated("Used on API 26–32 only; API 33+ uses OnBackInvokedCallback.")
    override fun onBackPressed() {
        if (!handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------ unsaved-changes flow

    /** Runs [action] now, or after the "Do you want to save changes?" prompt (design.md §5.9). */
    private fun runWithSavePrompt(action: PendingAction) {
        if (!isDirty) {
            perform(action)
            return
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.save_changes_to, doc.displayName))
                .setPositiveButton(R.string.save) { _, _ ->
                    session.pending = action
                    save()
                }
                .setNegativeButton(R.string.dont_save) { _, _ ->
                    discardRecovery()
                    perform(action)
                }
                .setNeutralButton(R.string.cancel, null)
        )
    }

    /** Every save path ends here; continues or cancels the pending action. */
    private fun onSaveFinished(success: Boolean) {
        val action = session.pending
        session.pending = null
        if (success && action != null) perform(action)
    }

    private fun perform(action: PendingAction) {
        when (action.kind) {
            PendingAction.Kind.NEW -> newDocument()
            PendingAction.Kind.EXIT -> {
                exiting = true
                finish()
            }
            else -> {}
        }
    }

    private fun newDocument() {
        loadDocument("", newUntitledDocument())
        session.proposedName = null
        discardRecovery()
    }

    private fun save() {
        // Writing to files arrives with the file I/O layer.
        Toast.makeText(this, R.string.no_changes, Toast.LENGTH_SHORT).show()
        onSaveFinished(false)
    }

    // ------------------------------------------------------------------- recovery

    private fun scheduleRecovery() {
        val main = session.work.main
        main.removeCallbacks(recoveryRunnable)
        if (isDirty && editor.length() <= LARGE_FILE_CHARS) main.postDelayed(recoveryRunnable, RECOVERY_DELAY_MS)
    }

    /** Snapshots the dirty document into the single recovery slot (design.md §5.30). */
    private fun writeRecovery() {
        session.work.main.removeCallbacks(recoveryRunnable)
        if (!isDirty || exiting || doc.access.isViewOnly) return
        val text = editor.text.toString()
        val d = doc
        val meta = RecoveryStore.Meta(
            uri = d.uri?.toString(),
            name = d.displayName,
            encoding = d.encoding.name,
            lineEnding = d.lineEnding.name,
            mixed = d.mixedLineEndings,
            access = d.access.name,
            origin = d.origin.name,
            hadBom = d.hadBom,
            selStart = editor.selectionStart,
            selEnd = editor.selectionEnd,
            diskSize = d.diskStamp?.size,
            diskModified = d.diskStamp?.lastModified,
            time = System.currentTimeMillis(),
        )
        session.recoveryWritten = true
        session.work.io.execute {
            try {
                recovery.write(text, meta)
            } catch (e: Exception) {
                // Best effort: the text is still in memory.
            }
        }
    }

    private fun discardRecovery() {
        session.recoveryWritten = false
        session.work.main.removeCallbacks(recoveryRunnable)
        session.work.io.execute { recovery.delete() }
    }

    private fun restoreFromRecovery() {
        val store = recovery
        session.work.onIo({ Pair(store.readMeta(), store.readText()) }) { result ->
            val (meta, text) = result.getOrNull() ?: return@onIo
            if (meta == null || text == null) return@onIo
            val restored = Document(
                uri = meta.uri?.let { android.net.Uri.parse(it) },
                displayName = meta.name,
                encoding = Encoding.parse(meta.encoding),
                lineEnding = LineEnding.parse(meta.lineEnding),
                mixedLineEndings = meta.mixed,
                access = AccessMode.entries.firstOrNull { it.name == meta.access } ?: AccessMode.READ_WRITE,
                origin = Origin.entries.firstOrNull { it.name == meta.origin } ?: Origin.RECOVERED,
                hadBom = meta.hadBom,
                diskStamp = if (meta.diskSize == null && meta.diskModified == null) null
                else DiskStamp(meta.diskSize, meta.diskModified),
            )
            loadDocument(text, restored, meta.selEnd)
            editor.setSelection(meta.selStart.coerceIn(0, text.length), meta.selEnd.coerceIn(0, text.length))
            session.undo.markUnsaved()
            session.recoveryWritten = true
            updateTitle()
            updateBackCallback()
        }
    }

    // ---------------------------------------------------------------------- menus

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val editable = !doc.access.isViewOnly
        val hasSelection = editor.hasSelection()
        menu.findItem(R.id.action_undo)?.isEnabled = editable && session.undo.canUndo
        menu.findItem(R.id.edit_undo)?.isEnabled = editable && session.undo.canUndo
        menu.findItem(R.id.edit_redo)?.isEnabled = editable && session.undo.canRedo
        menu.findItem(R.id.edit_cut)?.isEnabled = editable && hasSelection
        menu.findItem(R.id.edit_copy)?.isEnabled = hasSelection
        menu.findItem(R.id.edit_delete)?.isEnabled = editable && hasSelection
        menu.findItem(R.id.edit_paste)?.isEnabled = editable && clipboardHasText()
        menu.findItem(R.id.action_save)?.isEnabled = editable
        menu.findItem(R.id.file_save)?.isEnabled = editable
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_undo, R.id.edit_undo -> undo()
            R.id.edit_redo -> redo()
            R.id.edit_cut -> editor.onTextContextMenuItem(android.R.id.cut)
            R.id.edit_copy -> editor.onTextContextMenuItem(android.R.id.copy)
            R.id.edit_paste -> editor.onTextContextMenuItem(android.R.id.pasteAsPlainText)
            R.id.edit_delete -> deleteSelection()
            R.id.edit_select_all -> editor.onTextContextMenuItem(android.R.id.selectAll)
            R.id.file_new -> runWithSavePrompt(PendingAction(PendingAction.Kind.NEW))
            R.id.action_save, R.id.file_save -> save()
            R.id.file_exit -> exitApp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun clipboardHasText(): Boolean {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return false
        val desc = cm.primaryClipDescription ?: return false
        return cm.hasPrimaryClip() &&
            (desc.hasMimeType("text/*") || desc.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN))
    }

    // --------------------------------------------------------------- edit commands

    private fun undo() {
        if (doc.access.isViewOnly) return
        val caret = session.undo.undo { s, e, t -> editor.text.replace(s, e, t) }
        if (caret >= 0) editor.setSelection(caret.coerceIn(0, editor.length()))
        onDocumentChanged()
    }

    private fun redo() {
        if (doc.access.isViewOnly) return
        val caret = session.undo.redo { s, e, t -> editor.text.replace(s, e, t) }
        if (caret >= 0) editor.setSelection(caret.coerceIn(0, editor.length()))
        onDocumentChanged()
    }

    private fun deleteSelection() {
        if (doc.access.isViewOnly || !editor.hasSelection()) return
        session.undo.breakGroup()
        val s = minOf(editor.selectionStart, editor.selectionEnd)
        val e = maxOf(editor.selectionStart, editor.selectionEnd)
        editor.text.delete(s, e)
        session.undo.breakGroup()
    }

    private fun exitApp() {
        if (isDirty) runWithSavePrompt(PendingAction(PendingAction.Kind.EXIT)) else finish()
    }

    // ----------------------------------------------------------------- keyboard

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleShortcut(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Windows Notepad shortcuts (design.md §5.34). */
    private fun handleShortcut(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val shift = e.isShiftPressed
        val inEditor = currentFocus === editor
        if (!ctrl) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_N -> runWithSavePrompt(PendingAction(PendingAction.Kind.NEW))
            KeyEvent.KEYCODE_S -> save()
            KeyEvent.KEYCODE_Z -> if (inEditor) { if (shift) redo() else undo() } else return false
            KeyEvent.KEYCODE_Y -> if (inEditor) redo() else return false
            else -> return false
        }
        return true
    }

    // --------------------------------------------------------------- appearance

    private fun applyFont() {
        editor.typeface = Typeface.create(settings.fontFamily, settings.fontStyle)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp * settings.zoomPercent / 100f)
        editor.applyTabStops()
    }

    private fun applyWordWrap() {
        editor.setHorizontallyScrolling(!settings.wordWrap)
    }

    /** design.md §6.13: draw behind system bars on API 30+ and pad for bars and the IME. */
    private fun applyEdgeToEdge(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
    }

    // -------------------------------------------------------------------- helpers

    private fun showDialog(builder: AlertDialog.Builder): AlertDialog {
        activeDialog?.dismiss()
        val d = builder.create()
        d.setOnDismissListener { if (activeDialog === d) activeDialog = null }
        activeDialog = d
        d.show()
        return d
    }

    private fun showProgress(show: Boolean) {
        val main = session.work.main
        main.removeCallbacks(showProgressRunnable)
        if (show) main.postDelayed(showProgressRunnable, PROGRESS_DELAY_MS) else progress.visibility = View.GONE
    }

    companion object {
        const val LARGE_FILE_CHARS = 1_000_000
        const val RECOVERY_DELAY_MS = 3000L
        const val RECOVERY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val PROGRESS_DELAY_MS = 300L
    }
}
