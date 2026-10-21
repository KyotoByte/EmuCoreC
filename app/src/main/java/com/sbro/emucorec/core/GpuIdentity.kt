package com.sbro.emucorec.core

enum class GpuVendor {
    ADRENO,
    MALI,
    POWERVR,
    XCLIPSE,
    UNKNOWN
}

enum class GpuTier {
    GREEN,
    YELLOW,
    RED,
    UNKNOWN
}

enum class GpuInfoSource {
    GL_RENDERER,
    SOC_CATALOG,
    UNKNOWN
}

data class GpuModelInfo(
    val vendor: GpuVendor,
    val displayName: String,
    val adrenoFamily: AdrenoFamily? = null
)

data class DeviceGpuSnapshot(
    val model: GpuModelInfo?,
    val tier: GpuTier,
    val renderer: String?,
    val source: GpuInfoSource
)
