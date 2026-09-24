package io.github.kaustubhowmick.plaintext.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ToggleButton
import io.github.kaustubhowmick.plaintext.R

/** The inline Find / Replace bar under the toolbar (design.md §6.4). */
class FindBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    interface Listener {
        fun onFind(forward: Boolean)
        fun onReplace()
        fun onReplaceAll()
        fun onFindOptionsChanged(matchCase: Boolean, wrap: Boolean)
        fun onFindClosed()
    }

    var listener: Listener? = null

    val findField: EditText
    val replaceField: EditText
    private val prev: ImageButton
    private val next: ImageButton
    private val matchCase: ToggleButton
    private val wrap: ToggleButton
    private val replaceRow: View
    private val replaceOne: Button
    private val replaceAll: Button

    val term: String get() = findField.text.toString()
    val replacement: String get() = replaceField.text.toString()
    val isReplaceVisible: Boolean get() = replaceRow.visibility == VISIBLE

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.find_bar, this, true)
        findField = findViewById(R.id.find_field)
        replaceField = findViewById(R.id.replace_field)
        prev = findViewById(R.id.find_prev)
        next = findViewById(R.id.find_next)
        matchCase = findViewById(R.id.find_match_case)
        wrap = findViewById(R.id.find_wrap)
        replaceRow = findViewById(R.id.replace_row)
        replaceOne = findViewById(R.id.replace_one)
        replaceAll = findViewById(R.id.replace_all)

        prev.setOnClickListener { listener?.onFind(false) }
        next.setOnClickListener { listener?.onFind(true) }
        findViewById<View>(R.id.find_close).setOnClickListener { listener?.onFindClosed() }
        replaceOne.setOnClickListener { listener?.onReplace() }
        replaceAll.setOnClickListener { listener?.onReplaceAll() }
        val optionsChanged = { _: android.widget.CompoundButton, _: Boolean ->
            listener?.onFindOptionsChanged(matchCase.isChecked, wrap.isChecked)
            Unit
        }
        matchCase.setOnCheckedChangeListener(optionsChanged)
        wrap.setOnCheckedChangeListener(optionsChanged)

        findField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) = updateEnabled()
        })
        // Enter = Find Next, Shift+Enter = Find Previous (design.md §5.34).
        findField.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (event.action == KeyEvent.ACTION_DOWN) listener?.onFind(!event.isShiftPressed)
                true
            } else {
                false
            }
        }
        findField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH && event == null) {
                listener?.onFind(true)
                true
            } else {
                false
            }
        }
        updateEnabled()
    }

    fun setOptions(matchCaseOn: Boolean, wrapOn: Boolean) {
        matchCase.isChecked = matchCaseOn
        wrap.isChecked = wrapOn
    }

    fun showReplace(show: Boolean) {
        replaceRow.visibility = if (show) VISIBLE else GONE
    }

    private fun updateEnabled() {
        val has = findField.text.isNotEmpty()
        prev.isEnabled = has
        next.isEnabled = has
        replaceOne.isEnabled = has
        replaceAll.isEnabled = has
        prev.alpha = if (has) 1f else 0.38f
        next.alpha = if (has) 1f else 0.38f
    }
}
