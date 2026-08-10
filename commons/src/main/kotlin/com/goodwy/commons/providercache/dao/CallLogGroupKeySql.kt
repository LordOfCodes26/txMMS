package com.goodwy.commons.providercache.dao

/**
 * SQL expression for digit-only call-log group keys — must match [com.goodwy.commons.providercache.display.RecentGroupKey].
 */
object CallLogGroupKeySql {
    const val EXPR =
        "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(NULLIF(normalized_number, ''), phone_number), '+', ''), ' ', ''), '-', ''), '(', ''), ')', ''), '.', '')"

    const val EXPR_C =
        "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(NULLIF(c.normalized_number, ''), c.phone_number), '+', ''), ' ', ''), '-', ''), '(', ''), ')', ''), '.', '')"

    const val EXPR_X =
        "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(NULLIF(x.normalized_number, ''), x.phone_number), '+', ''), ' ', ''), '-', ''), '(', ''), ')', ''), '.', '')"

    /** Prefer highest call_id when multiple rows share MAX(start_ts). */
    const val HEAD_CALL_ID_AT_MAX_TS =
        "(SELECT MAX(x.call_id) FROM call_log_entries x WHERE $EXPR_X = g.group_key AND x.start_ts = g.max_ts)"
}
