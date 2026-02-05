package com.android.mms.models

import android.os.Parcel
import android.os.Parcelable

data class Contact(
    var name: String = "",
    var contactId: String = "",
    var icon: Int = -1,
    var phoneNumber: String = "",
    var address: String = "",
    var organizationName: String = ""
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
        dest.writeString(contactId)
        dest.writeInt(icon)
        dest.writeString(phoneNumber)
        dest.writeString(address)
        dest.writeString(organizationName)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Contact> {
        override fun createFromParcel(parcel: Parcel): Contact = Contact(parcel)
        override fun newArray(size: Int): Array<Contact?> = arrayOfNulls(size)
    }
}
