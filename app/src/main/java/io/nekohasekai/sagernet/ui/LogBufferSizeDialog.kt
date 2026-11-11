package io.nekohasekai.sagernet.ui

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

internal fun showLogBufferSizeDialog(context: Context, onSaved: () -> Unit): AlertDialog {
    val view = EditText(context).apply {
        inputType = EditorInfo.TYPE_CLASS_NUMBER
        setText(DataStore.logBufSize.takeIf { it > 0 }?.toString() ?: "50")
    }
    val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.log_buffer_size)
        .setView(view)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val size = view.text.toString().toIntOrNull()?.takeIf { it in 50..65536 }
            if (size == null) {
                view.error = context.getString(R.string.log_buffer_size_invalid)
            } else {
                DataStore.logBufSize = size
                dialog.dismiss()
                onSaved()
            }
        }
    }
    dialog.show()
    return dialog
}
