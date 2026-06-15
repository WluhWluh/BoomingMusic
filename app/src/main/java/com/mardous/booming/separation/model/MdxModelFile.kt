package com.mardous.booming.separation.model

import android.content.Context
import java.io.File
import java.io.IOException

object MdxModelFile {
    fun get(context: Context, variant: MdxModelVariant): File {
        val target = File(context.filesDir, variant.fileName)
        if (target.isFile && target.length() > 0L) return target

        copyBundledAsset(context, variant, target)
        require(target.isFile && target.length() > 0L) {
            "Model file missing: ${target.absolutePath}"
        }
        return target
    }

    private fun copyBundledAsset(context: Context, variant: MdxModelVariant, target: File) {
        val temp = File(context.filesDir, "${variant.fileName}.tmp")
        try {
            context.assets.open(variant.fileName).use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (error: IOException) {
            temp.delete()
            throw IllegalStateException(
                "Bundled model asset missing. Build the APK with models/uvr-mdx/${variant.fileName} available.",
                error,
            )
        }

        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace model file: ${target.absolutePath}")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}
