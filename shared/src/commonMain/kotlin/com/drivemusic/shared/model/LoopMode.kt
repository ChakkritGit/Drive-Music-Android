package com.drivemusic.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class LoopMode {
    OFF,
    ALL,
    ONE;

    val next: LoopMode
        get() = when (this) {
            OFF -> ALL
            ALL -> ONE
            ONE -> OFF
        }
}
