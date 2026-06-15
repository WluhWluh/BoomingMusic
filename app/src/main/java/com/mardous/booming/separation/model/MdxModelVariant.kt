package com.mardous.booming.separation.model

enum class MdxModelVariant(
    val displayName: String,
    val fileName: String,
    val outputTag: String,
    val modelOutputStem: MdxStem,
) {
    MDXNET_9482(
        displayName = "UVR MDXNET 9482",
        fileName = "UVR_MDXNET_9482.onnx",
        outputTag = "mdxnet_9482",
        modelOutputStem = MdxStem.VOCALS,
    ),
    INST_MAIN(
        displayName = "UVR-MDX-NET Inst Main",
        fileName = "UVR-MDX-NET-Inst_Main.onnx",
        outputTag = "inst_main",
        modelOutputStem = MdxStem.INSTRUMENTAL,
    );

    override fun toString(): String = displayName
}

enum class MdxStem {
    VOCALS,
    INSTRUMENTAL,
}
