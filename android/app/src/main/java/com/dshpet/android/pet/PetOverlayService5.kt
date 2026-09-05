package com.dshpet.android.pet

/** 多开实例 5 的服务载体（见 PetOverlayService.serviceClassFor） */
class PetOverlayService5 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 5
}
