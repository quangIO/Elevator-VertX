This is a attempt to make a more modern and efficient design of elevator system compare to my old https://github.com/quangIO/Elevator. The older one uses multi-threaded, inefficient, "eager" approach. An event loop keep tracking the request queue to see if there exists some requests need to dispatch to elevators. (Lock based, CPU hungry, and waste of electricity)

### How does this work?

The idea is based on **Actor model**. There are **Elevator**Actors and a **ElevatorController**Actor. The messages passed from Elevators to the centralized **ElevatorController** (`MainVerticle`) using VertX's event bus (just for convenient, you can just use Rx's PublishBehavior or built-in Kotlin's Coroutine features like `channel` to implement it). 

##### Elevator
* `id` self-explanatory
* `timeToReach` time to move from floor `i` to `i+1` or other way around
* `pickUpTime` time to pickup
* `vertx` hold the vertx instance for event bus and some other utilities

Each elevator accepts following command from `MainVerticle`: `moveUp`, `moveDown`, and `pickUp`. There is a Timer that simulate the process. The `isMoving` variable is atomic, helping to check the current state of a elevator. It will be `true` if the elevator is picking up something or moving between 2 floors.

When a elevator reached a floor, it notifies its current state to the controller.

**Tl;dr:** Elevators basically do not anything. It just follow the order dispatched from `MainVerticle` and updates its state.

##### MainVerticle

This holds all the logic of the system, including `outsideRequests` (all requests from outside of the elevator), `insideRequests` (similarly), `elevatorStatuses` (current states of elevators), `elevators` (references to each elevator), and `requestQueue` (for fairness purpose - **TODO:** not optimized yet). 

All those data should be thread-safe. For the simplicity, I use built-in concurrent data structures with naive filter. However, in real production, an in-memory should be used (it is simpler to code actually).

###### It is **event-based**

* When it received an outside request: if it is moving up, it will try to reach the highest floor, and the same for moving down. The cost to select the elevator is calculated as `abs(toFloor - request.atFloor)` where `toFloor` is where the elevator is heading and `request.atFloor` is where the request comes from. In case, we cannot find an elevator to process a request, we add it to `requestQueue` 
* When it received an inside request: similar to outside request but with specific elevator ID, so it is a lot simpler.
* When elevators' states changed
    * If should pick up: `pickup`
    * If already doing something, `return`
    * If `toFloor > atFloor`: move up to reach `toFloor`
    * else if `toFloor < atFloor`: move down to reach `toFloor`
    * else (it is idling): find something to do (try to empty `requestQueue`, `in|outsideRequests`)

### Run
```
./gradlew shadowJar
java -jar build/libs/*.jar
```
or
```
Open import with Intellij Idea and run the main function
```

The server should start and we can use this [dashboard](https://elevator-dashboard.netlify.com/#/) to try some of the simulation. To change the number of elevator, change `val n = [number of elevator here]` in the `MainVerticle.kt` 
(101 floors)
### Bugs?
**Likely**. I would be very appreciate if you point out my errors.



