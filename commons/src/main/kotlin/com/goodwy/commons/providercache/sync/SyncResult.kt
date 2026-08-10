package com.goodwy.commons.providercache.sync

sealed class SyncResult<out T> {
    data class Success<T>(val value: T) : SyncResult<T>()
    data object NoChange : SyncResult<Nothing>()
    data class Failure(
        val stage: SyncStage,
        val throwable: Throwable,
        val retryable: Boolean = true,
    ) : SyncResult<Nothing>()
}

enum class SyncStage {
    PERMISSION_CHECK,
    PROVIDER_READ,
    RAW_WRITE,
    INDEX_REBUILD,
    DISPLAY_REBUILD,
    VALIDATION,
    UNKNOWN,
}
