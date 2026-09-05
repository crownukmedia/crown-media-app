package uk.crownmedia.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "catalog_categories",
    primaryKeys = ["playlistId", "kind", "categoryId"],
    indices = [Index(value = ["playlistId", "kind", "sortOrder"])],
)
data class CachedCategory(
    val playlistId: String,
    val kind: String,
    val categoryId: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "catalog_items",
    primaryKeys = ["playlistId", "kind", "contentId"],
    indices = [
        Index(value = ["playlistId", "kind", "categoryId"]),
        Index(value = ["playlistId", "kind", "providerOrder", "contentId"]),
        Index(value = ["playlistId", "kind", "normalizedTitle", "contentId"]),
    ],
)
data class CachedCatalogItem(
    val playlistId: String,
    val kind: String,
    val contentId: String,
    val categoryId: String,
    val title: String,
    val normalizedTitle: String,
    val imageUrl: String?,
    val rating: String?,
    val addedEpochSeconds: Long?,
    val extension: String?,
    val epgChannelId: String?,
    val catchUp: Boolean,
    val catchUpDays: Int,
    val providerOrder: Int = 0,
    val isAdult: Boolean = false,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "catalog_search_tokens",
    primaryKeys = ["playlistId", "kind", "contentId", "token"],
    indices = [Index(value = ["playlistId", "token"])],
)
data class CachedSearchToken(
    val playlistId: String,
    val kind: String,
    val contentId: String,
    val token: String,
)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_categories WHERE playlistId = :playlistId AND kind = :kind ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun categories(playlistId: String, kind: String): List<CachedCategory>

    @Query("SELECT * FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY providerOrder, contentId")
    suspend fun items(playlistId: String, kind: String, categoryId: String?): List<CachedCatalogItem>

    @Query("SELECT * FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY providerOrder, contentId LIMIT :limit OFFSET :offset")
    suspend fun itemPageProvider(playlistId: String, kind: String, categoryId: String?, limit: Int, offset: Int): List<CachedCatalogItem>

    @Query("SELECT * FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY normalizedTitle COLLATE NOCASE, contentId LIMIT :limit OFFSET :offset")
    suspend fun itemPageAscending(playlistId: String, kind: String, categoryId: String?, limit: Int, offset: Int): List<CachedCatalogItem>

    @Query("SELECT * FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY normalizedTitle COLLATE NOCASE DESC, contentId LIMIT :limit OFFSET :offset")
    suspend fun itemPageDescending(playlistId: String, kind: String, categoryId: String?, limit: Int, offset: Int): List<CachedCatalogItem>

    @Query("SELECT * FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND contentId IN (:contentIds) ORDER BY providerOrder, contentId LIMIT :limit OFFSET :offset")
    suspend fun favoriteItemPageProvider(playlistId: String, kind: String, contentIds: List<String>, limit: Int, offset: Int): List<CachedCatalogItem>

    @Query("SELECT * FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND contentId IN (:contentIds) ORDER BY normalizedTitle COLLATE NOCASE, contentId LIMIT :limit OFFSET :offset")
    suspend fun favoriteItemPageAscending(playlistId: String, kind: String, contentIds: List<String>, limit: Int, offset: Int): List<CachedCatalogItem>

    @Query("SELECT * FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND contentId IN (:contentIds) ORDER BY normalizedTitle COLLATE NOCASE DESC, contentId LIMIT :limit OFFSET :offset")
    suspend fun favoriteItemPageDescending(playlistId: String, kind: String, contentIds: List<String>, limit: Int, offset: Int): List<CachedCatalogItem>

    @Query("SELECT COUNT(*) FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND (:categoryId IS NULL OR categoryId = :categoryId)")
    suspend fun categoryItemCount(playlistId: String, kind: String, categoryId: String?): Int

    @Query("SELECT categoryId, COUNT(*) AS itemCount FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind GROUP BY categoryId")
    suspend fun categoryItemCounts(playlistId: String, kind: String): List<CategoryCount>

    // contentId is already unique for each playlist/kind via the table primary key, so COUNT(*)
    // is exact and avoids SQLite's temporary DISTINCT aggregation on large TV catalogs.
    @Query("SELECT categoryId, COUNT(*) AS itemCount FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND (:includeAdult = 1 OR isAdult = 0) GROUP BY categoryId")
    suspend fun accessibleCategoryItemCounts(playlistId: String, kind: String, includeAdult: Boolean): List<CategoryCount>

    data class CategoryCount(val categoryId: String, val itemCount: Int)

    @Query("SELECT DISTINCT i.* FROM catalog_items i INNER JOIN catalog_search_tokens s ON i.playlistId = s.playlistId AND i.kind = s.kind AND i.contentId = s.contentId WHERE i.playlistId = :playlistId AND s.token >= :tokenStart AND s.token < :tokenEnd ORDER BY i.title COLLATE NOCASE LIMIT :limit")
    suspend fun searchCandidates(playlistId: String, tokenStart: String, tokenEnd: String, limit: Int): List<CachedCatalogItem>

    @Query("SELECT DISTINCT i.* FROM catalog_items i INNER JOIN catalog_search_tokens s ON i.playlistId = s.playlistId AND i.kind = s.kind AND i.contentId = s.contentId WHERE i.playlistId = :playlistId AND i.kind = :kind AND s.token >= :tokenStart AND s.token < :tokenEnd ORDER BY i.title COLLATE NOCASE LIMIT :limit")
    suspend fun searchCandidatesForKind(playlistId: String, kind: String, tokenStart: String, tokenEnd: String, limit: Int): List<CachedCatalogItem>

    @Query("SELECT COUNT(*) FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun itemCount(playlistId: String, kind: String): Int

    @Query("SELECT COUNT(*) FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND (:includeAdult = 1 OR isAdult = 0)")
    suspend fun accessibleItemCount(playlistId: String, kind: String, includeAdult: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(values: List<CachedCategory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(values: List<CachedCatalogItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchTokens(values: List<CachedSearchToken>)

    @Query("DELETE FROM catalog_search_tokens WHERE playlistId = :playlistId AND kind = :kind AND contentId IN (:contentIds)")
    suspend fun deleteSearchTokens(playlistId: String, kind: String, contentIds: List<String>)

    @Query("DELETE FROM catalog_search_tokens WHERE playlistId = :playlistId AND kind = :kind AND contentId NOT IN (SELECT contentId FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind)")
    suspend fun deleteOrphanSearchTokens(playlistId: String, kind: String)

    @Query("DELETE FROM catalog_categories WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteCategories(playlistId: String, kind: String)

    @Query("DELETE FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteItems(playlistId: String, kind: String)

    @Query("DELETE FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND categoryId = :categoryId")
    suspend fun deleteCategoryItems(playlistId: String, kind: String, categoryId: String)

    @Query("DELETE FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND updatedAtEpochMs != :refreshMarker")
    suspend fun deleteStaleItems(playlistId: String, kind: String, refreshMarker: Long)

    @Query("DELETE FROM catalog_items WHERE playlistId = :playlistId AND kind = :kind AND categoryId = :categoryId AND updatedAtEpochMs != :refreshMarker")
    suspend fun deleteStaleCategoryItems(playlistId: String, kind: String, categoryId: String, refreshMarker: Long)

    @Query("DELETE FROM catalog_categories WHERE playlistId = :playlistId")
    suspend fun deletePlaylistCategories(playlistId: String)

    @Query("DELETE FROM catalog_items WHERE playlistId = :playlistId")
    suspend fun deletePlaylistItems(playlistId: String)

    @Query("DELETE FROM catalog_search_tokens WHERE playlistId = :playlistId")
    suspend fun deletePlaylistSearchTokens(playlistId: String)

    @Transaction
    suspend fun upsertItems(values: List<CachedCatalogItem>, tokens: List<CachedSearchToken>) {
        if (values.isEmpty()) return
        val first = values.first()
        deleteSearchTokens(first.playlistId, first.kind, values.map { it.contentId })
        insertItems(values)
        if (tokens.isNotEmpty()) insertSearchTokens(tokens)
    }

    @Transaction
    suspend fun replaceCategories(playlistId: String, kind: String, values: List<CachedCategory>) {
        if (values.isEmpty()) return
        deleteCategories(playlistId, kind)
        insertCategories(values)
    }

    /** Empty or malformed provider responses never erase a previously usable catalog. */
    @Transaction
    suspend fun replaceAllItems(playlistId: String, kind: String, values: List<CachedCatalogItem>) {
        if (values.isEmpty() && itemCount(playlistId, kind) > 0) return
        deleteItems(playlistId, kind)
        if (values.isNotEmpty()) insertItems(values)
    }

    @Transaction
    suspend fun replaceCategoryItems(playlistId: String, kind: String, categoryId: String, values: List<CachedCatalogItem>) {
        if (values.isEmpty()) return
        deleteCategoryItems(playlistId, kind, categoryId)
        insertItems(values)
    }

    @Transaction
    suspend fun deletePlaylist(playlistId: String) {
        deletePlaylistCategories(playlistId)
        deletePlaylistItems(playlistId)
        deletePlaylistSearchTokens(playlistId)
    }
}

@Database(
    entities = [CachedCategory::class, CachedCatalogItem::class, CachedSearchToken::class],
    version = 2,
    exportSchema = true,
)
abstract class CrownDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        @Volatile private var instance: CrownDatabase? = null

        fun get(context: Context): CrownDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CrownDatabase::class.java,
                "crown_media.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN providerOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE catalog_items ADD COLUMN isAdult INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_search_tokens (playlistId TEXT NOT NULL, kind TEXT NOT NULL, contentId TEXT NOT NULL, token TEXT NOT NULL, PRIMARY KEY(playlistId, kind, contentId, token))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_search_tokens_playlistId_token ON catalog_search_tokens (playlistId, token)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_items_playlistId_kind_providerOrder_contentId ON catalog_items (playlistId, kind, providerOrder, contentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_items_playlistId_kind_normalizedTitle_contentId ON catalog_items (playlistId, kind, normalizedTitle, contentId)")
            }
        }
    }
}
