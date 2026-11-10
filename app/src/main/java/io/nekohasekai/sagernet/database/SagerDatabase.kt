package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.appScope
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DatabaseConfiguration
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 7,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7)
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {
    private lateinit var databaseContext: Context

    override fun init(configuration: DatabaseConfiguration) {
        databaseContext = configuration.context.applicationContext
        super.init(configuration)
    }

    /** ATTACH is connection state: never expose it to normal DAO transactions. */
    internal fun isolatedConnection(): SagerDatabase {
        val path = requireNotNull(openHelper.writableDatabase.path)
        require(path.isNotBlank() && path != ":memory:") { "Cross-database transactions require persistent databases" }
        check(!inTransaction()) { "Cross-database transactions cannot be nested" }
        return Room.databaseBuilder(databaseContext, SagerDatabase::class.java, path)
            .addMigrations(LegacyMigrations.FROM_1, LegacyMigrations.FROM_2)
            .setJournalMode(JournalMode.TRUNCATE)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .build()
    }

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(LegacyMigrations.FROM_1, LegacyMigrations.FROM_2)
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .setQueryExecutor { appScope.launch { it.run() } }
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
