package com.android.mms

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.goodwy.commons.RightApp
import com.goodwy.commons.helpers.PERMISSION_READ_CONTACTS
import com.goodwy.commons.extensions.hasPermission
import com.android.mms.helpers.MessagingCache
import com.android.mms.helpers.refreshConversations
import com.android.mms.models.Events
import org.greenrobot.eventbus.EventBus


private const val CONTACTS_CHANGED_CONVERSAIONS_DEBOUNCE_MS = 400L
class App : RightApp() {
    override val isAppLockFeatureAvailable = true

    private val contactsChangeHandler = Handler(Looper.getMainLooper())
    private val postContactsCacheEvictRefreshConversations = Runnable {
//        MessagingCache.namePhoto.evictAll()
//        MessagingCache.participantsCache.evictAll()
        refreshConversations()
        EventBus.getDefault().post(Events.ContactsProviderChanged())
    }

    override fun onCreate() {
        super.onCreate()
        registerGlobalContactsObservers()
    }

    private fun registerGlobalContactsObservers() {
        val uris = listOf(
            ContactsContract.AUTHORITY_URI,
            ContactsContract.Contacts.CONTENT_URI,
            ContactsContract.Data.CONTENT_URI,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            ContactsContract.DisplayPhoto.CONTENT_URI,
        )

        uris.forEach { uri: Uri ->
            try {
                contentResolver.registerContentObserver(uri, true, contactsObserver)
            } catch (_: Exception) {}
        }
    }

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
            contactsChangeHandler.removeCallbacks(postContactsCacheEvictRefreshConversations)
            contactsChangeHandler.postDelayed(
                postContactsCacheEvictRefreshConversations,
                CONTACTS_CHANGED_CONVERSAIONS_DEBOUNCE_MS
            )
        }
    }
}
