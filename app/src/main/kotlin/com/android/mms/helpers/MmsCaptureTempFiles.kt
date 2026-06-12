package com.android.mms.helpers



import android.content.Context

import android.net.Uri

import com.android.mms.BuildConfig

import com.goodwy.commons.extensions.getMyFileUri

import java.io.File



/**

 * Alps MMS [com.android.mms.TempFileProvider] scrap-file helpers for camera capture.

 * Photo scrap: `.temp.jpg` via [scrapPhotoUri]. Video scrap: `.temp.3gp` via [scrapVideoUri].

 */

object MmsCaptureTempFiles {

    private const val AUTHORITY_SUFFIX = ".capture_temp"

    private const val SCRAP_PHOTO_SEGMENT = "scrapSpace"

    private const val SCRAP_VIDEO_SEGMENT = "scrapSpaceVideo"

    private const val SCRAP_PHOTO_NAME = ".temp.jpg"

    private const val SCRAP_VIDEO_NAME = ".temp.3gp"



    fun providerAuthority(): String = "${BuildConfig.APPLICATION_ID}$AUTHORITY_SUFFIX"



    fun scrapPhotoUri(): Uri =

        Uri.parse("content://${providerAuthority()}/$SCRAP_PHOTO_SEGMENT")



    fun scrapVideoUri(): Uri =

        Uri.parse("content://${providerAuthority()}/$SCRAP_VIDEO_SEGMENT")



    fun getCaptureDir(context: Context): File? {

        val dir = File(context.cacheDir, "attachments")

        return if (dir.exists() || dir.mkdirs()) dir else null

    }



    fun getScrapPhotoPath(context: Context): String? =

        getCaptureDir(context)?.let { File(it, SCRAP_PHOTO_NAME).absolutePath }



    fun getScrapVideoPath(context: Context): String? =

        getCaptureDir(context)?.let { File(it, SCRAP_VIDEO_NAME).absolutePath }



    fun getScrapPhotoFile(context: Context): File? {

        val path = getScrapPhotoPath(context) ?: return null

        return File(path).takeIf { it.exists() }

    }



    fun getScrapVideoFile(context: Context): File? {

        val path = getScrapVideoPath(context) ?: return null

        return File(path).takeIf { it.exists() }

    }



    fun clearScrapPhoto(context: Context) {

        getScrapPhotoFile(context)?.delete()

    }



    fun clearScrapVideo(context: Context) {

        getScrapVideoFile(context)?.delete()

    }



    fun scrapPhotoLength(context: Context): Long =

        getScrapPhotoFile(context)?.length() ?: 0L



    fun scrapVideoLength(context: Context): Long =

        getScrapVideoFile(context)?.length() ?: 0L



    /**

     * Alps [ComposeMessageActivity] reads [getScrapPhotoPath] after capture; copy to a unique file

     * so the next capture can reuse the scrap file.

     */

    fun finalizeScrapPhoto(context: Context): Uri? {

        val scrapFile = getScrapPhotoFile(context) ?: return null

        if (scrapFile.length() <= 0L) {

            return null

        }

        val destDir = getCaptureDir(context) ?: return null

        val destFile = File(destDir, "capture_${System.currentTimeMillis()}.jpg")

        return try {

            scrapFile.copyTo(destFile, overwrite = true)

            if (destFile.length() <= 0L) {

                destFile.delete()

                return null

            }

            scrapFile.delete()

            context.getMyFileUri(destFile)

        } catch (_: Exception) {

            destFile.delete()

            null

        }

    }



    /**

     * Copies the scrap video into a unique attachment file, matching Alps

     * [com.android.mms.TempFileProvider.renameScrapVideoFile].

     */

    fun finalizeScrapVideo(context: Context): Uri? {

        val scrapFile = getScrapVideoFile(context) ?: return null

        if (scrapFile.length() <= 0L) {

            return null

        }

        val destDir = getCaptureDir(context) ?: return null

        val destFile = File(destDir, "capture_${System.currentTimeMillis()}.3gp")

        return try {

            scrapFile.copyTo(destFile, overwrite = true)

            if (destFile.length() <= 0L) {

                destFile.delete()

                return null

            }

            scrapFile.delete()

            context.getMyFileUri(destFile)

        } catch (_: Exception) {

            destFile.delete()

            null

        }

    }

}


