package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProfileMutations
import io.nekohasekai.sagernet.database.ProfileMutations.PendingDeletion
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.util.*

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<PendingDeletion<ProxyGroup>>

    internal var pendingExport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("pendingProfileExport")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingProfileExport", pendingExport)
        super.onSaveInstanceState(outState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView = view.findViewById(R.id.group_list)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                val pending = PendingDeletion((viewHolder as GroupHolder).proxyGroup)
                groupAdapter.remove(index)
                undoManager.remove(index to pending)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        }).attachToRecyclerView(groupListView)

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }

            R.id.action_update_all -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.update_all_subscription)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.SUBSCRIPTION }
                            .forEach {
                                GroupUpdater.startUpdate(it, true)
                            }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
        return true
    }

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            val token = pendingExport
            val context = requireContext().applicationContext
            val snapshots = io.nekohasekai.sagernet.database.backup.ProfileExportSnapshot(context.cacheDir)
            runOnDefaultDispatcher {
                try {
                    if (data != null) {
                        checkNotNull(token) { context.getString(R.string.profile_export_expired) }
                        snapshots.write(token) {
                            context.contentResolver.openOutputStream(data)
                                ?: throw java.io.IOException(context.getString(R.string.asset_cannot_read))
                        }
                        onMainDispatcher { if (isAdded) snackbar(R.string.action_export_msg).show() }
                    }
                } catch (error: Exception) {
                    Logs.w(error)
                    onMainDispatcher { if (isAdded) snackbar(getString(R.string.profile_export_failed, error.readableMessage)).show() }
                } finally {
                    token?.let { snapshots.discard(it) }
                    onMainDispatcher { if (pendingExport == token) pendingExport = null }
                }
            }
        }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<PendingDeletion<ProxyGroup>> {

        val groupList = ArrayList<ProxyGroup>()

        suspend fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            if (groups.size > 1 && SagerDatabase.proxyDao.countByGroup(groups.find { it.ungrouped }!!.id) == 0L) groups.removeAll { it.ungrouped }
            groupList.clear()
            groupList.addAll(groups)
            groupListView.post {
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.toList().forEach { SagerDatabase.groupDao.updateOrder(it.id, it.userOrder) }
            updated.clear()
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, PendingDeletion<ProxyGroup>>>) {
            runOnDefaultDispatcher { reload() }
        }

        override fun commit(actions: List<Pair<Int, PendingDeletion<ProxyGroup>>>) {
            val requests = actions.map { it.second }
            runOnDefaultDispatcher {
                runCatching { GroupManager.deleteGroup(requests.map { it.item },
                    ProfileMutations.deletionGeneration(requests)) }.onFailure { error ->
                    onMainDispatcher { requireContext().showDataError(error) }
                }
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            groupList.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            groupList[index] = group
            onMainDispatcher {
                undoManager.flush()

                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }

    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        private var bindingGeneration = 0L
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupUser = binding.groupUser
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

        override fun onMenuItemClick(item: MenuItem): Boolean {

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            when (item.itemId) {
                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        proxyGroup.toUniversalLink(), proxyGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    export(proxyGroup.toUniversalLink())
                }

                R.id.action_export_clipboard -> {
                    val groupId = proxyGroup.id
                    runOnDefaultDispatcher {
                        val result = runCatching {
                            SagerDatabase.proxyDao.getByGroup(groupId).joinToString("\n") { it.toStdLink(compact = true) }
                        }
                        onMainDispatcher {
                            result.onSuccess { export(it) }
                                .onFailure { snackbar(it.readableMessage).show() }
                        }
                    }
                }

                R.id.action_export_file -> {
                    val groupId = proxyGroup.id
                    val fileName = "profiles_${proxyGroup.displayName()}.txt"
                    val snapshots = io.nekohasekai.sagernet.database.backup.ProfileExportSnapshot(requireContext().cacheDir)
                    runOnDefaultDispatcher {
                        runCatching {
                            val links = SagerDatabase.proxyDao.getByGroup(groupId).joinToString("\n") { it.toStdLink(compact = true) }
                            snapshots.stage(links)
                        }.onSuccess { token ->
                            onMainDispatcher {
                                if (isAdded) {
                                    pendingExport?.let { snapshots.discard(it) }
                                    pendingExport = token
                                    startFilesForResult(exportProfiles, fileName)
                                } else snapshots.discard(token)
                            }
                        }.onFailure { error -> onMainDispatcher {
                            if (isAdded) snackbar(getString(R.string.profile_export_failed, error.readableMessage)).show()
                        } }
                    }
                }

                R.id.action_clear -> {
                    val generation = ProfileMutations.currentGeneration()
                    val groupId = proxyGroup.id
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(getString(R.string.clear_group_confirmation, proxyGroup.displayName(),
                            SagerDatabase.proxyDao.countByGroup(proxyGroup.id)))
                        .setPositiveButton(R.string.clear_group_action) { _, _ ->
                            runOnDefaultDispatcher {
                                runCatching { GroupManager.clearGroup(groupId, expectedGeneration = generation) }.onFailure { error ->
                                    onMainDispatcher { requireContext().showDataError(error) }
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            return true
        }


        fun bind(group: ProxyGroup) {
            val binding = ++bindingGeneration
            proxyGroup = group
            groupTraffic.text = ""
            groupTraffic.isVisible = false
            groupStatus.setPadding(0, 0, 0, dp2px(4))
            optionsButton.contentDescription = getString(R.string.group_more_actions, group.displayName())

            itemView.setOnClickListener {
                group.subscription?.takeIf { it.lastError.isNotBlank() }?.let { subscription ->
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.subscription_update_failed)
                        .setMessage(getString(R.string.subscription_failure_details,
                            java.text.DateFormat.getDateTimeInstance().format(Date(subscription.lastAttempted)),
                            subscription.lastError))
                        .setPositiveButton(R.string.subscription_retry) { _, _ -> GroupUpdater.startUpdate(group, true) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }

            editButton.isGone = proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            if (proxyGroup.id in GroupUpdater.updating) {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[proxyGroup.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isGone = proxyGroup.ungrouped
            }

            val subscription = proxyGroup.subscription
            if (subscription != null && subscription.bytesUsed > 0L) { // SIP008 & Open Online Config
                groupTraffic.isVisible = true
                groupTraffic.text = if (subscription.bytesRemaining > 0L) {
                    app.getString(
                        R.string.subscription_traffic, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        ), Formatter.formatFileSize(
                            app, subscription.bytesRemaining
                        )
                    )
                } else {
                    app.getString(
                        R.string.subscription_used, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        )
                    )
                }
                groupStatus.setPadding(0)
            } else if (subscription != null && !subscription.subscriptionUserinfo.isNullOrBlank()) { // Raw
                var text = ""

                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscription.subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }

                try {
                    var used: Long = 0
                    get("upload=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    get("download=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    val total = get("total=([0-9]+)")?.toLong() ?: 0
                    val remain = total - used
                    if (used > 0 || total > 0) {
                        text += if (remain > 0) {
                            getString(
                                R.string.subscription_traffic,
                                used.toBytesString(),
                                remain.toBytesString()
                            )
                        } else {
                            getString(R.string.subscription_used, used.toBytesString())
                        }
                    }
                    get("expire=([0-9]+)")?.apply {
                        text += "\n"
                        text += getString(
                            R.string.subscription_expire,
                            Util.timeStamp2Text(this.toLong() * 1000)
                        )
                    }
                } catch (_: NumberFormatException) {
                    // ignore
                }

                if (text.isNotEmpty()) {
                    groupTraffic.isVisible = true
                    groupTraffic.text = text
                    groupStatus.setPadding(0)
                }
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            groupUser.text = subscription?.username ?: ""

            runOnDefaultDispatcher {
                val size = SagerDatabase.proxyDao.countByGroup(group.id)
                onMainDispatcher {
                    if (binding != bindingGeneration) return@onMainDispatcher
                    @Suppress("DEPRECATION") when (group.type) {
                        GroupType.BASIC -> {
                            if (size == 0L) {
                                groupStatus.setText(R.string.group_status_empty)
                            } else {
                                groupStatus.text = getString(R.string.group_status_proxies, size)
                            }
                        }

                        GroupType.SUBSCRIPTION -> {
                            groupStatus.text = if (group.subscription!!.lastError.isNotBlank()) {
                                getString(R.string.subscription_failed_kept, size)
                            } else if (group.subscription!!.lastUpdated == 0) {
                                getString(R.string.group_status_empty_subscription)
                            } else {
                                val date = Date(group.subscription!!.lastUpdated * 1000L)
                                getString(
                                    R.string.group_status_proxies_subscription,
                                    size,
                                    "${date.month + 1} - ${date.date}"
                                )
                            }

                        }
                    }
                }

            }

        }
    }

}