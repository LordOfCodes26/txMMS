package com.goodwy.commons.providercache

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCacheDatabaseMigrationTest {

  @get:Rule
  val helper: MigrationTestHelper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    ProviderCacheDatabase::class.java,
    emptyList(),
    FrameworkSQLiteOpenHelperFactory(),
  )

  @Test
  fun migrate1To2() {
    helper.createDatabase(TEST_DB, 1).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      2,
      true,
      ProviderCacheMigrations.MIGRATION_1_2,
    )
    assertV2Schema(db)
    assertFalseV3OnlyColumns(db)
    db.close()
  }

  @Test
  fun migrate2To3() {
    helper.createDatabase(TEST_DB, 2).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      3,
      true,
      ProviderCacheMigrations.MIGRATION_2_3,
    )
    assertV3Schema(db)
    db.close()
  }

  @Test
  fun migrate1To2To3() {
    helper.createDatabase(TEST_DB, 1).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      3,
      true,
      ProviderCacheMigrations.MIGRATION_1_2,
      ProviderCacheMigrations.MIGRATION_2_3,
    )
    assertV2Schema(db)
    assertV3Schema(db)
    db.close()
  }

  @Test
  fun freshOpenAtV3() {
    val db = helper.createDatabase(TEST_DB, 3)
    assertV3Schema(db)
    db.close()
  }

  @Test
  fun migrate5To6() {
    helper.createDatabase(TEST_DB, 5).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      6,
      true,
      ProviderCacheMigrations.MIGRATION_5_6,
    )
    assertV6Indexes(db)
    db.close()
  }

  @Test
  fun migrate6To7() {
    helper.createDatabase(TEST_DB, 6).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      7,
      true,
      ProviderCacheMigrations.MIGRATION_6_7,
    )
    assertTableExists(db, "contact_display_cache")
    assertTableExists(db, "recent_display_cache")
    db.close()
  }

  @Test
  fun migrate10To11() {
    helper.createDatabase(TEST_DB, 10).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      11,
      true,
      ProviderCacheMigrations.MIGRATION_10_11,
    )
    assertTrue(
      "index_contact_display_cache_phone_digits",
      indexExists(db, "index_contact_display_cache_phone_digits"),
    )
    db.close()
  }

  private fun assertV6Indexes(db: SupportSQLiteDatabase) {
    assertTrue(
      "index_contact_summaries_primary_raw_id",
      indexExists(db, "index_contact_summaries_primary_raw_id"),
    )
    assertTrue(
      "index_contact_summaries_account_name",
      indexExists(db, "index_contact_summaries_account_name"),
    )
    assertTrue(
      "index_contact_summaries_account_type",
      indexExists(db, "index_contact_summaries_account_type"),
    )
    assertTrue(
      "index_contact_search_index_contact_id",
      indexExists(db, "index_contact_search_index_contact_id"),
    )
    assertTrue(
      "index_contact_phone_index_phone_digits",
      indexExists(db, "index_contact_phone_index_phone_digits"),
    )
    assertTrue(
      "index_call_log_entries_phone_account_id",
      indexExists(db, "index_call_log_entries_phone_account_id"),
    )
  }

  private fun indexExists(db: SupportSQLiteDatabase, indexName: String): Boolean {
    db.query(
      "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
      arrayOf(indexName),
    ).use { return it.moveToFirst() }
  }

  @Test
  fun migrate12To13() {
    helper.createDatabase(TEST_DB, 12).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      13,
      true,
      ProviderCacheMigrations.MIGRATION_12_13,
    )
    assertColumnExists(db, "recent_display_cache", "photo_thumb_uri")
    assertColumnExists(db, "recent_display_cache", "avatar_color")
    db.close()
  }

  @Test
  fun migrate13To14() {
    helper.createDatabase(TEST_DB, 13).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      14,
      true,
      ProviderCacheMigrations.MIGRATION_13_14,
    )
    assertV14Schema(db)
    db.close()
  }

  @Test
  fun migrate12To14() {
    helper.createDatabase(TEST_DB, 12).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      14,
      true,
      ProviderCacheMigrations.MIGRATION_12_13,
      ProviderCacheMigrations.MIGRATION_13_14,
    )
    assertV14Schema(db)
    db.close()
  }

  @Test
  fun migrate3To4() {
    helper.createDatabase(TEST_DB, 3).close()
    val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, ProviderCacheMigrations.MIGRATION_3_4)
    assertColumnExists(db, "call_log_entries", "normalized_number")
    db.close()
  }

  @Test
  fun migrate4To5_clearsCallLog() {
    helper.createDatabase(TEST_DB, 4).apply {
      execSQL("INSERT INTO call_log_entries(call_id,phone_number,cached_name,cached_photo_uri,start_ts,duration,type,sim_id,normalized_number) VALUES (1,'555','A','',1000,0,1,0,'555')")
      close()
    }
    val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, ProviderCacheMigrations.MIGRATION_4_5)
    db.query("SELECT COUNT(*) FROM call_log_entries").use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(0, c.getInt(0))
    }
    db.close()
  }

  @Test
  fun migrate7To8() {
    helper.createDatabase(TEST_DB, 7).close()
    val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, ProviderCacheMigrations.MIGRATION_7_8)
    assertColumnExists(db, "recent_display_cache", "display_name")
    assertColumnExists(db, "recent_display_cache", "section_header_text")
    db.close()
  }

  @Test
  fun migrate11To12_uniqueGroupIndex() {
    helper.createDatabase(TEST_DB, 11).close()
    val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, ProviderCacheMigrations.MIGRATION_11_12)
    assertTrue(indexExists(db, "index_recent_display_cache_group_key"))
    db.close()
  }

  @Test
  fun migrate14To15() {
    helper.createDatabase(TEST_DB, 14).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      15,
      true,
      ProviderCacheMigrations.MIGRATION_14_15,
    )
    assertV15Schema(db)
    db.close()
  }

  @Test
  fun migrate13To15() {
    helper.createDatabase(TEST_DB, 13).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      15,
      true,
      ProviderCacheMigrations.MIGRATION_13_14,
      ProviderCacheMigrations.MIGRATION_14_15,
    )
    assertV15Schema(db)
    db.close()
  }

  @Test
  fun migrate14To15_preservesRecentsAndSeedsRepairRequired() {
    helper.createDatabase(TEST_DB, 14).apply {
      execSQL(
        "INSERT INTO recent_display_cache(call_id,phone_number,cached_name,photo_uri,start_ts,duration,type,sim_id,sim_type_id,sim_color,call_count,grouped_call_ids,normalized_number,is_unknown_number,is_voice_mail,group_by_contact,display_order,group_key) " +
          "VALUES (1,'555','N','',1000,0,1,0,1,0,1,'1','555',0,0,0,0,'555')",
      )
      close()
    }
    val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, ProviderCacheMigrations.MIGRATION_14_15)
    db.query("SELECT COUNT(*) FROM recent_display_cache").use { c ->
      c.moveToFirst()
      assertEquals(1, c.getInt(0))
    }
    db.query("SELECT repair_required FROM cache_metadata WHERE domain='RECENTS_DISPLAY'").use { c ->
      c.moveToFirst()
      assertEquals(1, c.getInt(0))
    }
    assertV15Schema(db)
    db.close()
  }

  @Test
  fun migrate1To15_fullChain() {
    helper.createDatabase(TEST_DB, 1).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      15,
      true,
      *ProviderCacheMigrations.ALL,
    )
    assertV15Schema(db)
    assertTableExists(db, "contact_display_cache")
    assertTableExists(db, "recent_display_cache")
    db.close()
  }

  @Test
  fun migrate1To14_fullChain() {
    helper.createDatabase(TEST_DB, 1).close()
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      14,
      true,
      *ProviderCacheMigrations.ALL.dropLast(1).toTypedArray(),
    )
    assertV14Schema(db)
    assertTableExists(db, "contact_display_cache")
    assertTableExists(db, "recent_display_cache")
    db.close()
  }

  private fun assertV15Schema(db: SupportSQLiteDatabase) {
    assertTableExists(db, "recent_groups")
    assertTableExists(db, "recent_group_calls")
    assertTableExists(db, "recent_group_numbers")
    assertColumnExists(db, "recent_groups", "group_key")
    assertColumnExists(db, "recent_groups", "latest_call_id")
    assertColumnExists(db, "recent_group_calls", "call_id")
    assertV14Schema(db)
  }

  @Test
  fun migrate13To14_metadataSeededDirtyAndRepairRequired() {
    helper.createDatabase(TEST_DB, 13).close()
    val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, ProviderCacheMigrations.MIGRATION_13_14)
    db.query("SELECT domain, dirty, repair_required, display_version FROM cache_metadata ORDER BY domain").use { c ->
      while (c.moveToNext()) {
        assertEquals(1, c.getInt(c.getColumnIndex("dirty")))
        assertEquals(1, c.getInt(c.getColumnIndex("repair_required")))
        assertTrue(c.getLong(c.getColumnIndex("display_version")) >= 0L)
      }
    }
    db.close()
  }

  @Test
  fun migrate12To14_preservesContactAndCallRows() {
    helper.createDatabase(TEST_DB, 12).apply {
      execSQL(
        "INSERT INTO contact_summaries(contact_id,lookup_key,display_name,photo_thumbnail_uri,has_phone_number,last_updated_timestamp,primary_raw_id) " +
          "VALUES (1,'k','Alice','',1,1000,10)",
      )
      execSQL(
        "INSERT INTO call_log_entries(call_id,phone_number,cached_name,cached_photo_uri,start_ts,duration,type,sim_id,normalized_number) " +
          "VALUES (99,'555','Alice','',2000,0,1,0,'555')",
      )
      close()
    }
    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      14,
      true,
      ProviderCacheMigrations.MIGRATION_12_13,
      ProviderCacheMigrations.MIGRATION_13_14,
    )
    db.query("SELECT COUNT(*) FROM contact_summaries").use { c ->
      c.moveToFirst()
      assertEquals(1, c.getInt(0))
    }
    db.query("SELECT COUNT(*) FROM call_log_entries").use { c ->
      c.moveToFirst()
      assertEquals(1, c.getInt(0))
    }
    assertV14Schema(db)
    db.close()
  }

  private fun assertV14Schema(db: SupportSQLiteDatabase) {
    assertTableExists(db, "cache_metadata")
    assertColumnExists(db, "cache_metadata", "domain")
    assertColumnExists(db, "cache_metadata", "raw_version")
    assertColumnExists(db, "cache_metadata", "display_version")
    assertColumnExists(db, "cache_metadata", "dirty")
    assertColumnExists(db, "cache_metadata", "repair_required")
    db.query("SELECT COUNT(*) FROM cache_metadata").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(4, cursor.getInt(0))
    }
  }

  private fun assertV2Schema(db: SupportSQLiteDatabase) {
    assertTableExists(db, "contact_phone_index")
    assertColumnExists(db, "contact_phone_index", "contact_id")
    assertColumnExists(db, "contact_phone_index", "normalized_number")
    assertColumnExists(db, "contact_phone_index", "digits")
    assertColumnExists(db, "call_log_entries", "phone_account_id")
  }

  private fun assertV3Schema(db: SupportSQLiteDatabase) {
    assertTableExists(db, "contact_search_index")
    assertColumnExists(db, "contact_search_index", "contact_id")
    assertColumnExists(db, "contact_search_index", "display_name_lower")
    assertColumnExists(db, "contact_search_index", "name_t9_key")
    assertColumnExists(db, "contact_summaries", "primary_raw_id")
    assertColumnExists(db, "contact_summaries", "account_name")
    assertColumnExists(db, "contact_summaries", "account_type")
    assertColumnExists(db, "contact_summaries", "first_phone")
    assertColumnExists(db, "contact_summaries", "first_email")
    assertColumnExists(db, "contact_phone_index", "phone_digits")
    assertColumnExists(db, "call_log_entries", "phone_account_id")
  }

  private fun assertFalseV3OnlyColumns(db: SupportSQLiteDatabase) {
    assertFalse(
      "primary_raw_id should not exist before v3",
      columnExists(db, "contact_summaries", "primary_raw_id"),
    )
    assertFalse(
      "contact_search_index should not exist before v3",
      tableExists(db, "contact_search_index"),
    )
    assertFalse(
      "phone_digits should not exist before v3",
      columnExists(db, "contact_phone_index", "phone_digits"),
    )
  }

  private fun assertTableExists(db: SupportSQLiteDatabase, table: String) {
    assertTrue("Missing table $table", tableExists(db, table))
  }

  private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
    db.query(
      "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
      arrayOf(table),
    ).use { return it.moveToFirst() }
  }

  private fun assertColumnExists(db: SupportSQLiteDatabase, table: String, column: String) {
    assertTrue(
      "Missing column $table.$column (found ${tableColumns(db, table)})",
      columnExists(db, table, column),
    )
  }

  private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
    column in tableColumns(db, table)

  private fun tableColumns(db: SupportSQLiteDatabase, table: String): List<String> {
    val columns = ArrayList<String>()
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
      val nameIndex = cursor.getColumnIndex("name")
      while (cursor.moveToNext()) {
        columns.add(cursor.getString(nameIndex))
      }
    }
    return columns
  }

  companion object {
    private const val TEST_DB = "provider-cache-migration-test"
  }
}
