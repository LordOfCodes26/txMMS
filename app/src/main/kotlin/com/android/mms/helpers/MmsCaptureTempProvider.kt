package com.android.mms.helpers



import android.content.ContentProvider

import android.content.ContentValues

import android.content.UriMatcher

import android.database.Cursor

import android.database.MatrixCursor

import android.net.Uri

import android.os.ParcelFileDescriptor

import android.provider.MediaStore

import android.util.Log

import java.io.File

import java.io.FileNotFoundException



/**

 * Content provider that exposes writable scrap file URIs to the camera/camcorder, same pattern as

 * Alps MMS [com.android.mms.TempFileProvider].

 */

class MmsCaptureTempProvider : ContentProvider() {

    private lateinit var uriMatcher: UriMatcher



    override fun onCreate(): Boolean {

        val authority = MmsCaptureTempFiles.providerAuthority()

        uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {

            addURI(authority, SCRAP_PHOTO_SEGMENT, MATCH_SCRAP_PHOTO)

            addURI(authority, SCRAP_VIDEO_SEGMENT, MATCH_SCRAP_VIDEO)

        }

        return true

    }



    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {

        val path = when (uriMatcher.match(uri)) {

            MATCH_SCRAP_PHOTO -> MmsCaptureTempFiles.getScrapPhotoPath(requireNotNull(context))

            MATCH_SCRAP_VIDEO -> MmsCaptureTempFiles.getScrapVideoPath(requireNotNull(context))

            else -> throw FileNotFoundException("Unknown URI: $uri")

        } ?: throw FileNotFoundException("Unable to resolve scrap path for $uri")



        val modeFlags = if (mode == "r") {

            ParcelFileDescriptor.MODE_READ_ONLY

        } else {

            ParcelFileDescriptor.MODE_READ_WRITE or

                ParcelFileDescriptor.MODE_CREATE or

                ParcelFileDescriptor.MODE_TRUNCATE

        }

        return try {

            ParcelFileDescriptor.open(File(path), modeFlags)

        } catch (e: Exception) {

            Log.e(TAG, "openFile failed for $path", e)

            throw FileNotFoundException("Unable to open scrap file: $path")

        }

    }



    override fun getType(uri: Uri): String = when (uriMatcher.match(uri)) {

        MATCH_SCRAP_PHOTO -> "image/jpeg"

        MATCH_SCRAP_VIDEO -> "video/3gpp"

        else -> "*/*"

    }



    /**

     * AOSP Camera queries [_data] for the scrap path before opening the recorder.

     */

    override fun query(

        uri: Uri,

        projection: Array<out String>?,

        selection: String?,

        selectionArgs: Array<out String>?,

        sortOrder: String?,

    ): Cursor? {

        if (projection?.size == 1 && projection[0] == MediaStore.MediaColumns.DATA) {

            val path = when (uriMatcher.match(uri)) {

                MATCH_SCRAP_PHOTO -> MmsCaptureTempFiles.getScrapPhotoPath(requireNotNull(context))

                MATCH_SCRAP_VIDEO -> MmsCaptureTempFiles.getScrapVideoPath(requireNotNull(context))

                else -> return null

            } ?: return null

            return MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA), 1).apply {

                addRow(arrayOf(path))

            }

        }

        return null

    }



    override fun insert(uri: Uri, values: ContentValues?): Uri? = null



    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0



    override fun update(

        uri: Uri,

        values: ContentValues?,

        selection: String?,

        selectionArgs: Array<out String>?,

    ): Int = 0



    companion object {

        private const val TAG = "MmsCaptureTempProvider"

        private const val SCRAP_PHOTO_SEGMENT = "scrapSpace"

        private const val SCRAP_VIDEO_SEGMENT = "scrapSpaceVideo"

        private const val MATCH_SCRAP_PHOTO = 1

        private const val MATCH_SCRAP_VIDEO = 2

    }

}


