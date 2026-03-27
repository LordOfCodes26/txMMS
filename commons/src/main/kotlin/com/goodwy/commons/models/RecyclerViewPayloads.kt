package com.goodwy.commons.models

data class RecyclerSelectionPayload(val selected: Boolean)

/** Partial bind: update selection UI only (checkbox / row state). */
object RecyclerSelectionRefreshPayload
