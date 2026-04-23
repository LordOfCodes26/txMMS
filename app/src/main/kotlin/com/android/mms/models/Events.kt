package com.android.mms.models

class Events {
    class RefreshMessages
    /**
     * @param localListRefreshOnly When true (e.g. after saving a compose draft), reload the main list from
     * local Room only instead of running a full Telephony merge — much faster with many threads.
     */
    class RefreshConversations(val localListRefreshOnly: Boolean = false)
}
