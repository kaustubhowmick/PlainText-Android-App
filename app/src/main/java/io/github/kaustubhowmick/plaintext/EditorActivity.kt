package io.github.kaustubhowmick.plaintext

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
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
import io.github.kaustubhowmick.plaintext.io.Detection
import io.github.kaustubhowmick.plaintext.io.DocumentIo
import io.github.kaustubhowmick.plaintext.io.Encoding
import io.github.kaustubhowmick.plaintext.io.EncodingDetector
import io.github.kaustubhowmick.plaintext.io.FileNames
import io.github.kaustubhowmick.plaintext.io.LineEnding
import io.github.kaustubhowmick.plaintext.io.LineEndings
import io.github.kaustubhowmick.plaintext.io.TextCodec
import io.github.kaustubhowmick.plaintext.storage.DefaultFolder
import io.github.kaustubhowmick.plaintext.storage.RecentFiles
import io.github.kaustubhowmick.plaintext.storage.RecoveryStore
import io.github.kaustubhowmick.plaintext.storage.Settings
import io.github.kaustubhowmick.plaintext.ui.Dialogs
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.text.DateFormat
import java.util.Date

/**
 * The only activity (design.md §3.2): owns the views, menus, dialogs, shortcuts,
 * back handling, activity results, intent handling, and the open/save flows.
 * Long-lived state lives in [EditorSession], which survives configuration changes.
 */
class EditorActivity : Activity(), EditorView.Listener {

    private lateinit var settings: Settings
    private lateinit var recovery: RecoveryStore
    private lateinit var io: DocumentIo
    private lateinit var folder: DefaultFolder
    private lateinit var recents: RecentFiles
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
        io = DocumentIo(applicationContext)
        folder = DefaultFolder(contentResolver, settings)
        recents = RecentFiles({ settings.recentFilesJson }, { settings.recentFilesJson = it })

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkExternalChange()
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
        val uri = state.getParcelable<Uri>("uri")
        if (state.getBoolean("dirty") && recovery.exists()) {
            restoreFromRecovery()
        } else if (uri != null) {
            // The document was clean: just read the file again (no .LOG, no Recent entry).
            val origin = Origin.entries.firstOrNull { it.name == state.getString("origin") } ?: Origin.PICKER_OPEN
            openUri(uri, origin, OpenMode.RESTORE, caret = state.getInt("selEnd"))
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
                        restoreFromRecovery { handleLaunchIntent() }
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
        val i = intent ?: return
        if (i.action == null || i.action == Intent.ACTION_MAIN) maybeFirstRun() else handleIntent(i)
    }

    // ------------------------------------------------------------ intents (§7)

    private fun handleIntent(i: Intent) {
        if (i.getBooleanExtra(EXTRA_HANDLED, false)) return
        i.putExtra(EXTRA_HANDLED, true)
        setIntent(i)
        when (i.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                val uri = i.data ?: return
                requestOpen(uri, if (i.action == Intent.ACTION_EDIT) Origin.EDIT_INTENT else Origin.VIEW_INTENT)
            }
            Intent.ACTION_SEND -> {
                val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val stream = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                when {
                    text != null -> runWithSavePrompt(PendingAction(PendingAction.Kind.SHARE_TEXT, intent = i))
                    stream != null -> requestOpen(stream, Origin.SHARE)
                }
            }
        }
    }

    private fun openSharedText(i: Intent) {
        val raw = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return
        val stats = LineEndings.count(raw)
        val shared = newUntitledDocument().apply {
            lineEnding = stats.dominant() ?: settings.defaultLineEnding
            origin = Origin.SHARE
        }
        loadDocument(LineEndings.normalize(raw), shared)
        session.undo.markUnsaved() // shared text is new, unsaved content
        session.proposedName = i.getStringExtra(Intent.EXTRA_SUBJECT)
            ?.let { FileNames.sanitize(it) }?.takeIf { it.isNotEmpty() }
            ?.let { FileNames.limitBytes(FileNames.ensureExtension(it)) }
        discardRecovery()
        updateTitle()
        updateBackCallback()
    }

    // --------------------------------------------------------------- first run

    private fun maybeFirstRun() {
        if (settings.firstRunDone) return
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.first_run_title)
                .setMessage(R.string.first_run_message)
                .setCancelable(false)
                .setPositiveButton(R.string.choose_folder) { _, _ ->
                    settings.firstRunDone = true
                    launchTreePicker(TreePurpose.FIRST_RUN)
                }
                .setNegativeButton(R.string.not_now) { _, _ -> settings.firstRunDone = true }
        )
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
        if (caret == 0) editor.scrollTo(0, 0) else editor.post { editor.bringPointIntoView(editor.selectionEnd) }
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
            PendingAction.Kind.OPEN_PICKER -> launchOpenPicker()
            PendingAction.Kind.OPEN_URI -> {
                val origin = Origin.entries.firstOrNull { it.name == action.extra } ?: Origin.PICKER_OPEN
                action.uri?.let { openUri(it, origin) }
            }
            PendingAction.Kind.SHARE_TEXT -> action.intent?.let { openSharedText(it) }
            PendingAction.Kind.REOPEN_WITH -> {
                val uri = doc.uri ?: return
                openUri(uri, doc.origin, OpenMode.REOPEN, forced = Encoding.parse(action.extra))
            }
            PendingAction.Kind.EXIT -> {
                exiting = true
                finish()
            }
        }
    }

    private fun newDocument() {
        loadDocument("", newUntitledDocument())
        session.proposedName = null
        session.mixedCounts = null
        discardRecovery()
    }

    /** Open a URI unless it is already the open document (design.md §5.3). */
    private fun requestOpen(uri: Uri, origin: Origin) {
        if (uri == doc.uri) {
            editor.requestFocus()
            session.lastStatCheck = 0
            checkExternalChange()
            return
        }
        runWithSavePrompt(PendingAction(PendingAction.Kind.OPEN_URI, uri = uri, extra = origin.name))
    }

    // --------------------------------------------------------------- open (§5.3)

    private enum class OpenMode {
        NORMAL,     // a user-initiated open: .LOG applies, Recent updated
        BINARY_OK,  // the user chose "Open read-only" for a binary file
        PREVIEW,    // the first 10 MB of an oversized file
        RELOAD,     // external change: keep caret, no .LOG
        REOPEN,     // Reopen with Encoding
        RESTORE,    // after process death: silent, no .LOG
    }

    private sealed class OpenResult {
        class Loaded(val text: String, val doc: Document, val mixed: IntArray?, val longLines: Boolean) : OpenResult()
        class TooLarge(val name: String, val size: Long) : OpenResult()
        class Binary(val name: String) : OpenResult()
        class InvalidEncoding(val encoding: Encoding) : OpenResult()
    }

    /**
     * The open pipeline: read and decode on the IO thread, then replace the
     * document on the main thread. The current document stays untouched if
     * anything fails.
     */
    private fun openUri(uri: Uri, origin: Origin, mode: OpenMode = OpenMode.NORMAL, forced: Encoding? = null, caret: Int = 0) {
        if (session.busy) return
        session.busy = true
        showProgress(true)
        val defaultEncoding = settings.defaultEncoding
        val defaultEol = settings.defaultLineEnding
        val writeGranted = hasWriteGrant(uri)
        val previous = doc
        session.work.onIo({
            readDocument(uri, origin, mode, forced, defaultEncoding, defaultEol, writeGranted, previous)
        }) { result ->
            session.busy = false
            showProgress(false)
            result.onSuccess { r -> onOpenResult(uri, origin, mode, caret, r) }
            result.onFailure { e -> onOpenFailed(uri, origin, mode, e) }
        }
    }

    /** Runs on the IO thread. */
    private fun readDocument(
        uri: Uri,
        origin: Origin,
        mode: OpenMode,
        forced: Encoding?,
        defaultEncoding: Encoding,
        defaultEol: LineEnding,
        writeGranted: Boolean,
        previous: Document,
    ): OpenResult {
        val meta = io.queryMeta(uri)
        if (!meta.exists) throw FileNotFoundException(uri.toString())
        val name = meta.name ?: getString(R.string.untitled)
        val preview = mode == OpenMode.PREVIEW
        if (!preview && meta.size != null && meta.size > MAX_FILE_BYTES) return OpenResult.TooLarge(name, meta.size)
        val raw = io.read(uri, MAX_FILE_BYTES)
        if (!preview && raw.size > MAX_FILE_BYTES) return OpenResult.TooLarge(name, meta.size ?: raw.size.toLong())
        var bytes = if (raw.size > MAX_FILE_BYTES) raw.copyOf(MAX_FILE_BYTES) else raw

        val detection = if (forced != null) {
            Detection(forced, TextCodec.bomLength(bytes, forced), false)
        } else {
            EncodingDetector.detect(bytes, defaultEncoding)
        }
        if (detection.isBinary && mode != OpenMode.BINARY_OK && !preview) return OpenResult.Binary(name)

        val text = when {
            preview -> {
                if (detection.encoding.isUtf16 && bytes.size % 2 == 1) bytes = bytes.copyOf(bytes.size - 1)
                val decoded = TextCodec.decode(bytes, detection.encoding, detection.bomLength, strict = false)
                // Cut at the last complete line.
                val cut = maxOf(decoded.lastIndexOf('\n'), decoded.lastIndexOf('\r'))
                if (raw.size > MAX_FILE_BYTES && cut > 0) decoded.substring(0, cut + 1) else decoded
            }
            detection.isBinary -> TextCodec.decode(bytes, Encoding.ANSI, 0)
            else -> try {
                TextCodec.decode(bytes, detection.encoding, detection.bomLength)
            } catch (e: CharacterCodingException) {
                if (forced != null) return OpenResult.InvalidEncoding(forced)
                return OpenResult.Binary(name)
            }
        }

        val stats = LineEndings.count(text)
        val normalized = LineEndings.normalize(text)
        var longest = 0
        var run = 0
        for (c in normalized) {
            if (c == '\n') run = 0 else if (++run > longest) longest = run
        }
        val access = when {
            preview -> AccessMode.VIEW_ONLY_PREVIEW
            detection.isBinary -> AccessMode.VIEW_ONLY_BINARY
            origin == Origin.SHARE -> AccessMode.READ_ONLY_FILE
            meta.supportsWrite == false -> AccessMode.READ_ONLY_FILE
            (origin == Origin.VIEW_INTENT || origin == Origin.EDIT_INTENT) && !writeGranted -> AccessMode.READ_ONLY_FILE
            mode == OpenMode.RELOAD || mode == OpenMode.REOPEN -> previous.access
            else -> AccessMode.READ_WRITE
        }
        val newDoc = Document(
            uri = uri,
            displayName = name,
            encoding = detection.encoding,
            lineEnding = stats.dominant() ?: defaultEol,
            mixedLineEndings = stats.isMixed,
            access = access,
            origin = origin,
            hadBom = !detection.encoding.isUtf16 || detection.bomLength > 0,
            diskStamp = meta.stamp,
        )
        val mixed = if (stats.isMixed) intArrayOf(stats.crlf, stats.lf, stats.cr) else null
        return OpenResult.Loaded(normalized, newDoc, mixed, longest > LONG_LINE_CHARS)
    }

    private fun onOpenResult(uri: Uri, origin: Origin, mode: OpenMode, caret: Int, r: OpenResult) {
        when (r) {
            is OpenResult.TooLarge -> showDialog(
                AlertDialog.Builder(this)
                    .setTitle(R.string.too_large_title)
                    .setMessage(getString(R.string.too_large_message, r.name, Formatter.formatShortFileSize(this, r.size)))
                    .setPositiveButton(R.string.open_preview) { _, _ -> openUri(uri, origin, OpenMode.PREVIEW) }
                    .setNegativeButton(R.string.cancel, null)
            )
            is OpenResult.Binary -> showDialog(
                AlertDialog.Builder(this)
                    .setTitle(R.string.binary_title)
                    .setMessage(getString(R.string.binary_message, r.name))
                    .setPositiveButton(R.string.open_read_only) { _, _ -> openUri(uri, origin, OpenMode.BINARY_OK) }
                    .setNegativeButton(R.string.cancel, null)
            )
            is OpenResult.InvalidEncoding -> showMessage(getString(R.string.invalid_encoding, r.encoding.label))
            is OpenResult.Loaded -> applyLoaded(r, mode, caret)
        }
    }

    private fun applyLoaded(r: OpenResult.Loaded, mode: OpenMode, caret: Int) {
        val keepPosition = mode == OpenMode.RELOAD || mode == OpenMode.REOPEN
        val oldLine = if (keepPosition) session.lines.lineOf(editor.selectionEnd) else 0
        val oldCol = if (keepPosition) editor.selectionEnd - session.lines.lineStart(oldLine) else 0

        loadDocument(r.text, r.doc)
        session.mixedCounts = r.mixed
        session.ignoredStamp = null
        session.proposedName = null
        val target = when {
            keepPosition -> {
                val line = oldLine.coerceAtMost(session.lines.lineCount - 1)
                val start = session.lines.lineStart(line)
                val end = if (line + 1 < session.lines.lineCount) session.lines.lineStart(line + 1) - 1 else r.text.length
                (start + oldCol).coerceAtMost(end)
            }
            else -> caret.coerceIn(0, r.text.length)
        }
        editor.setSelection(target)
        if (target > 0) editor.post { editor.bringPointIntoView(editor.selectionEnd) }
        discardRecovery()

        if (mode == OpenMode.NORMAL || mode == OpenMode.BINARY_OK || mode == OpenMode.PREVIEW) {
            if (r.doc.origin != Origin.SHARE) addRecent(r.doc.uri!!, r.doc.displayName)
        }
        if (mode == OpenMode.NORMAL) onFileOpened()
        when {
            r.mixed != null && mode != OpenMode.RESTORE -> toast(getString(R.string.mixed_on_open))
            r.longLines -> toast(getString(R.string.long_lines))
            r.text.length > LARGE_FILE_CHARS && mode == OpenMode.NORMAL -> toast(getString(R.string.large_file))
        }
        if (mode == OpenMode.RELOAD) toast(getString(R.string.reloaded, r.doc.displayName))
    }

    /** Hook for behavior that runs after a user-initiated open (the .LOG feature). */
    private fun onFileOpened() {}

    private fun onOpenFailed(uri: Uri, origin: Origin, mode: OpenMode, e: Throwable) {
        if (mode == OpenMode.RESTORE) return
        val name = recents.list().firstOrNull { it.uri == uri.toString() }?.name
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: getString(R.string.untitled)
        val message = when {
            e is OutOfMemoryError -> getString(R.string.too_large_to_open, name)
            uri.scheme == "file" && (e is SecurityException || e is FileNotFoundException) -> getString(R.string.cant_read_from_app)
            else -> getString(R.string.cant_open, name)
        }
        recents.remove(uri.toString())
        showMessage(message)
    }

    private fun hasWriteGrant(uri: Uri): Boolean {
        if (checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    // --------------------------------------------------------------- save (§5.4)

    /** Where and how a save writes. */
    private class SaveTarget(
        val uri: Uri,
        val encoding: Encoding,
        val lineEnding: LineEnding,
        val hadBom: Boolean,
        val origin: Origin,
        val requestedName: String? = null,
    )

    private fun save() {
        if (session.busy) return
        val d = doc
        when {
            d.access.isViewOnly -> onSaveFinished(false)
            d.uri == null -> saveUntitled()
            d.access == AccessMode.READ_ONLY_FILE -> showCantSaveHere()
            d.deletedOnDisk -> saveDeleted()
            !isDirty -> {
                toast(getString(R.string.no_changes))
                onSaveFinished(true)
            }
            else -> saveInPlace(d.uri!!)
        }
    }

    private fun saveInPlace(uri: Uri) {
        session.busy = true
        session.work.onIo({ io.stat(uri) }) { result ->
            session.busy = false
            val meta = result.getOrElse { e ->
                onSaveError(e, null)
                return@onIo
            }
            if (!meta.exists) {
                markDeleted()
                saveDeleted()
                return@onIo
            }
            val old = doc.diskStamp
            val target = SaveTarget(uri, doc.encoding, doc.lineEnding, doc.hadBom, doc.origin)
            if (old != null && meta.stamp.differsFrom(old)) {
                showDialog(
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.changed_since_open_title, doc.displayName))
                        .setMessage(R.string.changed_since_open)
                        .setPositiveButton(R.string.overwrite) { _, _ -> checkThenWrite(target) }
                        .setNeutralButton(R.string.save_as) { _, _ -> saveAs() }
                        .setNegativeButton(R.string.cancel) { _, _ -> onSaveFinished(false) }
                        .setOnCancelListener { onSaveFinished(false) }
                )
            } else {
                checkThenWrite(target)
            }
        }
    }

    /** Unmappable-character check, then the mixed-line-ending confirmation, then write. */
    private fun checkThenWrite(target: SaveTarget) {
        checkUnmappable(target.encoding) { encoding ->
            val t = if (encoding == target.encoding) target
            else SaveTarget(target.uri, encoding, target.lineEnding, true, target.origin, target.requestedName)
            val counts = session.mixedCounts
            if (doc.mixedLineEndings && counts != null) {
                confirmMixed(counts, t.lineEnding) { eol ->
                    writeDocument(SaveTarget(t.uri, t.encoding, eol, t.hadBom, t.origin, t.requestedName))
                }
            } else {
                writeDocument(t)
            }
        }
    }

    /** Calls [then] with the encoding to save in, after asking if ANSI would lose characters (§5.24). */
    private fun checkUnmappable(encoding: Encoding, then: (Encoding) -> Unit) {
        val text = editor.text
        val index = TextCodec.findUnmappable(text, encoding)
        if (index < 0) {
            then(encoding)
            return
        }
        val end = if (Character.isHighSurrogate(text[index]) && index + 1 < text.length) index + 2 else index + 1
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.unmappable_title)
                .setMessage(getString(R.string.unmappable_message, text.subSequence(index, end).toString()))
                .setPositiveButton(R.string.save_as_utf8) { _, _ ->
                    if (doc.encoding != Encoding.UTF8) {
                        doc.encoding = Encoding.UTF8
                        doc.metadataDirty = true
                    }
                    then(Encoding.UTF8)
                }
                .setNeutralButton(R.string.save_as_ansi_anyway) { _, _ -> then(Encoding.ANSI) }
                .setNegativeButton(R.string.cancel) { _, _ -> onSaveFinished(false) }
                .setOnCancelListener { onSaveFinished(false) }
        )
    }

    /** Mixed line endings are only converted after the user confirms (§5.25). */
    private fun confirmMixed(counts: IntArray, preselected: LineEnding, then: (LineEnding) -> Unit) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.mixed_message, doc.displayName, counts[0], counts[1], counts[2])
        })
        val group = RadioGroup(this)
        LineEnding.entries.forEach { e ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = e.label
                tag = e
                minHeight = (48 * resources.displayMetrics.density).toInt()
                isChecked = e == preselected
            })
        }
        content.addView(group)
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.mixed_title)
                .setView(content)
                .setPositiveButton(R.string.save) { _, _ ->
                    val chosen = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? LineEnding ?: preselected
                    then(chosen)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> onSaveFinished(false) }
                .setOnCancelListener { onSaveFinished(false) }
        )
    }

    /** Encodes on the IO thread and writes in place (design.md §3.4). */
    private fun writeDocument(target: SaveTarget) {
        val text = editor.text.toString()
        val state = session.undo.stateId
        session.undo.breakGroup()
        session.busy = true
        showProgress(true)
        session.work.onIo({
            val bytes = TextCodec.encode(LineEndings.denormalize(text, target.lineEnding), target.encoding, target.hadBom)
            val written = io.write(target.uri, bytes)
            val meta = try {
                io.stat(target.uri)
            } catch (e: Exception) {
                null
            }
            Pair(written, meta)
        }) { result ->
            session.busy = false
            showProgress(false)
            val (written, meta) = result.getOrElse { e ->
                onSaveError(e, target)
                return@onIo
            }
            val d = doc
            d.uri = target.uri
            d.encoding = target.encoding
            d.lineEnding = target.lineEnding
            d.hadBom = target.hadBom
            d.mixedLineEndings = false
            d.metadataDirty = false
            d.deletedOnDisk = false
            d.access = AccessMode.READ_WRITE
            d.origin = target.origin
            d.displayName = meta?.name ?: target.requestedName ?: d.displayName
            d.diskStamp = meta?.stamp
            session.mixedCounts = null
            session.ignoredStamp = null
            session.proposedName = null
            session.undo.markSaved(state)
            discardRecovery()
            addRecent(target.uri, d.displayName)
            updateTitle()
            updateBackCallback()
            val requested = target.requestedName
            toast(if (requested != null && !requested.equals(d.displayName, ignoreCase = false)) getString(R.string.saved_as, d.displayName) else getString(R.string.saved))
            if (written == DocumentIo.WriteResult.MAYBE_LEFTOVER) {
                showDialog(
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.maybe_leftover, d.displayName))
                        .setPositiveButton(R.string.ok) { _, _ -> onSaveFinished(true) }
                        .setNeutralButton(R.string.save_as) { _, _ -> onSaveFinished(true); saveAs() }
                        .setOnCancelListener { onSaveFinished(true) }
                )
            } else {
                onSaveFinished(true)
            }
        }
    }

    /** Maps a failed save to the messages in design.md §8. The text always stays. */
    private fun onSaveError(e: Throwable, target: SaveTarget?) {
        val name = doc.displayName
        val cancel = { _: android.content.DialogInterface, _: Int -> onSaveFinished(false) }
        val builder = AlertDialog.Builder(this).setOnCancelListener { onSaveFinished(false) }
        when {
            e is SecurityException -> {
                doc.access = AccessMode.READ_ONLY_FILE
                updateTitle()
                builder.setMessage(getString(R.string.permission_lost, name))
                    .setPositiveButton(R.string.save_as) { _, _ -> saveAs() }
                    .setNegativeButton(R.string.cancel, cancel)
            }
            e is FileNotFoundException -> {
                doc.access = AccessMode.READ_ONLY_FILE
                updateTitle()
                builder.setTitle(getString(R.string.cant_save_here_title, name))
                    .setMessage(R.string.cant_save_here)
                    .setPositiveButton(R.string.save_as) { _, _ -> saveAs() }
                    .setNegativeButton(R.string.cancel, cancel)
            }
            isNoSpace(e) -> builder.setMessage(getString(R.string.storage_full, name))
                .setPositiveButton(R.string.save_as) { _, _ -> saveAs() }
                .setNegativeButton(R.string.ok, cancel)
            else -> {
                builder.setMessage(getString(R.string.write_failed, name))
                    .setNegativeButton(R.string.cancel, cancel)
                    .setNeutralButton(R.string.save_as) { _, _ -> saveAs() }
                if (target != null) builder.setPositiveButton(R.string.try_again) { _, _ -> writeDocument(target) }
            }
        }
        showDialog(builder)
    }

    private fun isNoSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is ErrnoException && t.errno == OsConstants.ENOSPC) return true
            if (t is java.io.SyncFailedException) return true
            val m = t.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
            t = t.cause
        }
        return false
    }

    private fun showCantSaveHere() {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.cant_save_here_title, doc.displayName))
                .setMessage(R.string.cant_save_here)
                .setPositiveButton(R.string.save_as) { _, _ -> saveAs() }
                .setNegativeButton(R.string.cancel) { _, _ -> onSaveFinished(false) }
                .setOnCancelListener { onSaveFinished(false) }
        )
    }

    /** Untitled document: save into the default folder, or Save As when there is none (§5.4). */
    private fun saveUntitled() {
        if (folder.treeUri == null) {
            saveAs()
            return
        }
        session.busy = true
        session.work.onIo({ if (folder.validate()) folder.displayName() ?: "" else null }) { result ->
            session.busy = false
            val name = result.getOrNull()
            if (name == null) showFolderUnavailable() else showSaveToFolder(name, null)
        }
    }

    private fun showFolderUnavailable() {
        showDialog(
            AlertDialog.Builder(this)
                .setMessage(R.string.folder_unavailable)
                .setPositiveButton(R.string.choose_folder) { _, _ -> launchTreePicker(TreePurpose.SAVE) }
                .setNeutralButton(R.string.save_as) { _, _ -> saveAs() }
                .setNegativeButton(R.string.cancel) { _, _ -> onSaveFinished(false) }
                .setOnCancelListener { onSaveFinished(false) }
        )
    }

    private fun proposedName(): String =
        session.proposedName ?: FileNames.proposeName(editor.text, getString(R.string.untitled))

    private fun showSaveToFolder(folderName: String, name: String?) {
        Dialogs.saveOptions(
            this, ::showDialog, folderName, name ?: proposedName(), doc.encoding, doc.lineEnding,
            onOtherLocation = { c ->
                rememberDefaults(c)
                checkUnmappable(c.encoding) { enc -> launchCreatePicker(enc, c.lineEnding) }
            },
            onCancel = { onSaveFinished(false) },
        ) { c ->
            rememberDefaults(c)
            val fileName = c.name ?: return@saveOptions
            checkUnmappable(c.encoding) { enc -> saveIntoFolder(folderName, fileName, enc, c.lineEnding) }
        }
    }

    private fun rememberDefaults(c: Dialogs.SaveChoice) {
        if (!c.useForNew) return
        settings.defaultEncoding = c.encoding
        settings.defaultLineEnding = c.lineEnding
    }

    /** Checks for a name collision (§4.6), then creates the file and writes it. */
    private fun saveIntoFolder(folderName: String, name: String, encoding: Encoding, eol: LineEnding) {
        session.busy = true
        session.work.onIo({ folder.children() }) { result ->
            session.busy = false
            val children = result.getOrElse { e ->
                onSaveError(e, null)
                return@onIo
            }
            val existing = children[name.lowercase()]
            if (existing == null) {
                createInFolder(name, encoding, eol)
                return@onIo
            }
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.already_exists_title, name))
                    .setMessage(R.string.replace_it)
                    .setPositiveButton(R.string.replace) { _, _ ->
                        writeDocument(SaveTarget(existing, encoding, eol, true, Origin.DEFAULT_FOLDER, name))
                    }
                    .setNeutralButton(R.string.keep_both) { _, _ ->
                        createInFolder(FileNames.nextFreeName(name) { children.containsKey(it.lowercase()) }, encoding, eol)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> showSaveToFolder(folderName, name) }
                    .setOnCancelListener { onSaveFinished(false) }
            )
        }
    }

    private fun createInFolder(name: String, encoding: Encoding, eol: LineEnding) {
        session.busy = true
        session.work.onIo({
            val mime = FileNames.mimeFor(name) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            folder.create(name, mime) ?: throw IOException("createDocument returned null")
        }) { result ->
            session.busy = false
            val uri = result.getOrElse { e ->
                onSaveError(e, null)
                return@onIo
            }
            writeDocument(SaveTarget(uri, encoding, eol, true, Origin.DEFAULT_FOLDER, name))
        }
    }

    /** The file vanished from disk: recreate it in the default folder, else Save As. */
    private fun saveDeleted() {
        if (doc.origin != Origin.DEFAULT_FOLDER || folder.treeUri == null) {
            saveAs()
            return
        }
        val name = doc.displayName
        val encoding = doc.encoding
        val eol = doc.lineEnding
        session.busy = true
        session.work.onIo({ folder.validate() }) { result ->
            session.busy = false
            if (result.getOrDefault(false)) {
                checkUnmappable(encoding) { enc -> createInFolder(name, enc, eol) }
            } else {
                saveAs()
            }
        }
    }

    /** Save As (§5.5): choose encoding and line ending, then the system picker. */
    private fun saveAs() {
        if (doc.access.isViewOnly) {
            onSaveFinished(false)
            return
        }
        Dialogs.saveOptions(
            this, ::showDialog, null, null, doc.encoding, doc.lineEnding,
            onOtherLocation = null,
            onCancel = { onSaveFinished(false) },
        ) { c ->
            rememberDefaults(c)
            checkUnmappable(c.encoding) { enc -> launchCreatePicker(enc, c.lineEnding) }
        }
    }

    private fun launchCreatePicker(encoding: Encoding, eol: LineEnding) {
        session.saveAsEncoding = encoding
        session.saveAsLineEnding = eol
        val name = if (doc.uri != null) doc.displayName else proposedName()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(FileNames.mimeFor(name) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) })
            .putExtra(Intent.EXTRA_TITLE, name)
        (doc.uri ?: folder.rootDocument)?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        if (!startPicker(intent, REQ_CREATE)) onSaveFinished(false)
    }

    // ------------------------------------------------------ pickers and results

    private fun launchOpenPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, OPEN_MIME_TYPES)
        folder.rootDocument?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        startPicker(intent, REQ_OPEN)
    }

    private fun launchTreePicker(purpose: TreePurpose) {
        session.treePurpose = purpose
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents"),
            )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_TREE)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_tree_picker))
            if (purpose == TreePurpose.SAVE) saveAs()
        }
    }

    /** Returns false (after telling the user) when the device has no picker. */
    private fun startPicker(intent: Intent, request: Int): Boolean = try {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, request)
        true
    } catch (e: ActivityNotFoundException) {
        showMessage(getString(R.string.no_picker))
        false
    }

    @Deprecated("Framework Activity API; there is no AndroidX here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        when (requestCode) {
            REQ_OPEN -> if (uri != null) {
                takeGrant(uri, data!!.flags)
                openUri(uri, Origin.PICKER_OPEN)
            }
            REQ_CREATE -> if (uri == null) {
                onSaveFinished(false)
            } else {
                takeGrant(uri, data!!.flags)
                val encoding = session.saveAsEncoding ?: doc.encoding
                val eol = session.saveAsLineEnding ?: doc.lineEnding
                val hadBom = if (encoding == doc.encoding) doc.hadBom else true
                writeDocument(SaveTarget(uri, encoding, eol, hadBom, Origin.PICKER_CREATE))
            }
            REQ_TREE -> onTreePicked(uri, data?.flags ?: 0)
        }
    }

    private fun onTreePicked(tree: Uri?, flags: Int) {
        val purpose = session.treePurpose
        if (tree == null) {
            if (purpose == TreePurpose.SAVE) onSaveFinished(false)
            return
        }
        try {
            folder.set(tree, flags)
        } catch (e: SecurityException) {
            showMessage(getString(R.string.folder_unavailable))
            if (purpose == TreePurpose.SAVE) onSaveFinished(false)
            return
        }
        session.work.onIo({ folder.displayName() ?: "" }) { result ->
            toast(getString(R.string.notes_saved_in, result.getOrDefault("")))
            if (purpose == TreePurpose.SAVE) save()
        }
    }

    private fun takeGrant(uri: Uri, flags: Int) {
        val wanted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (wanted == 0) return
        try {
            contentResolver.takePersistableUriPermission(uri, wanted)
        } catch (e: SecurityException) {
            // Not persistable: fine for this session (design.md §4.3).
        }
    }

    // -------------------------------------------------------------- recent files

    private fun addRecent(uri: Uri, name: String) {
        recents.add(uri.toString(), name).forEach { releaseIfUnused(Uri.parse(it.uri)) }
    }

    /** Keeps the persisted-grant count low (design.md §4.4). */
    private fun releaseIfUnused(uri: Uri) {
        if (uri == doc.uri || uri == folder.treeUri) return
        val held = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var modes = 0
        if (held.isReadPermission) modes = modes or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (held.isWritePermission) modes = modes or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.releasePersistableUriPermission(uri, modes)
        } catch (e: SecurityException) {
            // Already released.
        }
    }

    private fun populateRecent(menu: Menu) {
        val sub = menu.findItem(R.id.file_recent)?.subMenu ?: return
        sub.clear()
        val entries = recents.list()
        val counts = entries.groupingBy { it.name }.eachCount()
        entries.forEachIndexed { i, e ->
            val label = if ((counts[e.name] ?: 0) > 1) "${e.name} — ${providerLabel(Uri.parse(e.uri))}" else e.name
            sub.add(Menu.NONE, RECENT_BASE + i, i, label)
        }
        sub.add(Menu.NONE, R.id.recent_clear, entries.size, R.string.clear_recent).isEnabled = entries.isNotEmpty()
    }

    private fun providerLabel(uri: Uri): String {
        val authority = uri.authority ?: return ""
        return try {
            packageManager.resolveContentProvider(authority, 0)?.loadLabel(packageManager)?.toString() ?: authority
        } catch (e: Exception) {
            authority
        }
    }

    private fun showDefaultFolder() {
        session.work.onIo({ if (folder.treeUri != null) folder.displayName() else null }) { result ->
            val name = result.getOrNull()
            val builder = AlertDialog.Builder(this)
                .setTitle(R.string.default_folder)
                .setMessage(if (name != null) getString(R.string.default_folder_is, name) else getString(R.string.default_folder_none))
                .setPositiveButton(R.string.change) { _, _ -> launchTreePicker(TreePurpose.SETTINGS) }
                .setNegativeButton(R.string.close, null)
            if (name != null) builder.setNeutralButton(R.string.clear) { _, _ -> folder.clear() }
            showDialog(builder)
        }
    }

    // ------------------------------------------------ external changes (§5.33)

    private fun checkExternalChange() {
        val uri = doc.uri ?: return
        if (session.busy || activeDialog != null || doc.access == AccessMode.VIEW_ONLY_PREVIEW) return
        val now = System.currentTimeMillis()
        if (now - session.lastStatCheck < STAT_THROTTLE_MS) return
        session.lastStatCheck = now
        session.work.onIo({ io.stat(uri) }) { result ->
            if (doc.uri != uri || session.busy) return@onIo
            val meta = result.getOrElse { e ->
                if (e is FileNotFoundException) markDeletedWithNotice()
                return@onIo
            }
            if (!meta.exists) {
                markDeletedWithNotice()
                return@onIo
            }
            if (doc.deletedOnDisk) return@onIo
            val old = doc.diskStamp ?: return@onIo
            val now2 = meta.stamp
            if (!now2.differsFrom(old) || now2 == session.ignoredStamp) return@onIo
            if (!isDirty) {
                openUri(uri, doc.origin, OpenMode.RELOAD)
            } else if (activeDialog == null) {
                showDialog(
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.changed_externally_title, doc.displayName))
                        .setMessage(R.string.changed_externally)
                        .setPositiveButton(R.string.reload) { _, _ -> openUri(uri, doc.origin, OpenMode.RELOAD) }
                        .setNegativeButton(R.string.keep_mine) { _, _ -> session.ignoredStamp = now2 }
                        .setOnCancelListener { session.ignoredStamp = now2 }
                )
            }
        }
    }

    private fun markDeleted() {
        if (doc.deletedOnDisk) return
        doc.deletedOnDisk = true
        doc.metadataDirty = true
        updateTitle()
        updateBackCallback()
    }

    private fun markDeletedWithNotice() {
        if (doc.deletedOnDisk) return
        markDeleted()
        toast(getString(R.string.deleted_externally, doc.displayName))
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

    private fun restoreFromRecovery(then: (() -> Unit)? = null) {
        val store = recovery
        session.work.onIo({ Pair(store.readMeta(), store.readText()) }) { result ->
            val (meta, text) = result.getOrNull() ?: Pair(null, null)
            if (meta != null && text != null) {
                val restored = Document(
                    uri = meta.uri?.let { Uri.parse(it) },
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
                loadDocument(text, restored)
                editor.setSelection(meta.selStart.coerceIn(0, text.length), meta.selEnd.coerceIn(0, text.length))
                if (meta.selEnd > 0) editor.post { editor.bringPointIntoView(editor.selectionEnd) }
                session.undo.markUnsaved()
                session.recoveryWritten = true
                updateTitle()
                updateBackCallback()
            }
            then?.invoke()
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
        val wide = resources.configuration.screenWidthDp >= WIDE_DP
        menu.findItem(R.id.action_redo)?.apply {
            isVisible = wide
            isEnabled = editable && session.undo.canRedo
        }
        menu.findItem(R.id.action_new)?.isVisible = wide
        menu.findItem(R.id.action_open)?.isVisible = wide
        menu.findItem(R.id.action_undo)?.isEnabled = editable && session.undo.canUndo
        menu.findItem(R.id.edit_undo)?.isEnabled = editable && session.undo.canUndo
        menu.findItem(R.id.edit_redo)?.isEnabled = editable && session.undo.canRedo
        menu.findItem(R.id.edit_cut)?.isEnabled = editable && hasSelection
        menu.findItem(R.id.edit_copy)?.isEnabled = hasSelection
        menu.findItem(R.id.edit_delete)?.isEnabled = editable && hasSelection
        menu.findItem(R.id.edit_paste)?.isEnabled = editable && clipboardHasText()
        menu.findItem(R.id.action_save)?.isEnabled = editable
        menu.findItem(R.id.file_save)?.isEnabled = editable
        menu.findItem(R.id.file_save_as)?.isEnabled = editable
        menu.findItem(R.id.file_reopen)?.isEnabled = doc.uri != null
        populateRecent(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id >= RECENT_BASE && id < RECENT_BASE + RecentFiles.MAX) {
            recents.list().getOrNull(id - RECENT_BASE)?.let { requestOpen(Uri.parse(it.uri), Origin.RECENT) }
            return true
        }
        when (id) {
            R.id.action_undo, R.id.edit_undo -> undo()
            R.id.action_redo, R.id.edit_redo -> redo()
            R.id.edit_cut -> editor.onTextContextMenuItem(android.R.id.cut)
            R.id.edit_copy -> editor.onTextContextMenuItem(android.R.id.copy)
            R.id.edit_paste -> editor.onTextContextMenuItem(android.R.id.pasteAsPlainText)
            R.id.edit_delete -> deleteSelection()
            R.id.edit_select_all -> editor.onTextContextMenuItem(android.R.id.selectAll)
            R.id.action_new, R.id.file_new -> runWithSavePrompt(PendingAction(PendingAction.Kind.NEW))
            R.id.action_open, R.id.file_open -> runWithSavePrompt(PendingAction(PendingAction.Kind.OPEN_PICKER))
            R.id.action_save, R.id.file_save -> save()
            R.id.file_save_as -> saveAs()
            R.id.recent_clear -> recents.clear().forEach { releaseIfUnused(Uri.parse(it.uri)) }
            R.id.reopen_utf8 -> reopenWith(Encoding.UTF8)
            R.id.reopen_utf8_bom -> reopenWith(Encoding.UTF8_BOM)
            R.id.reopen_utf16le -> reopenWith(Encoding.UTF16LE)
            R.id.reopen_utf16be -> reopenWith(Encoding.UTF16BE)
            R.id.reopen_ansi -> reopenWith(Encoding.ANSI)
            R.id.file_default_folder -> showDefaultFolder()
            R.id.file_exit -> exitApp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun reopenWith(encoding: Encoding) {
        if (doc.uri == null) return
        runWithSavePrompt(PendingAction(PendingAction.Kind.REOPEN_WITH, extra = encoding.name))
    }

    private fun clipboardHasText(): Boolean {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return false
        val desc = cm.primaryClipDescription ?: return false
        return cm.hasPrimaryClip() &&
            (desc.hasMimeType("text/*") || desc.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateOptionsMenu()
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
            KeyEvent.KEYCODE_O -> runWithSavePrompt(PendingAction(PendingAction.Kind.OPEN_PICKER))
            KeyEvent.KEYCODE_S -> if (shift) saveAs() else save()
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

    private fun showMessage(message: String) {
        showDialog(AlertDialog.Builder(this).setMessage(message).setPositiveButton(R.string.ok, null))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showProgress(show: Boolean) {
        val main = session.work.main
        main.removeCallbacks(showProgressRunnable)
        if (show) main.postDelayed(showProgressRunnable, PROGRESS_DELAY_MS) else progress.visibility = View.GONE
    }

    companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        const val LARGE_FILE_CHARS = 1_000_000
        const val LONG_LINE_CHARS = 100_000
        const val RECOVERY_DELAY_MS = 3000L
        const val RECOVERY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val PROGRESS_DELAY_MS = 300L
        const val STAT_THROTTLE_MS = 2000L
        const val WIDE_DP = 600
        const val RECENT_BASE = 0x7000
        const val REQ_OPEN = 1
        const val REQ_CREATE = 2
        const val REQ_TREE = 3
        const val EXTRA_HANDLED = "io.github.kaustubhowmick.plaintext.HANDLED"

        val OPEN_MIME_TYPES = arrayOf(
            "text/*", "application/json", "application/xml", "application/x-subrip", "application/octet-stream",
        )
    }
}
