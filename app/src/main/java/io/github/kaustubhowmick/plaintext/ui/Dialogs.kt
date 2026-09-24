package io.github.kaustubhowmick.plaintext.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
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

    fun spinnerAdapter(a: Activity, items: List<String>) =
        ArrayAdapter(a, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}
