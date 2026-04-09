package com.android.mms.emoji

import android.content.Context
import com.chutils.CHGlobal
import com.chutils.emo.manager.ResManager
import java.io.File
import java.io.FileOutputStream

/**
 * Configures CH350-style emoticon loading (libresutils JNI plus dot-obb resource packs).
 *
 * The upstream CH350 Git tree usually does not ship libresutils.so or obb files.
 * Lookup order:
 * - App filesDir and externalFilesDir under [OBB_FOLDER]
 * - Optional: copy from assets/ch350_emoji/ (any file ending in .obb)
 * - OEM path /product/txDCS/ when present (CH350 Global.APP_DIR_PATH)
 */
object Ch350EmojiBootstrap {
    const val OBB_FOLDER = "ch350_emoji_obb"
    private const val ASSET_PACK_DIR = "ch350_emoji"

    fun ensureInitialized(context: Context): Boolean {
        ResManager.clearEmojiResourceRoots()

        val filesObb = File(context.filesDir, OBB_FOLDER)
        filesObb.mkdirs()
        ResManager.addEmojiResourceRoot(filesObb)

        context.getExternalFilesDir(null)?.let { ext ->
            val exObb = File(ext, OBB_FOLDER)
            exObb.mkdirs()
            ResManager.addEmojiResourceRoot(exObb)
        }

        val productTxDcs = File("/product/txDCS/")
        if (productTxDcs.isDirectory) {
            ResManager.addEmojiResourceRoot(productTxDcs)
        }

        copyBundledObbFromAssets(context, filesObb)

        return ResManager.initResourceDb() == CHGlobal.AUTH_CODE_SUCCESS
    }

    /**
     * Copies obb packs from assets into targetDir when missing.
     */
    private fun copyBundledObbFromAssets(context: Context, targetDir: File) {
        targetDir.mkdirs()
        val names = try {
            context.assets.list(ASSET_PACK_DIR)
        } catch (_: Exception) {
            null
        } ?: return

        for (name in names) {
            if (!name.endsWith(".obb", ignoreCase = true)) continue
            val out = File(targetDir, name)
            if (out.exists() && out.length() > 0L) continue
            try {
                context.assets.open("$ASSET_PACK_DIR/$name").use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                if (out.exists() && out.length() == 0L) {
                    out.delete()
                }
            }
        }
    }
}
