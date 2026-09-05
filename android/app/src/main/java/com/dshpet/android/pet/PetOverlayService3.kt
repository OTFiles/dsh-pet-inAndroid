package com.dshpet.android.pet

/** 多开实例 3 的服务载体（见 PetOverlayService.serviceClassFor） */
class PetOverlayService3 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 3
}
