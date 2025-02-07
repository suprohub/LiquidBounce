/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.render.drawLineStrip
import net.ccbluex.liquidbounce.render.engine.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withColor
import net.ccbluex.liquidbounce.utils.combat.findEnemy
import net.ccbluex.liquidbounce.utils.entity.PlayerSimulationCache
import net.ccbluex.liquidbounce.utils.kotlin.mapArray
import net.ccbluex.liquidbounce.utils.math.toVec3
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket
import net.minecraft.util.math.Vec3d
import kotlin.math.min

/**
 * TickBase
 *
 * Calls tick function to speed up, when needed
 */
internal object ModuleTickBase : ClientModule("TickBase", Category.COMBAT) {

    private val mode by enumChoice("Mode", TickBaseMode.PAST)
        .apply { tagBy(this) }
    private val call by enumChoice("Call", TickBaseCall.GAME)

    /**
     * The range defines where we want to tickbase into. The first value is the minimum range, which we can
     * tick into, and the second value is the range where we cannot tickbase at all.
     */
    private val range by floatRange("Range", 2.5f..4f, 0f..8f)

    private val balanceRecoveryIncrement by float("BalanceRecoverIncrement", 1f, 0f..2f)
    private val balanceMaxValue by int("BalanceMaxValue", 20, 0..200)
    private val maxTicksAtATime by int("MaxTicksAtATime", 4, 1..20, "ticks")
    private val pauseOnFlag by boolean("PauseOfFlag", true)
    private val pause by int("Pause", 0, 0..20, "ticks")
    private val cooldown by int("Cooldown", 0, 0..100, "ticks")
    private val forceGround by boolean("ForceGround", false)
    private val lineColor by color("Line", Color4b.WHITE)
        .doNotIncludeAlways()

    private val requiresKillAura by boolean("RequiresKillAura", true)

    private var ticksToSkip = 0
    private var tickBalance = 0f
    private var reachedTheLimit = false

    private val tickBuffer = mutableListOf<TickData>()

    @Suppress("unused")
    private val playerTickHandler = handler<PlayerTickEvent> { event ->
        // We do not want this module to conflict with blink
        if (player.vehicle != null || ModuleBlink.running) {
            return@handler
        }

        if (ticksToSkip-- > 0) {
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        // We do not want this module to conflict with blink
        if (player.vehicle != null || ModuleBlink.running || tickBuffer.isEmpty()) {
            return@tickHandler
        }

        val nearbyEnemy = world.findEnemy(0f..range.endInclusive) ?: return@tickHandler
        val currentDistance = player.pos.squaredDistanceTo(nearbyEnemy.pos)

        // Find the best tick that is able to hit the target and is not too far away from the player, as well as
        // able to crit the target
        val possibleTicks = tickBuffer
            .mapIndexed { index, tick -> index to tick }
            .filter { (_, tick) ->
                tick.position.squaredDistanceTo(nearbyEnemy.pos) < currentDistance
                    && tick.position.squaredDistanceTo(nearbyEnemy.pos) in range
            }
            .filter { (_, tick) ->
                !forceGround || tick.onGround
            }

        val criticalTick = possibleTicks
            .filter { (_, tick) ->
                tick.fallDistance > 0.0f
            }
            .minByOrNull { (index, _) ->
                index
            }

        val (bestTick, _) = criticalTick ?: possibleTicks.minByOrNull { (index, _) ->
            index
        } ?: return@tickHandler

        if (bestTick == 0) {
            return@tickHandler
        }

        // We do not want to tickbase if killaura is not ready to attack
        val breakRequirement = {
            requiresKillAura && !(ModuleKillAura.running &&
                ModuleKillAura.clickScheduler.isClickOnNextTick(bestTick))
        }

        if (breakRequirement()) {
            return@tickHandler
        }

        when (mode) {
            TickBaseMode.PAST -> {
                ticksToSkip = bestTick + pause
                waitTicks(ticksToSkip)
                repeat(bestTick) {
                    call.tick()
                    tickBalance -= 1
                }

                ModuleDebug.debugParameter(this, "Recommended Skip", bestTick)
            }

            TickBaseMode.FUTURE -> {
                var totalSkipped = 0

                for (i in 0 until bestTick) {
                    call.tick()
                    tickBalance -= 1
                    totalSkipped++

                    if (breakRequirement()) {
                        break
                    }
                }

                ModuleDebug.debugParameter(this, "Total Skipped", totalSkipped)
                ModuleDebug.debugParameter(this, "Recommended Skip", bestTick)

                ticksToSkip = totalSkipped + pause
                waitTicks(ticksToSkip)
            }
        }

        waitTicks(cooldown)
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        // We do not want this module to conflict with blink
        if (player.vehicle != null || ModuleBlink.running) {
            return@handler
        }

        tickBuffer.clear()

        val simulatedPlayer = PlayerSimulationCache.getSimulationForLocalPlayer()

        if (tickBalance <= 0) {
            reachedTheLimit = true
        }
        if (tickBalance > balanceMaxValue / 2) {
            reachedTheLimit = false
        }
        if (tickBalance <= balanceMaxValue) {
            tickBalance += balanceRecoveryIncrement
        }

        if (reachedTheLimit) {
            return@handler
        }

        val tickRange = 0 until min(tickBalance.toInt(), maxTicksAtATime)
        val snapshots = simulatedPlayer.getSnapshotsBetween(tickRange)

        snapshots.forEach { snapshot ->
            tickBuffer += TickData(
                snapshot.pos,
                snapshot.fallDistance,
                snapshot.velocity,
                snapshot.onGround
            )
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (lineColor.a > 0) {
            renderEnvironmentForWorld(event.matrixStack) {
                withColor(lineColor) {
                    drawLineStrip(positions = tickBuffer.mapArray { tick ->
                        relativeToCamera(tick.position).toVec3()
                    })
                }
            }
        }
    }

    val packetHandler = handler<PacketEvent> {
        // Stops when you got flagged
        if (it.packet is PlayerPositionLookS2CPacket && pauseOnFlag) {
            tickBalance = 0f
        }
    }

    data class TickData(
        val position: Vec3d,
        val fallDistance: Float,
        val velocity: Vec3d,
        val onGround: Boolean
    )

    private enum class TickBaseMode(override val choiceName: String) : NamedChoice {
        PAST("Past"),
        FUTURE("Future")
    }

    @Suppress("unused")
    private enum class TickBaseCall(
        override val choiceName: String,
        val tick: () -> Unit
    ) : NamedChoice {

        /**
         * Runs a full game tick.
         *
         * TODO: Cancel full game ticks after this,
         *   not just the player ticks.
         */
        GAME("Game", { mc.tick() }),

        /**
         * This will NOT update the game tick,
         * but only the player tick - that means
         * e.g. Rotation Manager will not update either.
         *
         * This was the previous default behavior of the TickBase,
         * so it is kept for compatibility reasons.
         */
        PLAYER("Player", { player.tick() })
    }

}
