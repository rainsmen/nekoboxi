package io.nekohasekai.sagernet.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object LegacyMigrations {
    val FROM_1 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE proxy_groups_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userOrder` INTEGER NOT NULL, `ungrouped` INTEGER NOT NULL, `name` TEXT, `type` INTEGER NOT NULL, `subscription` BLOB, `order` INTEGER NOT NULL, `isSelector` INTEGER NOT NULL, `frontProxy` INTEGER NOT NULL, `landingProxy` INTEGER NOT NULL)")
            db.execSQL("INSERT INTO proxy_groups_new SELECT id, userOrder, ungrouped, name, type, subscription, `order`, 0, -1, -1 FROM proxy_groups")
            db.execSQL("DROP TABLE proxy_groups")
            db.execSQL("ALTER TABLE proxy_groups_new RENAME TO proxy_groups")
            db.execSQL("ALTER TABLE proxy_entities ADD COLUMN shadowTLSBean BLOB")
        }
    }
    val FROM_2 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE proxy_entities ADD COLUMN mieruBean BLOB")
        }
    }
}
