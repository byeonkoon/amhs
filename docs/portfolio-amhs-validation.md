# AMHS Scenario Validation Design

## Validation goal

Verify transport behavior across the application boundary before connecting a real factory system. A scenario starts with an accepted transport request and ends with either verified completion or a recoverable state that clearly states what a person must establish.

## Scenario stages

1. Accept a request and check required fields, endpoints, vehicle class, and duplicate submission.
2. Select an eligible vehicle using its current state, assignment policy, carrier state, and service status.
3. Find a route on the correct vehicle network and reserve only the next authorized portion.
4. Execute simulated movement while updating position, vehicle state, and traffic ownership.
5. Model equipment handover in simulation and require evidence before changing carrier location.
6. Complete the request, or retain an explicit recovery state when the physical carrier location is uncertain.

## Fault scenarios

| Scenario | Expected invariant |
| --- | --- |
| Duplicate request | One accepted work item for the same idempotency identity |
| Vehicle becomes unavailable | No new assignment to that vehicle |
| Traffic resource is held | The route stops before the first unavailable resource |
| Cancellation during movement | Do not release or reuse a resource while a command may still be active |
| Equipment prohibited | Preserve order and carrier state until permission returns |
| Handover result uncertain | Require an explicit carrier-location decision before closing recovery |
| Adapter is disconnected | Do not infer device completion from command submission |

## Boundary

The in-process simulator validates AMHS state transitions, route/traffic decisions, and virtual handover sequencing. Real device protocol behavior, inbound device acknowledgement, production safety interlocks, plant performance, and field reliability require separate adapter and factory validation.
