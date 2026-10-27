# AMHS Scenario Validation Design

## Validation goal

Verify transport behaviour across the application boundary before any real factory system is
connected. A scenario starts with an accepted transport request and ends either with verified
completion or with a recoverable state that says plainly what a person must establish.

Two claims are kept apart throughout, here and in [the verification log](verification.md): what has
been **run and observed**, and what has only been **designed**. A test passing is never reported as
a plant working.

## How it is verified

| | |
|---|---|
| Automated | **531 tests**, failures 0, errors 0, **skipped 0**, on an isolated PostgreSQL cluster the project starts for the run (`scripts/test-postgres.ps1`) |
| Under load | A repeatable run on a floor of its own: `scripts/load-run.ps1` makes its own database, imports a layout through the same API the screens use, drives the site, and asks the invariants afterwards (`scripts/load-invariants.sql`) |
| By hand | Screens are opened in the running application for anything a person has to read or act on |

The skip count is reported because it matters: the SQL-backed tests skip silently without
`AMHS_SQL_TEST=true`, so "all tests pass" with 200 of them skipped would be a false report.

## Scenario stages

1. Accept a request and check required fields, endpoints, vehicle class, and duplicate submission.
2. Select an eligible vehicle from its current state, assignment policy, carrier state, and service status.
3. Find a route on the correct vehicle network and reserve only the next authorised portion.
4. Execute movement while updating position, vehicle state, and traffic ownership.
5. Model equipment handover and require evidence before changing carrier location.
6. Complete the request, or keep an explicit recovery state while the physical carrier location is uncertain.

## What a fault must produce

| Scenario | Expected invariant |
| --- | --- |
| Duplicate request | One accepted work item for the same idempotency identity |
| Vehicle becomes unavailable | No new assignment to that vehicle |
| Traffic resource is held | The route stops before the first unavailable resource |
| Cancellation during movement | No resource released or reused while a command may still be acted on |
| Equipment prohibited | Order and carrier state preserved until permission returns |
| Handover result uncertain | An explicit carrier-location decision before recovery can close |
| Adapter is disconnected | Device completion is never inferred from command submission |

### The link, pushed on purpose

The contract for sending is three-valued — nothing was written (send it again), receipt (never
execution), or written and nobody can say (never repeated by this system). Each verdict was tested
one at a time on a well-behaved line, so the line was then made to misbehave: silent, refusing,
dying with the bytes on it, answering late, answering twice, and doing all of it while more work
piled up for two vehicles. The fake controller records every command id it is ever sent, because a
verdict can be argued about and a second copy of the same id on the wire cannot. **Nothing was ever
heard twice.**

| Injected fault | Verdict | Times the far side heard it |
|---|---|---|
| No answer | `IN_DOUBT`, held | **1**, after five further sweeps |
| Late answer | receipt settles the doubt | 1 |
| Nobody listening | `NOT_DELIVERED`, retried | **1**, on the attempt that worked |
| Line dies mid-write | `IN_DOUBT` | 1 |
| Answered twice | one delivery, `SENT` | 1 |
| Earlier command in doubt | the next one **does not leave** | 0, then 1 once resolved |

The same question is asked of the upstream notice sender (a notice is said once, and a claim nobody
is working on is said again with the same key) and of the equipment statement (a port is told once,
and an unacknowledged statement is repeated word for word — which is safe only because a statement
is not a command).

### Who is at the other end

A gateway that dialled in used to be taken at its word. The vehicle's name is not a secret — it is
in the map file, on the screens and in every frame on the wire — so the question is now split in
two and both halves are tested: **who are you** is a certificate signed by an authority the site
trusts and carrying the name the profile expects; **what may you speak for** is unchanged, the
vehicles wired to that profile. A certificate for another machine, a certificate nobody trusted, a
plaintext peer on a secured profile and a missing trust store each get their own answer, and
nothing is ever quietly sent in the clear instead. See [link security](link-security.md).

## Under load

A floor at its own size: **321 nodes, 64 handover ports, 25 vehicles**, work written between ports
the way a host would write it, 15 minutes, with the queue deliberately saturated (50 open orders
against 25 vehicles).

All the numbers below come from **one run**, so they can be read together. A longer run of the same
load — two hours asked for, of which the machine was awake for 66 minutes — carried 168 orders
through with the same throughput (2.45 a minute) and the same three counters at zero over **4096
samples**. Its control-loop figures are not usable and are not quoted: every loop lost an hour at
once when the machine suspended, which the harness now says in the report rather than leaving it
to be read as the system's own.

| | |
|---|---|
| Work | 85 written, **35 completed**, 0 refused, 0 cancelled, 0 failed, 0 awaiting recovery |
| Never seen | two vehicles on one node **0**, a vehicle on road it does not hold **0**, deadlock **0** (863 samples) |
| Time | median 564s, p95 827s — completion includes queue wait, which is what saturation means |
| Afterwards | no reservation left behind, no vehicle still assigned to finished work, no command left claimed |

The run also measures the control loops, because under load the first thing to go wrong is not a
wrong answer but a late one. Each loop records how long a pass takes and, separately, the gap
between passes: the first is that loop's own work, the second is everything it waited behind.

That distinction is what found that every `@Scheduled` method shared one thread, so the 100ms
device read — the loop everything above trusts for where a vehicle is — was waiting behind a
simulation sweep. With the loops separated, each keeps its period: equipment links 210ms against a
200ms target, notices 1044 against 1000, the site sweep 1045 against 1000, the simulation sweep
1517 against 1500. The simulated motion step is the exception at 237ms against 100, by design: it
shares the simulation's one thread with a sweep that takes 797ms on this floor, and in OPERATION
there is no such loop at all.

## Trusting the instruments

Three of the first four things the load work found were faults in the scenario or in the measuring,
not in the system, and they are recorded as such:

- the invariant queries did not know a reservation is handed back by setting a column rather than
  by deleting the row, and read nine clean orders as nine faults;
- the floor watch read the vehicles at the start of a pass and the road later on, and once the
  simulation had a thread of its own it was reporting a floor that never existed;
- the sample layout had nine handover ports for 275 nodes, so the queue was limited by the map
  rather than by the control — an order holds both its ports from dispatch.

A check that turns "nothing wrong" into "something wrong" is worse than no check, and a run that
measures the wrong thing is worse than no run. Each was fixed before the system's own numbers were
reported.

## Boundary

The in-process simulator exercises the same order, eligibility, routing and traffic decisions as
operation; simulated equipment state and simulated handover evidence stand in for sensors **only**
in simulation mode.

Not verified here, and not claimed:

- real vehicles, real equipment, a real broker or a real upstream system. Every counterpart in
  these runs is a test socket or an HTTP server inside the tests;
- the real-device inbound acknowledgement path, equipment gateway integration, and plant safety
  interlocks;
- certificate revocation (expiry and signature only), and any authentication for Modbus/MELSEC
  equipment, which have none in the protocol — what stands in front of such a machine is the
  answer, and it is written down rather than implemented;
- sustained load beyond a single 15-minute run, and automated load regression. The numbers above
  are one run, driven by hand.
