package com.goodwy.commons.providercache

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations for [ProviderCacheDatabase]. Kept separate for instrumented migration tests.
 */
internal object ProviderCacheMigrations {

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS contact_phone_index (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    contact_id INTEGER NOT NULL,
                    normalized_number TEXT NOT NULL,
                    digits TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_phone_index_contact_id ON contact_phone_index(contact_id)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_phone_index_normalized_number ON contact_phone_index(normalized_number)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_phone_index_digits ON contact_phone_index(digits)",
            )
            db.execSQL(
                "ALTER TABLE call_log_entries ADD COLUMN phone_account_id TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contact_summaries ADD COLUMN primary_raw_id INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE contact_summaries ADD COLUMN account_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE contact_summaries ADD COLUMN account_type TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE contact_summaries ADD COLUMN first_phone TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE contact_summaries ADD COLUMN first_email TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE contact_phone_index ADD COLUMN phone_digits TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS contact_search_index (
                    contact_id INTEGER NOT NULL PRIMARY KEY,
                    display_name_lower TEXT NOT NULL,
                    name_t9_key TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_search_index_name_t9_key ON contact_search_index(name_t9_key)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_search_index_display_name_lower ON contact_search_index(display_name_lower)",
            )
        }
    }

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE call_log_entries ADD COLUMN normalized_number TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Clear cached call log rows so they are re-synced with the correct
            // normalized_number (provider-supplied, with country code) for photo JOIN.
            db.execSQL("DELETE FROM call_log_entries")
        }
    }

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_summaries_primary_raw_id ON contact_summaries(primary_raw_id)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_summaries_account_name ON contact_summaries(account_name)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_summaries_account_type ON contact_summaries(account_type)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_search_index_contact_id ON contact_search_index(contact_id)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_phone_index_phone_digits ON contact_phone_index(phone_digits)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_call_log_entries_phone_account_id ON call_log_entries(phone_account_id)",
            )
        }
    }

    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS contact_display_cache (
                    raw_id INTEGER NOT NULL PRIMARY KEY,
                    contact_id INTEGER NOT NULL,
                    display_name TEXT NOT NULL,
                    thumbnail_uri TEXT NOT NULL,
                    photo_uri TEXT NOT NULL,
                    source TEXT NOT NULL,
                    account_type TEXT NOT NULL,
                    first_phone TEXT NOT NULL,
                    first_email TEXT NOT NULL,
                    starred INTEGER NOT NULL DEFAULT 0,
                    section_letter TEXT NOT NULL,
                    sort_key TEXT NOT NULL,
                    search_name TEXT NOT NULL,
                    t9_key TEXT NOT NULL,
                    phone_digits TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_display_cache_sort_key ON contact_display_cache(sort_key)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_display_cache_search_name ON contact_display_cache(search_name)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_display_cache_t9_key ON contact_display_cache(t9_key)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS recent_display_cache (
                    call_id INTEGER NOT NULL PRIMARY KEY,
                    phone_number TEXT NOT NULL,
                    cached_name TEXT NOT NULL,
                    photo_uri TEXT NOT NULL,
                    start_ts INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    type INTEGER NOT NULL,
                    sim_id INTEGER NOT NULL,
                    sim_type_id INTEGER NOT NULL,
                    sim_color INTEGER NOT NULL,
                    contact_id INTEGER,
                    call_count INTEGER NOT NULL,
                    grouped_call_ids TEXT NOT NULL,
                    normalized_number TEXT NOT NULL,
                    is_unknown_number INTEGER NOT NULL,
                    is_voice_mail INTEGER NOT NULL,
                    block_reason INTEGER,
                    features INTEGER,
                    group_by_contact INTEGER NOT NULL,
                    display_order INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_display_cache_start_ts ON recent_display_cache(start_ts)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_display_cache_phone_number ON recent_display_cache(phone_number)",
            )
        }
    }

    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN display_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN display_number TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN formatted_date_time TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN group_count_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN call_type_icon_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN sim_color_resolved INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN sim_label TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN sim_visible INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN avatar_initials TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN avatar_drawable_index INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN avatar_show_profile_icon INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN use_photo_avatar INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN name_is_missed_color INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN section_day_code TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE recent_display_cache ADD COLUMN section_header_text TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN first_phone_formatted TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN show_phone_number INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN avatar_initials TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN avatar_drawable_index INTEGER NOT NULL DEFAULT -1",
            )
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN use_photo_avatar INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_display_cache_display_order ON contact_display_cache(display_order)",
            )
            // UI-ready columns are empty until the next display-cache rebuild.
            db.execSQL("DELETE FROM contact_display_cache")
        }
    }

    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN photo_thumb_uri TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN avatar_color INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE contact_display_cache ADD COLUMN has_valid_photo_uri INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                """
                UPDATE contact_display_cache SET
                    photo_thumb_uri = CASE
                        WHEN thumbnail_uri != '' THEN thumbnail_uri
                        ELSE photo_uri
                    END,
                    has_valid_photo_uri = CASE
                        WHEN (thumbnail_uri != '' OR photo_uri != '') THEN 1
                        ELSE 0
                    END
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_contact_display_cache_phone_digits ON contact_display_cache(phone_digits)",
            )
        }
    }

    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE recent_display_cache ADD COLUMN group_key TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                """
                UPDATE recent_display_cache
                SET group_key = CASE
                    WHEN normalized_number != '' THEN normalized_number
                    ELSE phone_number
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                DELETE FROM recent_display_cache
                WHERE call_id NOT IN (
                    SELECT MAX(call_id)
                    FROM recent_display_cache
                    GROUP BY group_key, group_by_contact
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_recent_display_cache_group_key ON recent_display_cache(group_key, group_by_contact)",
            )
        }
    }

    val MIGRATION_12_13: Migration = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE recent_display_cache ADD COLUMN photo_thumb_uri TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE recent_display_cache ADD COLUMN avatar_color INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                """
                UPDATE recent_display_cache SET
                    photo_thumb_uri = CASE
                        WHEN photo_uri LIKE '%/photo' THEN ''
                        ELSE photo_uri
                    END
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_13_14: Migration = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cache_metadata (
                    domain TEXT NOT NULL PRIMARY KEY,
                    raw_version INTEGER NOT NULL DEFAULT 0,
                    display_version INTEGER NOT NULL DEFAULT 0,
                    last_successful_mutation_id INTEGER NOT NULL DEFAULT 0,
                    last_mutation_reason TEXT NOT NULL DEFAULT '',
                    row_count INTEGER NOT NULL DEFAULT 0,
                    content_checksum INTEGER NOT NULL DEFAULT 0,
                    dirty INTEGER NOT NULL DEFAULT 0,
                    repair_required INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            val now = System.currentTimeMillis()
            listOf(
                "CONTACTS_RAW",
                "CONTACTS_DISPLAY",
                "RECENTS_RAW",
                "RECENTS_DISPLAY",
            ).forEach { domain ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO cache_metadata
                    (domain, raw_version, display_version, dirty, repair_required, updated_at)
                    VALUES ('$domain', 0, 0, 1, 1, $now)
                    """.trimIndent(),
                )
            }
        }
    }

    val MIGRATION_14_15: Migration = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS recent_groups (
                    grouping_mode INTEGER NOT NULL,
                    group_key TEXT NOT NULL,
                    display_contact_id INTEGER,
                    latest_call_id INTEGER NOT NULL,
                    latest_timestamp INTEGER NOT NULL,
                    call_count INTEGER NOT NULL,
                    primary_number TEXT NOT NULL,
                    display_order INTEGER NOT NULL,
                    PRIMARY KEY(grouping_mode, group_key)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_groups_latest_timestamp ON recent_groups(latest_timestamp)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_groups_display_contact_id ON recent_groups(display_contact_id)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_groups_grouping_mode_display_order " +
                    "ON recent_groups(grouping_mode, display_order)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS recent_group_calls (
                    grouping_mode INTEGER NOT NULL,
                    group_key TEXT NOT NULL,
                    call_id INTEGER NOT NULL,
                    PRIMARY KEY(grouping_mode, group_key, call_id),
                    FOREIGN KEY(call_id) REFERENCES call_log_entries(call_id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_group_calls_call_id ON recent_group_calls(call_id)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_group_calls_grouping_mode_group_key " +
                    "ON recent_group_calls(grouping_mode, group_key)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS recent_group_numbers (
                    grouping_mode INTEGER NOT NULL,
                    group_key TEXT NOT NULL,
                    normalized_number TEXT NOT NULL,
                    PRIMARY KEY(grouping_mode, group_key, normalized_number)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_group_numbers_normalized_number " +
                    "ON recent_group_numbers(normalized_number)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_group_numbers_grouping_mode_group_key " +
                    "ON recent_group_numbers(grouping_mode, group_key)",
            )
            db.execSQL(
                """
                UPDATE cache_metadata
                SET repair_required = 1, dirty = 1, updated_at = ${System.currentTimeMillis()}
                WHERE domain = 'RECENTS_DISPLAY'
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_15_16: Migration = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE recent_groups ADD COLUMN membership_checksum INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE recent_display_cache ADD COLUMN avatar_seed_type TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE recent_display_cache ADD COLUMN avatar_seed_value TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE recent_display_cache ADD COLUMN avatar_version INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /**
     * recent_display_cache previously used call_id alone as PRIMARY KEY, so writing BY_CONTACT
     * rows REPLACE'd BY_NUMBER rows that shared the same latest call id. Composite PK lets both
     * modes coexist for dual-write / COMPARE_ONLY soak.
     */
    val MIGRATION_16_17: Migration = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS recent_display_cache_new (
                    call_id INTEGER NOT NULL,
                    phone_number TEXT NOT NULL,
                    cached_name TEXT NOT NULL,
                    photo_uri TEXT NOT NULL,
                    start_ts INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    type INTEGER NOT NULL,
                    sim_id INTEGER NOT NULL,
                    sim_type_id INTEGER NOT NULL,
                    sim_color INTEGER NOT NULL,
                    contact_id INTEGER,
                    call_count INTEGER NOT NULL,
                    grouped_call_ids TEXT NOT NULL,
                    normalized_number TEXT NOT NULL,
                    group_key TEXT NOT NULL DEFAULT '',
                    is_unknown_number INTEGER NOT NULL,
                    is_voice_mail INTEGER NOT NULL,
                    block_reason INTEGER,
                    features INTEGER,
                    group_by_contact INTEGER NOT NULL,
                    display_order INTEGER NOT NULL,
                    display_name TEXT NOT NULL DEFAULT '',
                    display_number TEXT NOT NULL DEFAULT '',
                    formatted_date_time TEXT NOT NULL DEFAULT '',
                    group_count_text TEXT NOT NULL DEFAULT '',
                    call_type_icon_key TEXT NOT NULL DEFAULT '',
                    sim_color_resolved INTEGER NOT NULL DEFAULT 0,
                    sim_label TEXT NOT NULL DEFAULT '',
                    sim_visible INTEGER NOT NULL DEFAULT 0,
                    avatar_initials TEXT NOT NULL DEFAULT '',
                    avatar_drawable_index INTEGER NOT NULL DEFAULT -1,
                    avatar_show_profile_icon INTEGER NOT NULL DEFAULT 0,
                    photo_thumb_uri TEXT NOT NULL DEFAULT '',
                    avatar_color INTEGER NOT NULL DEFAULT 0,
                    use_photo_avatar INTEGER NOT NULL DEFAULT 0,
                    name_is_missed_color INTEGER NOT NULL DEFAULT 0,
                    section_day_code TEXT NOT NULL DEFAULT '',
                    section_header_text TEXT NOT NULL DEFAULT '',
                    avatar_seed_type TEXT NOT NULL DEFAULT '',
                    avatar_seed_value TEXT NOT NULL DEFAULT '',
                    avatar_version INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(call_id, group_by_contact)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO recent_display_cache_new (
                    call_id, phone_number, cached_name, photo_uri, start_ts, duration, type,
                    sim_id, sim_type_id, sim_color, contact_id, call_count, grouped_call_ids,
                    normalized_number, group_key, is_unknown_number, is_voice_mail, block_reason,
                    features, group_by_contact, display_order, display_name, display_number,
                    formatted_date_time, group_count_text, call_type_icon_key, sim_color_resolved,
                    sim_label, sim_visible, avatar_initials, avatar_drawable_index,
                    avatar_show_profile_icon, photo_thumb_uri, avatar_color, use_photo_avatar,
                    name_is_missed_color, section_day_code, section_header_text,
                    avatar_seed_type, avatar_seed_value, avatar_version
                )
                SELECT
                    call_id, phone_number, cached_name, photo_uri, start_ts, duration, type,
                    sim_id, sim_type_id, sim_color, contact_id, call_count, grouped_call_ids,
                    normalized_number, group_key, is_unknown_number, is_voice_mail, block_reason,
                    features, group_by_contact, display_order, display_name, display_number,
                    formatted_date_time, group_count_text, call_type_icon_key, sim_color_resolved,
                    sim_label, sim_visible, avatar_initials, avatar_drawable_index,
                    avatar_show_profile_icon, photo_thumb_uri, avatar_color, use_photo_avatar,
                    name_is_missed_color, section_day_code, section_header_text,
                    avatar_seed_type, avatar_seed_value, avatar_version
                FROM recent_display_cache
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE recent_display_cache")
            db.execSQL("ALTER TABLE recent_display_cache_new RENAME TO recent_display_cache")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_display_cache_start_ts ON recent_display_cache(start_ts)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recent_display_cache_phone_number ON recent_display_cache(phone_number)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_recent_display_cache_group_key " +
                    "ON recent_display_cache(group_key, group_by_contact)",
            )
            db.execSQL(
                """
                UPDATE cache_metadata
                SET repair_required = 1, dirty = 1, updated_at = ${System.currentTimeMillis()}
                WHERE domain = 'RECENTS_DISPLAY'
                """.trimIndent(),
            )
        }
    }

    /** Composite index for full Contacts list ORDER BY display_order, raw_id. */
    val MIGRATION_17_18: Migration = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_contact_display_cache_display_order_raw_id` " +
                    "ON `contact_display_cache` (`display_order`, `raw_id`)",
            )
        }
    }

    /** Composite index for full Recents list WHERE group_by_contact + ORDER BY start_ts, call_id, group_key. */
    val MIGRATION_18_19: Migration = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_recent_display_cache_group_by_contact_start_ts_call_id_group_key` " +
                    "ON `recent_display_cache` (`group_by_contact`, `start_ts`, `call_id`, `group_key`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
    )
}
