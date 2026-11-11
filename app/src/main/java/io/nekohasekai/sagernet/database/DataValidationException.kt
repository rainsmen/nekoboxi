package io.nekohasekai.sagernet.database

import android.content.Context
import io.nekohasekai.sagernet.R

/** Stable failure categories; user-facing text is localized at the UI boundary. */
class DataValidationException(val reason: Reason, vararg val details: Any) :
    IllegalArgumentException("${reason.name}: ${details.joinToString()}") {
    var fromBackup: Boolean = false
    enum class Reason(val message: Int) {
        NAIVE_HEADER(R.string.naive_header_invalid),
        UNSUPPORTED_ENDPOINT(R.string.outbound_endpoint_unsupported),
        OUTBOUND_DEPENDENCY(R.string.outbound_dependency_invalid),
        CONFIG_CHANGED(R.string.configuration_changed_retry),
        IMPORT_GROUP_REQUIRED(R.string.import_group_required),
        IMPORTED_LOCAL_FILE(R.string.imported_local_file),
        INVALID_SUBSCRIPTION_FIELD(R.string.subscription_field_invalid),
        INVALID_SUBSCRIPTION_ENTRIES(R.string.subscription_entries_invalid),
        EMPTY_SUBSCRIPTION(R.string.subscription_empty_kept),
        DUPLICATE_NAMES(R.string.subscription_duplicate_names),
        AMBIGUOUS_NODE(R.string.subscription_ambiguous_node),
        RESTORED_DURING_DELETE(R.string.deletion_restore_changed),
        RESTORED_DURING_UPDATE(R.string.subscription_restore_changed),
        GROUP_REMOVED(R.string.subscription_group_removed),
        SUBSCRIPTION_CHANGED(R.string.subscription_changed_retry),
        SELECTED_NODE_REMOVED(R.string.subscription_selected_removed),
        MISSING_GROUP(R.string.reference_missing_group),
        MISSING_FRONT(R.string.reference_missing_front),
        MISSING_LANDING(R.string.reference_missing_landing),
        MISSING_RULE_TARGET(R.string.reference_missing_rule_target),
        MISSING_CHAIN_TARGET(R.string.reference_missing_chain_target),
        EMPTY_CHAIN(R.string.reference_empty_chain),
        CHAIN_TOO_LARGE(R.string.reference_chain_too_large),
        CHAIN_CYCLE(R.string.reference_chain_cycle),
        UNKNOWN_IDENTITY(R.string.backup_unknown_identity),
    }
    fun localize(context: Context): String = context.getString(reason.message, *details)
}
