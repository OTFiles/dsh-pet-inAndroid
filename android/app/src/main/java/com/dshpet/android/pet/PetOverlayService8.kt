package com.dshpet.android.pet

/** 多开实例 8 的服务载体（见 PetOverlayService.serviceClassFor） */
class PetOverlayService8 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 8
}
