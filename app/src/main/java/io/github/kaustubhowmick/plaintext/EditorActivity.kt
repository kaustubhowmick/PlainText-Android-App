package io.github.kaustubhowmick.plaintext

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Toolbar

/** The only activity. Phase 1 scaffold: a toolbar and a plain text area. */
class EditorActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        setActionBar(findViewById<Toolbar>(R.id.toolbar))
        actionBar?.title = getString(R.string.untitled)
        applyEdgeToEdge(findViewById(R.id.root))
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
}
