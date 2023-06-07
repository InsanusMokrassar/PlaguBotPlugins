package dev.inmo.plagubot.plugins.captcha.provider

import kotlinx.serialization.Serializable

@Serializable
sealed interface Complexity : Comparable<Complexity> {
    val weight: Int
    @Serializable
    object Easy : Complexity { override val weight: Int = Int.MAX_VALUE / 2 - Int.MAX_VALUE / 4 }
    @Serializable
    object Medium : Complexity { override val weight: Int = Int.MAX_VALUE / 2 }
    @Serializable
    object Hard : Complexity { override val weight: Int = Int.MAX_VALUE / 2 + Int.MAX_VALUE / 4 }
    @Serializable
    class Custom(override val weight: Int) : Complexity

    override fun compareTo(other: Complexity): Int = weight.compareTo(other.weight)

    companion object {
        operator fun invoke(weight: Int) = when(weight) {
            Easy.weight -> Easy
            Medium.weight -> Medium
            Hard.weight -> Hard
            else -> Custom(weight)
        }
    }
}
