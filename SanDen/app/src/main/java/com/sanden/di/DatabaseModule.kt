package com.sanden.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sanden.data.db.SanDenDatabase
import com.sanden.data.db.SyncMessageDao
import com.sanden.data.db.VaultFileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "sanden_vault.db"

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_messages ADD COLUMN targetPeerId TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_messages ADD COLUMN msgUid TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE sync_messages ADD COLUMN fileHash TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_messages_msgUid ON sync_messages(msgUid)")
            db.execSQL("ALTER TABLE vault_files ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''")
        }
    }

    @Provides
    @Singleton
    fun provideSanDenDatabase(
        @ApplicationContext context: Context
    ): SanDenDatabase {
        return Room.databaseBuilder(
            context,
            SanDenDatabase::class.java,
            DATABASE_NAME
        )
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideVaultFileDao(
        database: SanDenDatabase
    ): VaultFileDao {
        return database.vaultFileDao()
    }

    @Provides
    @Singleton
    fun provideSyncMessageDao(
        database: SanDenDatabase
    ): SyncMessageDao {
        return database.syncMessageDao()
    }
}
