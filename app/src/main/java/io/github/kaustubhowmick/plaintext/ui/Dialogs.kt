package io.github.kaustubhowmick.plaintext.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import io.github.kaustubhowmick.plaintext.R
import io.github.kaustubhowmick.plaintext.io.Encoding
import io.github.kaustubhowmick.plaintext.io.FileNames
import io.github.kaustubhowmick.plaintext.io.LineEnding

/**
 * Builders for the app's custom dialogs (design.md §6). Each takes a [show]
 * function so the activity can track the dialog that is currently open.
 */
object Dialogs {

    class SaveChoice(val name: String?, val encoding: Encoding, val lineEnding: LineEnding, val useForNew: Boolean)

    /**
     * Save options (design.md §6.7). With [folderName] set it is the "save to the
     * default folder" dialog with a name field; otherwise it is the Save As
     * dialog that continues to the system picker.
     */
    fun saveOptions(
        a: Activity,
        show: (AlertDialog.Builder) -> AlertDialog,
        folderName: String?,
        proposedName: String?,
        encoding: Encoding,
        lineEnding: LineEnding,
        onOtherLocation: ((SaveChoice) -> Unit)?,
        onCancel: () -> Unit,
        onConfirm: (SaveChoice) -> Unit,
    ) {
        val view = a.layoutInflater.inflate(R.layout.dialog_save, null)
        val folderMode = folderName != null
        val folder = view.findViewById<TextView>(R.id.save_folder)
        val nameLabel = view.findViewById<View>(R.id.save_name_label)
        val name = view.findViewById<EditText>(R.id.save_name)
        val error = view.findViewById<TextView>(R.id.save_name_error)
        val enc = view.findViewById<Spinner>(R.id.save_encoding)
        val eol = view.findViewById<Spinner>(R.id.save_eol)
        val useForNew = view.findViewById<CheckBox>(R.id.save_default)
        view.findViewById<View>(R.id.save_hint).visibility = if (folderMode) View.GONE else View.VISIBLE

        if (folderMode) {
            folder.text = a.getString(R.string.folder_label, folderName)
            name.setText(proposedName ?: "")
            name.setSelection(0, FileNames.baseLength(name.text.toString()))
        } else {
            folder.visibility = View.GONE
            nameLabel.visibility = View.GONE
            name.visibility = View.GONE
        }
        enc.adapter = spinnerAdapter(a, Encoding.entries.map { it.label })
        enc.setSelection(encoding.ordinal)
        eol.adapter = spinnerAdapter(a, LineEnding.entries.map { it.label })
        eol.setSelection(lineEnding.ordinal)

        fun choice(n: String?) = SaveChoice(
            n, Encoding.entries[enc.selectedItemPosition], LineEnding.entries[eol.selectedItemPosition], useForNew.isChecked,
        )

        val builder = AlertDialog.Builder(a)
            .setTitle(if (folderMode) R.string.save else R.string.save_as)
            .setView(view)
            .setPositiveButton(if (folderMode) R.string.save else R.string.choose_location, null)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
        if (folderMode && onOtherLocation != null) {
            builder.setNeutralButton(R.string.other_location) { _, _ -> onOtherLocation(choice(null)) }
        }
        val dialog = show(builder)
        if (folderMode) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (!folderMode) {
                dialog.dismiss()
                onConfirm(choice(null))
                return@setOnClickListener
            }
            val typed = name.text.toString().trim()
            val problem = when {
                typed.isEmpty() -> a.getString(R.string.empty_name)
                FileNames.hasInvalidChars(typed) -> a.getString(R.string.invalid_name_chars)
                FileNames.sanitize(typed).isEmpty() -> a.getString(R.string.empty_name)
                else -> null
            }
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                name.requestFocus()
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirm(choice(FileNames.limitBytes(FileNames.ensureExtension(FileNames.sanitize(typed)))))
        }
    }

    // ------------------------------------------------------------ Go To (§6.5)

    fun goToLine(a: Activity, show: (AlertDialog.Builder) -> AlertDialog, current: Int, lineCount: Int, onGo: (Int) -> Unit) {
        val pad = dp(a, 24)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(a, 8), pad, 0)
        }
        val label = TextView(a).apply { text = a.getString(R.string.line_number) }
        val field = EditText(a).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSingleLine = true
            minHeight = dp(a, 48)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(current.toString())
            selectAll()
        }
        label.labelFor = field.id
        val error = errorText(a, a.getString(R.string.line_out_of_range))
        box.addView(label)
        box.addView(field)
        box.addView(error)
        val dialog = show(
            AlertDialog.Builder(a)
                .setTitle(R.string.go_to_line)
                .setView(box)
                .setPositiveButton(R.string.go_to, null)
                .setNegativeButton(R.string.cancel, null)
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val go = {
            val n = field.text.toString().toIntOrNull()
            if (n == null || n < 1 || n > lineCount) {
                error.visibility = View.VISIBLE
            } else {
                dialog.dismiss()
                onGo(n)
            }
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { go() }
        field.setOnEditorActionListener { _, _, _ ->
            go()
            true
        }
    }

    // ------------------------------------------------------------- Font (§6.6)

    class FontChoice(val family: String, val style: Int, val sizeSp: Int)

    val FONT_FAMILIES = listOf("monospace", "sans-serif", "serif", "sans-serif-condensed", "serif-monospace")
    val FONT_SIZES = listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72)
    private val FONT_STYLES = listOf(Typeface.NORMAL, Typeface.ITALIC, Typeface.BOLD, Typeface.BOLD_ITALIC)

    fun font(a: Activity, show: (AlertDialog.Builder) -> AlertDialog, current: FontChoice, onOk: (FontChoice) -> Unit) {
        val familyNames = a.resources.getStringArray(R.array.font_families)
        val styleNames = a.resources.getStringArray(R.array.font_styles)
        val pad = dp(a, 24)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(a, 8), pad, 0)
        }
        val wide = a.resources.configuration.screenWidthDp >= 480
        val columns = LinearLayout(a).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        fun column(title: Int): LinearLayout = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (wide) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(a).apply {
                text = a.getString(title)
                setPadding(0, dp(a, 8), 0, 0)
            })
        }
        val families = RadioGroup(a)
        FONT_FAMILIES.forEachIndexed { i, f -> families.addView(radio(a, familyNames[i], i, f == current.family)) }
        val styles = RadioGroup(a)
        FONT_STYLES.forEachIndexed { i, st -> styles.addView(radio(a, styleNames[i], i, st == current.style)) }
        val sizes = Spinner(a).apply {
            adapter = spinnerAdapter(a, FONT_SIZES.map { it.toString() })
            setSelection(FONT_SIZES.indexOf(current.sizeSp).coerceAtLeast(0))
            minimumHeight = dp(a, 48)
            contentDescription = a.getString(R.string.font_size)
        }
        columns.addView(column(R.string.font_family).apply { addView(families) })
        columns.addView(column(R.string.font_style).apply { addView(styles) })
        columns.addView(column(R.string.font_size).apply { addView(sizes) })
        root.addView(columns)
        root.addView(TextView(a).apply {
            text = a.getString(R.string.sample)
            setPadding(0, dp(a, 12), 0, 0)
        })
        val sample = TextView(a).apply {
            text = "AaBbYyZz 0Oo1lI"
            setPadding(0, dp(a, 8), 0, dp(a, 8))
        }
        root.addView(sample)

        fun selected(): FontChoice {
            val fi = families.indexOfChild(families.findViewById(families.checkedRadioButtonId)).coerceAtLeast(0)
            val si = styles.indexOfChild(styles.findViewById(styles.checkedRadioButtonId)).coerceAtLeast(0)
            return FontChoice(FONT_FAMILIES[fi], FONT_STYLES[si], FONT_SIZES[sizes.selectedItemPosition.coerceAtLeast(0)])
        }
        fun preview() {
            val c = selected()
            sample.typeface = Typeface.create(c.family, c.style)
            sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.sizeSp.toFloat())
        }
        families.setOnCheckedChangeListener { _, _ -> preview() }
        styles.setOnCheckedChangeListener { _, _ -> preview() }
        sizes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = preview()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        preview()
        show(
            AlertDialog.Builder(a)
                .setTitle(R.string.font)
                .setView(ScrollView(a).apply { addView(root) })
                .setPositiveButton(R.string.ok) { _, _ -> onOk(selected()) }
                .setNegativeButton(R.string.cancel, null)
        )
    }

    // ------------------------------------------------------- Page Setup (§6.8)

    class PageSetup(val marginsMm: IntArray, val header: String, val footer: String, val fontSizePt: Int)

    private val PRINT_SIZES = listOf(8, 9, 10, 11, 12, 14, 16)

    fun pageSetup(a: Activity, show: (AlertDialog.Builder) -> AlertDialog, current: PageSetup, onOk: (PageSetup) -> Unit) {
        val pad = dp(a, 24)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(a, 8), pad, 0)
        }
        root.addView(TextView(a).apply { text = a.getString(R.string.margins_mm) })
        val labels = listOf(R.string.margin_left, R.string.margin_right, R.string.margin_top, R.string.margin_bottom)
        val fields = labels.mapIndexed { i, l -> numberField(a, current.marginsMm[i], a.getString(l)) }
        for (row in 0..1) {
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0..1) {
                val i = row * 2 + col
                line.addView(labeled(a, labels[i], fields[i]), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(line)
        }
        val marginError = errorText(a, a.getString(R.string.margin_range))
        root.addView(marginError)
        val header = textField(a, current.header)
        val footer = textField(a, current.footer)
        root.addView(labeled(a, R.string.header, header))
        root.addView(labeled(a, R.string.footer, footer))
        val size = Spinner(a).apply {
            adapter = spinnerAdapter(a, PRINT_SIZES.map { it.toString() })
            setSelection(PRINT_SIZES.indexOf(current.fontSizePt).coerceAtLeast(0))
            minimumHeight = dp(a, 48)
        }
        root.addView(labeled(a, R.string.print_font_size, size))
        root.addView(TextView(a).apply {
            text = a.getString(R.string.page_codes)
            setPadding(0, dp(a, 8), 0, 0)
        })
        root.addView(TextView(a).apply {
            text = a.getString(R.string.paper_in_print_dialog)
            setPadding(0, dp(a, 8), 0, dp(a, 8))
        })

        val dialog = show(
            AlertDialog.Builder(a)
                .setTitle(R.string.page_setup)
                .setView(ScrollView(a).apply { addView(root) })
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
        )
        val ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        fun values(): IntArray? {
            val v = fields.map { it.text.toString().toIntOrNull() }
            return if (v.all { it != null && it in 0..100 }) v.map { it!! }.toIntArray() else null
        }
        val validate = {
            val valid = values() != null
            ok.isEnabled = valid
            marginError.visibility = if (valid) View.GONE else View.VISIBLE
        }
        fields.forEach { f ->
            f.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) = validate()
            })
        }
        ok.setOnClickListener {
            val m = values() ?: return@setOnClickListener
            dialog.dismiss()
            onOk(PageSetup(m, header.text.toString(), footer.text.toString(), PRINT_SIZES[size.selectedItemPosition.coerceAtLeast(0)]))
        }
    }

    // -------------------------------------------------------------- helpers

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun radio(a: Activity, label: String, index: Int, checked: Boolean) = RadioButton(a).apply {
        id = View.generateViewId()
        text = label
        tag = index
        minHeight = dp(a, 48)
        isChecked = checked
    }

    private fun errorText(a: Activity, message: String) = TextView(a).apply {
        text = message
        val tv = TypedValue()
        if (a.theme.resolveAttribute(android.R.attr.colorError, tv, true)) setTextColor(tv.data)
        visibility = View.GONE
    }

    private fun numberField(a: Activity, value: Int, description: String) = EditText(a).apply {
        id = View.generateViewId()
        inputType = InputType.TYPE_CLASS_NUMBER
        isSingleLine = true
        minHeight = dp(a, 48)
        minEms = 3
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        contentDescription = description
        setText(value.toString())
    }

    private fun textField(a: Activity, value: String) = EditText(a).apply {
        id = View.generateViewId()
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
        minHeight = dp(a, 48)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setText(value)
    }

    private fun labeled(a: Activity, label: Int, field: View) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(a).apply {
            text = a.getString(label)
            minWidth = dp(a, 64)
            if (field.id != View.NO_ID) labelFor = field.id
        })
        addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun spinnerAdapter(a: Activity, items: List<String>) =
        ArrayAdapter(a, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}
