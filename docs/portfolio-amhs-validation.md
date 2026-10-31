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
| Automated | **537 tests**, failures 0, errors 0, **skipped 0**, on an isolated PostgreSQL cluster the project starts for the run (`scripts/test-postgres.ps1`) |
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

**And a certificate can be withdrawn.** A gateway taken off the floor used to keep a working
certificate until it expired — over two years with the material the document tells an operator to
make — because the only way to stop it was to change the authority, which means reissuing to every
other gateway as well; a procedure nobody carries out for one retired box. A revocation list is
now read with the trust store, from a file rather than fetched: a controller that has to open a
connection to the outside before it may open one to the floor has the order backwards, and a
responder that cannot be reached always resolves to "assume it is fine", which is the quiet
downgrade all of this exists to refuse. Four things are tested, including the two that cost
something — the retired certificate does not get in and its vehicle claim is never reached; the
gateway still on the floor is **unaffected**, because a revocation check that stops everything is
an outage with a reason attached; the same holds dialling out, with nothing sent in the clear
instead; and a list that has run out **refuses rather than passes**, which makes refreshing it an
operational duty, so the communication check reports it a week before it bites.

## Under load

A floor at its own size: **321 nodes, 64 handover ports, 25 vehicles**, work written between ports
the way a host would write it, 15 minutes, with the queue deliberately saturated (50 open orders
against 25 vehicles).

All the numbers below come from **one run**, so they can be read together. The same load was then
run for **two uninterrupted hours**: 267 orders completed, the same three counters at zero over
**6899 samples**, the same throughput (2.2 a minute against 2.3 in the short run). An earlier
two-hour attempt is not quoted, because the machine suspended and every loop lost an hour at once —
the harness now says that in the report rather than leaving it to be read as the system's own.

Over those two hours the three device-facing loops stayed on their periods — equipment links 210ms
against 200, notices and the site sweep 1043 against 1000 — while the simulation sweep's own pass
grew to 1145ms mean as the saturated queue put more vehicles in motion, pushing the motion step it
shares a thread with to a 343ms gap. The loop that has to hold 100ms in operation is the device
read, and it is not the one that drifted.

| | |
|---|---|
| Work | 85 written, **35 completed**, 0 refused, 0 cancelled, 0 failed, 0 awaiting recovery |
| Never seen | two vehicles on one node **0**, a vehicle on road it does not hold **0**, deadlock **0** (863 samples) |
| Time | median 564s, p95 827s — completion includes queue wait, which is what saturation means |
| Afterwards | no reservation left behind, no vehicle still assigned to finished work, no command left claimed |

### Three times the floor

The same load on **787 nodes, 192 handover ports and 75 vehicles** — the layout generator takes a
multiplier, and it scales the dimension a fab scales in: the line gets longer and carries more
bays, rather than the loops getting wider or nested. The shape is what makes node reservation
sufficient here (two routes meeting only at nodes they both hold), and a proved shape is not worth
trading for a larger number. At a multiplier of one it writes the committed map byte for byte, and
at three every geometry check still comes back empty.

Fifteen minutes, queue saturated at 150 open orders: 180 written, 30 completed, the three counters
at zero over 881 samples, every "none expected" check empty. **The device-facing loops came out
better than on the small floor** — mean gaps of 211, 1022 and 1023ms against periods of 200, 1000
and 1000 — which is what separate threads buy: the simulator got three times busier and those
three loops did not notice.

What the bigger floor strains is the simulator, not the control. The simulation sweep's own pass
rose to 1382ms mean against its 1500ms period (739ms at 25 vehicles), and the motion step sharing
its thread drifted to a 301ms gap. That loop does not exist in an operating plant, which is why
the verdict does not judge it — but it does mean 75 simulated vehicles is near what this machine
will model, and a larger floor than this would be measuring the simulator.

### Two hours of it, and the first real failure

The same floor for two hours: 494 written, **344 completed**, nothing refused, cancelled, failed or
left awaiting recovery, and the three counters at zero over **7051 samples**. The device-facing
loops held throughout — equipment links averaged 210ms against a 200ms period and never once
exceeded 238ms, with notices and the site sweep at 1021 against 1000.

And it **failed**, on the floor rather than on the instrument: two vehicles had been waiting for
road for over three minutes. Sixty-eight of the seventy-five were in waiting chains when the run
ended, which is a jam rather than a cycle — no deadlock was detected in any sample, and nearly all
of those waits were seconds old. Two were not. Following one: it had wanted the same node for three
and a half minutes, while the vehicle holding that node had held it for forty-five seconds. The
node kept turning over to others and its turn never came.

The rule that would have prevented this exists — first to ask is first served, which is why a wait
keeps its original timestamp across repeated refusals — but it applies only at nodes marked as
merge points, and the generated layouts mark none, although every bay rejoin is a merge by
construction. At 25 vehicles nobody starved; at 75 somebody did. **This is what the larger floor
was built to find**, and the fix belongs in the layout rather than in the control.

The generator now marks them — where a bay rejoins the line, and where each loop takes the other's
traffic; 34 nodes on this floor, 14 on the small one. The diverge is deliberately not marked: both
routes leave it from the same side, so it is a queue rather than a merge. Thirty minutes of the
same load afterwards passed, and the waits it ended with say why: 59 of them, **none over three
minutes**, the longest 91 seconds against the 210 that failed the two-hour run. Twenty-five of
those 59 point at a vehicle that does not hold the node they want but asked for it earlier — the
rule firing, which could not have happened before.

It costs something, and the figure is in the log rather than left out: at the same thirty-minute
mark the unmarked run had completed 83 against 67 here, about a fifth fewer, because keeping the
order at a merge leaves a free node briefly unused. Under a queue held deliberately past capacity
that is a congestion figure and not a capacity one — and a wait that never ends is worse for a
plant than a floor that runs a little slower, since a starved carrier becomes a late lot. The
confirmation still owed is the two-hour version: the starvation appeared at two hours, so the
evidence that it is gone has to be two hours as well.

### The run decides

A reading is not a regression. The harness now ends with a verdict and an exit code — 0 when the
floor held, 1 when it did not, and 2 when the run **cannot be judged**: the site stopped
progressing, or the machine slept through part of it. That third code is not a failure, and the
order matters, because a run that was not a measurement of load cannot be evidence of a defect in
it — and what a stopped site leaves behind looks like a fault by these questions while being the
designed answer (road ahead of a delivered move stays with that vehicle). Calling it a failure
teaches somebody to ignore the verdict.

Which checks must come back empty is taken from their own headings in the invariants file, so a
check is enforced by having been written; a verdict that restated the questions would drift from
them, and the copy nobody reads drifts first.

Adding it found one more fault in the measuring. A second run of the same command wrote **one**
order in its first minute against the 240 a minute asked for: the previous run's fifty open orders
were still there, filling the open-work cap, and then finishing during this run and being counted
as its own — with durations that started in the run before. A run on the harness's own database now
begins with a clear order book.

Because it decides for itself, nobody has to watch it: it is registered with the task scheduler,
daily, and the exit code becomes the task's own result. Three things about that are chosen rather
than defaulted — it runs **as the person who registered it, logged on, with no password asked for
or stored** (the isolated cluster's password is kept under that account's own key, and a task that
needs a stored password is a password in one more place); it does **not wake the machine**, a
missed run starting at the next opportunity instead; and it will **not start on battery** while
also not being killed mid-run if the power goes, because the verdict of a run that was stopped
halfway means nothing either way. On a laptop that last one means the task waits, visibly, for the
mains.

Two judged runs have passed so far. The first, ten minutes: 59 written, 9 completed, three
counters at zero over 578 samples, every "none expected" check empty, the device-facing loops
inside their periods. The second at the length the schedule will repeat, twelve minutes: 55
written, 5 completed, the same counters at zero over 692 samples, the same checks empty, equipment
links 209ms against a 200ms period and the notices and site sweep 1042 and 1043 against 1000.
Throughput is deliberately not compared between them or with the longer runs — each left the
vehicles where the last one parked them, and only the invariants and the loop periods are
comparable between runs that start from different floors.

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
- any authentication for Modbus/MELSEC equipment, which have none in the protocol — what stands in
  front of such a machine is the answer, and it is written down rather than implemented. Revocation
  by OCSP is not missing but declined, for the reason above;
- load beyond two hours, and a floor larger than 787 nodes -- past which this machine is measuring
  its own simulator rather than the control. The judged run is on a daily schedule now; every run
  quoted above was started by hand.
