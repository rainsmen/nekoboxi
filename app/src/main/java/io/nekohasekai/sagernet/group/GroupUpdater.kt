package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    enum class Outcome { UPDATED, SKIPPED, RETRYABLE_FAILURE, CONTENT_FAILURE }

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    protected suspend fun forceResolve(profiles: List<AbstractBean>, groupId: Long?) {
        val ipv6First = DataStore.ipv6Mode >= IPv6Mode.PREFER
        val progress = Progress(profiles.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postReload(groupId)
        }
        newFixedThreadPoolContext(5, "DNS Lookup").use { lookupPool ->
            coroutineScope {
                for (profile in profiles) {
                    if (profile is NaiveBean || profile.serverAddress.isIpAddress()) continue
                    launch(lookupPool) {
                        try {
                            val network = SagerNet.underlyingNetwork
                            val results = if (network != null && DataStore.enableFakeDns &&
                                DataStore.serviceState.started && DataStore.serviceMode == Key.MODE_VPN) {
                                network.getAllByName(profile.serverAddress).filterNotNull()
                            } else {
                                InetAddress.getAllByName(profile.serverAddress).filterNotNull()
                            }
                            ensureActive()
                            check(results.isNotEmpty()) { "empty response" }
                            rewriteAddress(profile, results, ipv6First)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Logs.d("Lookup ${profile.serverAddress} failed: ${error.readableMessage}", error)
                        }
                        if (groupId != null) {
                            progress.progress++
                            GroupManager.postReload(groupId)
                        }
                    }
                }
            }
        }
    }

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet4Address) xor ipv6First }[0].hostAddress

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni.isBlank()) sni = bean.serverAddress
                }
                is StandardV2RayBean -> {
                    when (security) {
                        "tls" -> if (sni.isBlank()) sni = bean.serverAddress
                    }
                }
                is TrojanBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is TrojanGoBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is HysteriaBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean =
            executeUpdateResult(proxyGroup, byUser) == Outcome.UPDATED

        internal suspend fun executeUpdateResult(proxyGroup: ProxyGroup, byUser: Boolean,
            perform: (suspend (ProxyGroup, SubscriptionBean, GroupManager.Interface?, Boolean) -> Unit)? = null): Outcome {
            // A duplicate caller never owns the marker and must not clear the active update.
            if (!updating.add(proxyGroup.id)) return Outcome.SKIPPED
            var request = proxyGroup
            var generation: String? = null
            try {
                GroupManager.postReload(proxyGroup.id)
                generation = io.nekohasekai.sagernet.database.backup.BackupRestore.generation(
                    io.nekohasekai.sagernet.database.preference.PublicDatabase.instance)
                val snapshot = io.nekohasekai.sagernet.database.SagerDatabase.groupDao.getById(proxyGroup.id)
                    ?: throw io.nekohasekai.sagernet.database.DataValidationException(
                        io.nekohasekai.sagernet.database.DataValidationException.Reason.GROUP_REMOVED)
                request = snapshot
                val subscription = snapshot.subscription ?: return Outcome.SKIPPED
                val connected = DataStore.serviceState.connected
                val userInterface = GroupManager.userInterface
                if (byUser && (subscription.link.startsWith("http://") || subscription.updateWhenConnectedOnly) && !connected) {
                    if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning)))
                        return Outcome.SKIPPED
                }
                if (perform == null) RawUpdater.update(snapshot, subscription, userInterface, byUser,
                    expectedGeneration = requireNotNull(generation))
                else perform(snapshot, subscription, userInterface, byUser)
                return Outcome.UPDATED
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logs.w(error)
                try {
                    if (generation != null) SubscriptionUpdate.recordFailure(
                        io.nekohasekai.sagernet.database.SagerDatabase.instance, request, error.readableMessage,
                        generation, io.nekohasekai.sagernet.database.preference.PublicDatabase.instance)
                } catch (storageError: Exception) { Logs.w(storageError) }
                GroupManager.userInterface?.onUpdateFailure(proxyGroup, error)
                return if (error is RawUpdater.DownloadFailure && error.retryable) Outcome.RETRYABLE_FAILURE else Outcome.CONTENT_FAILURE
            } finally {
                withContext(NonCancellable) {
                    try {
                        finishUpdate(proxyGroup)
                    } catch (error: Exception) {
                        Logs.w(error)
                    }
                }
            }
        }

        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup.id)
        }


    }

}