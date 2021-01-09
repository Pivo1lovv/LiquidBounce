/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2016 - 2021 CCBlueX
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
package net.ccbluex.liquidbounce.event

import net.ccbluex.liquidbounce.LiquidBounce

interface Listenable {

    /**
     * Allows to disable event handling when condition is false.
     */
    fun handleEvents() = true

}

inline fun <reified T : Event> Listenable.handler(ignoreCondition: Boolean = false, noinline handler: Handler<T>) {
    LiquidBounce.eventManager.registerEventHook(T::class.java, EventHook(this, handler, ignoreCondition))
}

typealias Handler<T> = (T) -> Unit

class EventHook<T : Event>(val handlerClass: Listenable, val handler: Handler<T>, val ignoresCondition: Boolean)
