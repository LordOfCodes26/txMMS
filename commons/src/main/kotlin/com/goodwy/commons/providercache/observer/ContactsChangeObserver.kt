package com.goodwy.commons.providercache.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.providercache.sync.ContactsSyncManager

class ContactsChangeObserver(
    context: Context,
    private val syncManager: ContactsSyncManager,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onChangeDebounced: (() -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var registered = false

    private val debouncedRunnable = Runnable {
        // When an app-layer owner is installed (ContactChangeCoordinator), it owns the sync
        // generation via awaitable join. Avoid schedule + await double entry on the same change.
        if (onChangeDebounced != null) {
            onChangeDebounced.invoke()
        } else {
            syncManager.scheduleIncrementalSync()
        }
    }

    fun register() {
        if (registered || !appContext.hasPermission(PERMISSION_READ_CONTACTS)) return
        if (observer == null) {
            observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (selfChange) return
                    handler.removeCallbacks(debouncedRunnable)
                    handler.postDelayed(debouncedRunnable, debounceMs)
                }
            }
        }
        val cr = appContext.contentResolver
        val obs = observer!!
        cr.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, obs)
        cr.registerContentObserver(ContactsContract.Data.CONTENT_URI, true, obs)
        cr.registerContentObserver(ContactsContract.RawContacts.CONTENT_URI, true, obs)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        handler.removeCallbacks(debouncedRunnable)
        observer?.let { appContext.contentResolver.unregisterContentObserver(it) }
        registered = false
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 2500L
    }
}
