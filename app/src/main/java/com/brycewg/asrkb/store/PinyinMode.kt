package com.brycewg.asrkb.store

enum class PinyinMode(val id: String) {
    Quanpin("quanpin"),
    Xiaohe("xiaohe");

    companion object {
        fun fromId(id: String?): PinyinMode = when (id?.lowercase()) {
            Xiaohe.id -> Xiaohe
            else -> Quanpin
        }
    }
}

