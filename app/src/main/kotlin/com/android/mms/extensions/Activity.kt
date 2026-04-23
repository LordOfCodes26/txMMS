package com.android.mms.extensions

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.NewAppDialog
import com.goodwy.commons.extensions.darkenColor
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.extensions.getMimeType
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isPackageInstalled
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.launchCallIntent
import com.goodwy.commons.extensions.launchViewContactIntent
import com.goodwy.commons.extensions.lightenColor
import com.goodwy.commons.extensions.performHapticFeedback
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.helpers.CONTACT_ID
import com.goodwy.commons.helpers.KEY_PHONE
import com.goodwy.commons.helpers.IS_PRIVATE
import com.goodwy.commons.helpers.LICENSE_EVENT_BUS
import com.goodwy.commons.helpers.LICENSE_INDICATOR_FAST_SCROLL
import com.goodwy.commons.helpers.LICENSE_SMS_MMS
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.FAQItem
import com.goodwy.commons.models.SimpleContact
import com.android.mms.BuildConfig
import com.android.mms.R
import com.android.mms.activities.ConversationDetailsActivity
import com.android.mms.activities.SimpleActivity
import com.android.mms.activities.VCardViewerActivity
import com.android.mms.dialogs.SelectSIMDialog
import com.android.mms.helpers.EXTRA_VCARD_URI
import com.android.mms.helpers.THREAD_ID
import com.android.mms.helpers.parseVCardFromUri
import com.goodwy.commons.extensions.telecomManager
import com.goodwy.commons.helpers.PERMISSION_READ_PHONE_STATE
import ezvcard.property.Telephone
import com.google.android.material.snackbar.Snackbar
import eightbitlab.com.blurview.BlurTarget
import java.util.Locale

@SuppressLint("MissingPermission")
fun BaseSimpleActivity.dialNumber(phoneNumber: String, callback: (() -> Unit)? = null) {
    hideKeyboard()
    launchCallIntent(phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
    callback?.invoke()
//    val subs = subscriptionManagerCompat().activeSubscriptionInfoList
//    if (!subs.isNullOrEmpty() && subs.size >1) {
//        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)?: throw IllegalStateException("mainBlurTarget not found")
//        SelectSIMDialog(this as SimpleActivity, blurTarget )  { simCard, _ ->
//            val handle = getPhoneAccountHandleForSubscription(simCard.subscriptionId)
//            launchCallIntent(phoneNumber, handle, BuildConfig.RIGHT_APP_KEY)
//            callback?.invoke()
//        }
//    } else {
//        val subId = subs?.singleOrNull()?.subscriptionId
//        val handle = subId?.let { getPhoneAccountHandleForSubscription(it) }
//        launchCallIntent(phoneNumber, handle, BuildConfig.RIGHT_APP_KEY)
//        callback?.invoke()
//    }
}

fun Activity.launchViewIntent(uri: Uri, mimetype: String, filename: String) {
    Intent().apply {
        action = Intent.ACTION_VIEW
        setDataAndType(uri, mimetype.lowercase(Locale.getDefault()))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            hideKeyboard()
            startActivity(this)
        } catch (_: ActivityNotFoundException) {
            val newMimetype = filename.getMimeType()
            if (newMimetype.isNotEmpty() && mimetype != newMimetype) {
                launchViewIntent(uri, newMimetype, filename)
            } else {
                toast(com.goodwy.commons.R.string.no_app_found)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun Activity.startContactDetailsIntentRecommendation(contact: SimpleContact) {
    val simpleContacts = "com.goodwy.contacts"
    val simpleContactsDebug = "com.goodwy.contacts.debug"
    val newSimpleContacts = "dev.goodwy.contacts"
    val newSimpleContactsDebug = "dev.goodwy.contacts.debug"
    if (
        (0..config.appRecommendationDialogCount).random() == 2 &&
        (!isPackageInstalled(simpleContacts) && !isPackageInstalled(simpleContactsDebug) &&
            !isPackageInstalled(newSimpleContacts) && !isPackageInstalled(newSimpleContactsDebug))
    ) {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        NewAppDialog(
            activity = this,
            packageName = if (packageName.startsWith("dev.")) newSimpleContacts else simpleContacts,
            title = getString(com.goodwy.strings.R.string.recommendation_dialog_contacts_g),
            text = getString(com.goodwy.commons.R.string.right_contacts),
            drawable = AppCompatResources.getDrawable(this, com.goodwy.commons.R.drawable.ic_contacts),
            blurTarget = blurTarget
        ) {
            startContactDetailsIntent(contact)
        }
    } else {
        startContactDetailsIntent(contact)
    }
}

fun Activity.startContactDetailsIntent(contact: SimpleContact) {
    val simpleContacts = "com.goodwy.contacts"
    val simpleContactsDebug = "com.goodwy.contacts.debug"
    val newSimpleContacts = "dev.goodwy.contacts"
    val newSimpleContactsDebug = "dev.goodwy.contacts.debug"
    if (
        contact.rawId > 1000000 &&
        contact.contactId > 1000000 &&
        contact.rawId == contact.contactId &&
        (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug) ||
            isPackageInstalled(newSimpleContacts) || isPackageInstalled(newSimpleContactsDebug))
    ) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            setPackage(
                if (isPackageInstalled(simpleContacts)) {
                    simpleContacts
                } else {
                    simpleContactsDebug
                }
            )

            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )

            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey = SimpleContactsHelper(this)
                .getContactLookupKey(
                    contactId = (contact).rawId.toString()
                )

            val publicUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey
            )
            runOnUiThread {
                launchViewContactIntent(publicUri)
            }
        }
    }
}

/**
 * Opens an address-book contact detail for a vCard attachment when any listed phone matches a device
 * contact (same flow as txDial [MainActivityRecents] info / [ConversationsAdapter.openConversationDetails]).
 * Falls back to [VCardViewerActivity] when no phones match.
 */
fun Activity.openContactDetailsFromVCardUri(uri: Uri) {
    parseVCardFromUri(this, uri) { vCards ->
        val rawNumbers = vCards.flatMap { vCard ->
            vCard.properties.filterIsInstance<Telephone>().mapNotNull { tel ->
                tel.text?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.distinct()

        fun openVCardViewer() {
            runOnUiThread {
                Intent(this, VCardViewerActivity::class.java).apply {
                    putExtra(EXTRA_VCARD_URI, uri)
                    startActivity(this)
                }
            }
        }

        if (rawNumbers.isEmpty()) {
            openVCardViewer()
            return@parseVCardFromUri
        }

        fun tryAddress(index: Int) {
            if (index >= rawNumbers.size) {
                openVCardViewer()
                return
            }
            val raw = rawNumbers[index]
            val address = raw.normalizePhoneNumber().ifEmpty { raw }
            getContactFromAddress(address) { contact ->
                if (contact != null) {
                    runOnUiThread {
                        startContactDetailsIntent(contact)
                    }
                } else {
                    tryAddress(index + 1)
                }
            }
        }
        tryAddress(0)
    }
}

fun Activity.launchConversationDetails(threadId: Long) {
    Intent(this, ConversationDetailsActivity::class.java).apply {
        putExtra(THREAD_ID, threadId)
        startActivity(this)
    }
}

/** Same as txDial [com.android.dialer.extensions.startAddContactIntent]: prefill new/edit contact with a phone number. */
fun Activity.startAddContactIntent(phoneNumber: String) {
    Intent().apply {
        action = Intent.ACTION_INSERT_OR_EDIT
        type = "vnd.android.cursor.item/contact"
        putExtra(KEY_PHONE, phoneNumber)
        launchActivityIntent(this)
    }
}

//Goodwy
fun SimpleActivity.launchPurchase() {
    val productIdX1 = BuildConfig.PRODUCT_ID_X1
    val productIdX2 = BuildConfig.PRODUCT_ID_X2
    val productIdX3 = BuildConfig.PRODUCT_ID_X3
    val subscriptionIdX1 = BuildConfig.SUBSCRIPTION_ID_X1
    val subscriptionIdX2 = BuildConfig.SUBSCRIPTION_ID_X2
    val subscriptionIdX3 = BuildConfig.SUBSCRIPTION_ID_X3
    val subscriptionYearIdX1 = BuildConfig.SUBSCRIPTION_YEAR_ID_X1
    val subscriptionYearIdX2 = BuildConfig.SUBSCRIPTION_YEAR_ID_X2
    val subscriptionYearIdX3 = BuildConfig.SUBSCRIPTION_YEAR_ID_X3

    startPurchaseActivity(
        R.string.app_name_g,
        productIdList = arrayListOf(productIdX1, productIdX2, productIdX3),
        productIdListRu = arrayListOf(productIdX1, productIdX2, productIdX3),
        subscriptionIdList = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionIdListRu = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionYearIdList = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
        subscriptionYearIdListRu = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
    )
}

fun SimpleActivity.showSnackbar(view: View) {
    view.performHapticFeedback()

    val snackbar = Snackbar.make(view, com.goodwy.strings.R.string.support_project_to_unlock, Snackbar.LENGTH_SHORT)
        .setAction(com.goodwy.commons.R.string.support) {
            launchPurchase()
        }

    val bgDrawable = ResourcesCompat.getDrawable(view.resources, com.goodwy.commons.R.drawable.button_background_16dp, null)
    snackbar.view.background = bgDrawable
    val properBackgroundColor = getProperBackgroundColor()
    val backgroundColor = if (properBackgroundColor == Color.BLACK) getSurfaceColor().lightenColor(6) else getSurfaceColor().darkenColor(6)
    snackbar.setBackgroundTint(backgroundColor)
    snackbar.setTextColor(getProperTextColor())
    snackbar.setActionTextColor(getProperPrimaryColor())
    snackbar.show()
}

fun SimpleActivity.launchAbout() {
    val licenses = LICENSE_EVENT_BUS or LICENSE_SMS_MMS or LICENSE_INDICATOR_FAST_SCROLL

    val faqItems = arrayListOf(
        FAQItem(
            title = R.string.faq_2_title,
            text = R.string.faq_2_text
        ),
        FAQItem(
            title = R.string.faq_3_title,
            text = R.string.faq_3_text
        ),
        FAQItem(
            title = R.string.faq_4_title,
            text = R.string.faq_4_text
        ),
        FAQItem(
            title = com.goodwy.commons.R.string.faq_9_title_commons,
            text = com.goodwy.commons.R.string.faq_9_text_commons
        )
    )

    if (!resources.getBoolean(com.goodwy.commons.R.bool.hide_google_relations)) {
        faqItems.add(FAQItem(com.goodwy.commons.R.string.faq_2_title_commons, com.goodwy.strings.R.string.faq_2_text_commons_g))
        faqItems.add(FAQItem(com.goodwy.commons.R.string.faq_6_title_commons, com.goodwy.strings.R.string.faq_6_text_commons_g))
    }

    val productIdX1 = BuildConfig.PRODUCT_ID_X1
    val productIdX2 = BuildConfig.PRODUCT_ID_X2
    val productIdX3 = BuildConfig.PRODUCT_ID_X3
    val subscriptionIdX1 = BuildConfig.SUBSCRIPTION_ID_X1
    val subscriptionIdX2 = BuildConfig.SUBSCRIPTION_ID_X2
    val subscriptionIdX3 = BuildConfig.SUBSCRIPTION_ID_X3
    val subscriptionYearIdX1 = BuildConfig.SUBSCRIPTION_YEAR_ID_X1
    val subscriptionYearIdX2 = BuildConfig.SUBSCRIPTION_YEAR_ID_X2
    val subscriptionYearIdX3 = BuildConfig.SUBSCRIPTION_YEAR_ID_X3

    val flavorName = BuildConfig.FLAVOR
    val storeDisplayName = when (flavorName) {
        "gplay" -> "Google Play"
        "foss" -> "FOSS"
        "rustore" -> "RuStore"
        else -> ""
    }
    val versionName = BuildConfig.VERSION_NAME
    val fullVersionText = "$versionName ($storeDisplayName)"

    startAboutActivity(
        appNameId = R.string.app_name_g,
        licenseMask = licenses,
        versionName = fullVersionText,
        flavorName = BuildConfig.FLAVOR,
        faqItems = faqItems,
        showFAQBeforeMail = true,
        productIdList = arrayListOf(productIdX1, productIdX2, productIdX3),
        productIdListRu = arrayListOf(productIdX1, productIdX2, productIdX3),
        subscriptionIdList = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionIdListRu = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionYearIdList = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
        subscriptionYearIdListRu = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
    )
}


