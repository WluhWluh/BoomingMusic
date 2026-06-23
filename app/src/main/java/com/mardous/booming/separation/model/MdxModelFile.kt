package com.mardous.booming.separation.model

import android.content.Context
import java.io.File

object MdxModelFile {
    fun get(context: Context, variant: MdxModelVariant): File {
        return SourceSeparationModelRepository(context).requireModelFile(variant)
    }
}
