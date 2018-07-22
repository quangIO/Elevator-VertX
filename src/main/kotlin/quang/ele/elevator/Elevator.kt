package quang.ele.elevator

import com.google.common.flogger.FluentLogger
import io.vertx.core.Vertx
import io.vertx.core.json.Json
import java.io.Serializable
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule

enum class ElevatorState {
    UP, DOWN
}

data class ElevatorStatus(val elevatorId: Int, var atFloor: Int = 0, var toFloor: Int = 0) : Serializable

class Elevator(private val id: Int, private val vertx: Vertx,
               private val timeToReach: Long = 1000,
               private val pickUpTime: Long = 2000) {
    var isMoving = AtomicBoolean(false)

    companion object {
        private val logger = FluentLogger.forEnclosingClass()
    }

    private var atFloor = 0

    fun pickUp() {
        logger.atInfo().log("$id picking up")
        isMoving.set(true)
        vertx.setTimer(pickUpTime) { _ ->
            isMoving.set(false)
            vertx.eventBus().publish("reach", Json.encodePrettily(ElevatorStatus(id, atFloor)))
            logger.atInfo().log("$id picked up at $atFloor")
        }
    }

    fun moveUp() {
        logger.atInfo().log("$id moving up")
        isMoving.set(true)
        vertx.setTimer(timeToReach) { _ ->
            atFloor++
            isMoving.set(false)
            vertx.eventBus().publish("reach", Json.encodePrettily(ElevatorStatus(id, atFloor)))
            logger.atInfo().log("$id moved up to $atFloor")
        }
    }

    fun moveDown() {
        logger.atInfo().log("$id moving down")
        isMoving.set(true)
        vertx.setTimer(timeToReach) {
            atFloor--
            isMoving.set(false)
            vertx.eventBus().publish("reach", Json.encodePrettily(ElevatorStatus(id, atFloor)))
            logger.atInfo().log("$id moved down to $atFloor")
        }
    }
}