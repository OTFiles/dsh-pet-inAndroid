package com.dshpet.android.pet

/** 多开实例 1 的服务载体（见 PetOverlayService.serviceClassFor） */
class PetOverlayService1 : PetOverlayService() {
    override val defaultInstanceId: Int get() = 1
}
