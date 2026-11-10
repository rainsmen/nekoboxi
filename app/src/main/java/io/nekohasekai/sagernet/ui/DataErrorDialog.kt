package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataValidationException
import io.nekohasekai.sagernet.database.DataValidationException.Reason
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.readableMessage

fun Context.showDataError(error: Throwable, message: String = error.readableMessage) {
    val validation = error as? DataValidationException
    val owner = (validation?.details?.getOrNull(1) as? Number)?.toLong()
    val destination = if (validation?.fromBackup == true) null else when (validation?.reason) {
        Reason.MISSING_FRONT, Reason.MISSING_LANDING -> owner?.let {
            if (SagerDatabase.groupDao.getById(it) == null) null else
                Intent(this, GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, it)
        }
        Reason.MISSING_RULE_TARGET -> owner?.let {
            if (SagerDatabase.rulesDao.getById(it) == null) null else
                Intent(this, RouteSettingsActivity::class.java).putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, it)
        }
        Reason.MISSING_CHAIN_TARGET, Reason.CHAIN_CYCLE -> owner?.let { id ->
            SagerDatabase.proxyDao.getById(id)?.let { profile ->
                profile.settingIntent(this, SagerDatabase.groupDao.getById(profile.groupId)?.type == GroupType.SUBSCRIPTION)
            }
        }
        Reason.SELECTED_NODE_REMOVED -> Intent(this, SwitchActivity::class.java)
        else -> null
    }
    val explanation = if (validation?.fromBackup == true) "$message\n\n${getString(R.string.backup_reference_source)}" else message
    MaterialAlertDialogBuilder(this).setTitle(R.string.error_title).setMessage(explanation)
        .setPositiveButton(android.R.string.ok, null).apply {
            if (destination != null) setNeutralButton(R.string.review_reference) { _, _ -> startActivity(destination) }
        }.show()
}
