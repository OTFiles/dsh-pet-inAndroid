package com.dshpet.android.pet

/** 多开实例 2 的服务载体（见 PetOverlayService.serviceClassFor） */
class PetOverlayService2 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 2
}
