package com.goodwy.commons.helpers

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.contacts.Address
import com.goodwy.commons.models.contacts.ContactRelation
import com.goodwy.commons.models.contacts.Email
import com.goodwy.commons.models.contacts.Event
import com.goodwy.commons.models.contacts.IM
import com.goodwy.commons.models.contacts.PhoneNumberConverter
import java.util.Locale

class Converters {
    private val gson = Gson()
    private val longType = object : TypeToken<List<Long>>() {}.type
    private val stringType = object : TypeToken<List<String>>() {}.type
    private val numberType = object : TypeToken<List<PhoneNumber>>() {}.type
    private val numberConverterType = object : TypeToken<List<PhoneNumberConverter>>() {}.type
    private val emailType = object : TypeToken<List<Email>>() {}.type
    private val addressType = object : TypeToken<List<Address>>() {}.type
    private val eventType = object : TypeToken<List<Event>>() {}.type
    private val imType = object : TypeToken<List<IM>>() {}.type
    private val relationType = object : TypeToken<List<ContactRelation>>() {}.type

    @TypeConverter
    fun jsonToStringList(value: String): ArrayList<String> = gson.fromJson(value, stringType)

    @TypeConverter
    fun stringListToJson(list: ArrayList<String>): String = gson.toJson(list)

    @TypeConverter
    fun jsonToLongList(value: String): ArrayList<Long> = gson.fromJson(value, longType)

    @TypeConverter
    fun longListToJson(list: ArrayList<Long>): String = gson.toJson(list)

    // some hacky converting is needed since PhoneNumber model has been added to proguard rules, but obfuscated json was stored in database
    // convert [{"a":"678910","b":2,"c":"","d":"678910","e":false}] to PhoneNumber(value=678910, type=2, label=, normalizedNumber=678910, isPrimary=false)
    @TypeConverter
    fun jsonToPhoneNumberList(value: String): ArrayList<PhoneNumber> {
        val numbers = gson.fromJson<ArrayList<PhoneNumber>>(value, numberType)
        return if (numbers.any { it.value == null }) {
            val phoneNumbers = ArrayList<PhoneNumber>()
            val numberConverters =
                gson.fromJson<ArrayList<PhoneNumberConverter>>(value, numberConverterType)
            numberConverters.forEach { converter ->
                val phoneNumber =
                    PhoneNumber(converter.a, converter.b, converter.c, converter.d, converter.e)
                phoneNumbers.add(phoneNumber)
            }
            phoneNumbers
        } else {
            numbers
        }
    }

    @TypeConverter
    fun phoneNumberListToJson(list: ArrayList<PhoneNumber>): String = gson.toJson(list)

    @TypeConverter
    fun jsonToEmailList(value: String): ArrayList<Email> = gson.fromJson(value, emailType)

    @TypeConverter
    fun emailListToJson(list: ArrayList<Email>): String = gson.toJson(list)

    @TypeConverter
    fun jsonToAddressList(value: String): ArrayList<Address> {
        val addresses = gson.fromJson<ArrayList<Address>>(value, addressType)
        // This is a workaround for https://github.com/FossifyOrg/Contacts/issues/281
        return addresses.map {
            @Suppress("USELESS_ELVIS")
            it.copy(
                value = it.value,
                type = it.type,
                label = it.label,
                country = it.country ?: "",
                region = it.region ?: "",
                city = it.city ?: "",
                postcode = it.postcode ?: "",
                pobox = it.pobox ?: "",
                street = it.street ?: "",
                neighborhood = it.neighborhood ?: ""
            )
        }.toMutableList() as ArrayList<Address>
    }

    @TypeConverter
    fun addressListToJson(list: ArrayList<Address>): String = gson.toJson(list)

    @TypeConverter
    fun jsonToEventList(value: String): ArrayList<Event> = gson.fromJson(value, eventType)

    @TypeConverter
    fun eventListToJson(list: ArrayList<Event>): String = gson.toJson(list)

    @TypeConverter
    fun jsonToIMsList(value: String): ArrayList<IM> = gson.fromJson(value, imType)

    @TypeConverter
    fun iMsListToJson(list: ArrayList<IM>): String = gson.toJson(list)

    @TypeConverter
    fun jsonToRelationList(value: String): ArrayList<ContactRelation> {
        return (gson.fromJson<ArrayList<ContactRelation>>(value, relationType) ?: ArrayList())
    }

    @TypeConverter
    fun relationListToJson(list: ArrayList<ContactRelation>): String = gson.toJson(list)

    fun normalizeRelativeTextForKorean(text: String, languageType: String): String {
        if (languageType != Locale.KOREAN.language) return text

        val koreanCompact = text
            .replace("분 전", "분전")
            .replace("시간 전", "시간전")
            .replace("일 전", "일전")
            .replace("주 전", "주전")
            .replace("개월 전", "개월전")
            .replace("년 전", "년전")

        return koreanCompact
            .replace(Regex("""(\d+)\s*min\.?\s*ago""", RegexOption.IGNORE_CASE), "$1분전")
            .replace(Regex("""(\d+)\s*mins\.?\s*ago""", RegexOption.IGNORE_CASE), "$1분전")
            .replace(Regex("""(\d+)\s*hr\.?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
            .replace(Regex("""(\d+)\s*hrs\.?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
            .replace(Regex("""(\d+)\s*hour[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
            .replace(Regex("""(\d+)\s*day[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1일전")
            .replace(Regex("""(\d+)\s*week[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1주전")
            .replace(Regex("""(\d+)\s*month[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1개월전")
            .replace(Regex("""(\d+)\s*year[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1년전")
    }

    companion object
}
