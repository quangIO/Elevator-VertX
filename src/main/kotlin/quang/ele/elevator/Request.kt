package quang.ele.elevator

data class OutsideRequest(val atFloor: Int, val direction: ElevatorState)
data class InsideRequest(val toFloor: Int, val elevatorId: Int)