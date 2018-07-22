package quang.ele.elevator

import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.google.common.flogger.FluentLogger
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.absoluteValue

// adding capacity is easy, just add more constraints in `filter` function filter {isFull && ...}

class MainVerticle : CoroutineVerticle() {
    // For simplicity, built-in thread-safe data structures are enough. Should use an in-memory DB in complex cases
    private val outsideRequests: MutableSet<OutsideRequest> = ConcurrentHashMap.newKeySet()
    private val insideRequests: MutableSet<InsideRequest> = ConcurrentHashMap.newKeySet()
    private val elevatorStatuses = CopyOnWriteArrayList<ElevatorStatus>()
    private val elevators = CopyOnWriteArrayList<Elevator>()
    // yep, if you go inside a elevator which is going UP, and then you want to go down, you have no priority
    private val requestQueue = ConcurrentLinkedQueue<Int>()

    companion object {
        private val logger = FluentLogger.forEnclosingClass()
    }

    private fun moveElevator(id: Int, pickup: Boolean = false) {
        if (elevators[id].isMoving.get()) {
            logger.atInfo().log("Is moving")
            logger.atInfo().log(elevatorStatuses.toString())
            return
        }
        if (pickup) {
            elevators[id].pickUp()
            return
        }
        when {
            elevatorStatuses[id].toFloor > elevatorStatuses[id].atFloor -> elevators[id].moveUp()
            elevatorStatuses[id].toFloor < elevatorStatuses[id].atFloor -> elevators[id].moveDown()
            else -> {
                requestQueue.poll()?.let {
                    moveElevatorToFloor(id, it)
                } ?: insideRequests.filter { it.elevatorId == id }.minBy {
                    (elevatorStatuses[id].atFloor - it.toFloor).absoluteValue
                }?.also {
                    moveElevatorToFloor(id, it.toFloor)
                }?: if (outsideRequests.iterator().hasNext()){
                    moveElevatorToFloor(id, outsideRequests.iterator().next().atFloor)
                }
            }
        }
    }

    private fun moveElevatorToFloor(elevatorId: Int, floor: Int, direction: ElevatorState? = null) {
        with(elevatorStatuses[elevatorId]) {
            toFloor = when {
                toFloor > atFloor -> {
                    // untested: attempt to make it work like SCAN algorithm
                    if (direction == ElevatorState.DOWN && floor < toFloor) {
                        requestQueue.add(floor)
                    }
                    Math.max(toFloor, floor)
                }
                toFloor < atFloor -> {
                    // untested: attempt to make it work like SCAN algorithm
                    if (direction == ElevatorState.UP && floor > toFloor) {
                        requestQueue.add(floor)
                    }
                    Math.min(toFloor, floor)
                }
                else -> floor
            }
            if (atFloor == toFloor) {
                elevators[elevatorId].pickUp()
            }
        }
        moveElevator(elevatorId)
    }

    override suspend fun start() {
        val n = 16
        val router = Router.router(vertx)
        for (i in 0 until n) {
            elevatorStatuses.add(ElevatorStatus(i))
            elevators.add(Elevator(i, vertx, 1000, 5000))
        }
        router.apply {
            route().handler(BodyHandler.create())
            route().handler(CorsHandler.create("*").allowedHeader("Content-Type"))
            post("/outside").handler { context ->
                logger.atInfo().log("request from outside:" + context.bodyAsString)

                val r = context.bodyAsJson.mapTo(OutsideRequest::class.java)
                outsideRequests.add(r)

                val e = elevatorStatuses
                        .filter { (it.toFloor - it.atFloor) * (r.atFloor - it.atFloor) >= 0
                                // && if (r.direction == ElevatorState.UP) (it.toFloor >= it.atFloor) else it.toFloor <= it.atFloor // TODO: this makes things simpler but can be more efficient
                        }
                        .minBy { (it.toFloor - r.atFloor).absoluteValue }

                if (e != null)
                    moveElevatorToFloor(e.elevatorId, r.atFloor, r.direction)
                else
                    requestQueue.add(r.atFloor)
                context.response().endWithJson(outsideRequests)
            }
            post("/inside").handler { context ->
                logger.atInfo().log("request from inside:" + context.bodyAsString)

                val r = context.bodyAsJson.mapTo(InsideRequest::class.java)
                insideRequests.add(r)

                moveElevatorToFloor(r.elevatorId, r.toFloor)

                context.response().endWithJson(insideRequests)
            }
            get("/status").handler { context ->
                context.response().endWithJson(elevatorStatuses)
            }
            get("/queue").handler { context ->
                context.response().endWithJson(requestQueue)
            }
            get("/all").handler { context ->
                context.response().endWithJson(
                        mapOf("status" to elevatorStatuses,
                                "queue" to requestQueue,
                                "inside" to insideRequests,
                                "outside" to outsideRequests))
            }
        }
        vertx.eventBus().consumer<String>("reach") {
            val res = JsonObject(it.body()).mapTo(ElevatorStatus::class.java)
            elevatorStatuses[res.elevatorId].run {
                atFloor = res.atFloor
                val o = OutsideRequest(atFloor, if (toFloor > atFloor) ElevatorState.UP else ElevatorState.DOWN)
                val i = InsideRequest(atFloor, elevatorId)

                logger.atInfo().log(toString() + "\n" + outsideRequests.toString() + "\n" + o)

                val needPickup = outsideRequests.remove(o) or insideRequests.remove(i) or
                        if (toFloor == atFloor) outsideRequests.remove(OutsideRequest(atFloor, ElevatorState.UP))
                        else false
                // "or" because we don't want short-circuit evaluation
                moveElevator(elevatorId, needPickup)
            }
        }
        Json.mapper.registerModule(KotlinModule())

        val port = System.getenv("PORT")?.toInt() ?: 8080
        vertx.createHttpServer().apply {
            requestHandler(router::accept)
        }.listen(port) { res ->
            if (res.failed()) {
                logger.atSevere().withCause(res.cause()).log("Cannot start server at port $port")
            } else {
                logger.atInfo().log("Server started with port $port")
            }
        }
    }
}

private fun HttpServerResponse.endWithJson(obj: Any) =
        putHeader("content-type", "application/json; charset=utf-8").end(Json.encodePrettily(obj))

fun main(args: Array<String>) {
    Vertx.vertx().deployVerticle(MainVerticle())
}