package com.mardous.booming.separation.model

enum class MdxModelVariant(
    val displayName: String,
    val fileName: String,
    val outputTag: String,
    val modelOutputStem: MdxStem,
    val expectedSha256: String,
) {
    MDXNET_9482(
        displayName = "UVR MDXNET 9482",
        fileName = "UVR_MDXNET_9482.onnx",
        outputTag = "mdxnet_9482",
        modelOutputStem = MdxStem.VOCALS,
        expectedSha256 = "9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d",
    ),
    INST_MAIN(
        displayName = "UVR-MDX-NET Inst Main",
        fileName = "UVR-MDX-NET-Inst_Main.onnx",
        outputTag = "inst_main",
        modelOutputStem = MdxStem.INSTRUMENTAL,
        expectedSha256 = "",
    );

    override fun toString(): String = displayName
}

enum class MdxStem {
    VOCALS,
    INSTRUMENTAL,
}
