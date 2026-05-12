# Wire protocol inventory

The wotaskd↔JavaMonitor wire protocol is a fixed vocabulary of XML envelopes
sent over HTTP. This document enumerates every distinct shape and, for each
one, points at the snapshot files that lock it in.

The goal: when the domain model refactor lands, the wire shapes shouldn't
shift silently. Any snapshot diff is a signal — either the shape genuinely
changed (regenerate intentionally with `UPDATE_SNAPSHOTS=true`) or it changed
by accident and we caught it.

## How to read the status tags

- **[covered]** — at least one IT snapshots both the request and response
  for this shape against a real wotaskd subprocess
- **[partial]** — request OR response snapshotted, but not both; or covered
  only via an indirect path (e.g. captured as a side-effect of another test)
- **[missing]** — no snapshot exists yet; tests TBD

When a shape needs additional coverage, prefer adding a new IT alongside the
existing ones rather than extending an existing scenario beyond its name —
the test name is itself documentation.

## HTTP surface (wotaskd)

| Path                                            | Handler                                          | Notes                                  |
|-------------------------------------------------|--------------------------------------------------|----------------------------------------|
| `POST /cgi-bin/WebObjects/wotaskd.woa/wa/monitorRequest` | `DirectAction.monitorRequestAction`              | The admin channel; carries the XML envelopes below |
| `GET  /cgi-bin/WebObjects/wotaskd.woa/wlb?...`  | `LifebeatRequestHandler`                         | UDP-style lifebeat notifications (HTTP GET, semicolon-style query string) |
| `GET  /cgi-bin/WebObjects/wotaskd.woa/wa/default` | `DirectAction.defaultAction`                     | Human status page; used as readiness probe |
| `GET  /cgi-bin/WebObjects/wotaskd.woa/wa/woconfig` | `DirectAction.woconfigAction`                    | Returns the adaptor config XML        |
| `POST /cgi-bin/WebObjects/wotaskd.woa/wa/getPath` | `RemoteBrowse.getPathAction`                     | Filesystem browser for the UI         |

JavaMonitor's `AdminAction` exposes a parallel direct-action surface
(`/cgi-bin/WebObjects/JavaMonitor.woa/admin/<action>`) but that's
JavaMonitor's *receive* side, not part of the wotaskd wire protocol — it's
what tests use to drive JavaMonitor.

## `monitorRequest` envelope vocabulary

Every `monitorRequest` body is an `NSDictionary` containing exactly one of
`updateWotaskd`, `commandWotaskd`, or `queryWotaskd`. The response is always
a `monitorResponse` with the matching `*Response` key plus an optional
`errorResponse` array.

### `updateWotaskd`

Inner dict carries one of `clear`, `overwrite`, `sync`, `remove`, `add`, or
`configure`. `clear`/`overwrite`/`sync` short-circuit the rest;
`remove`/`add`/`configure` can carry any combination of `hostArray`,
`applicationArray`, `instanceArray` (with `configure` also accepting `site`).

#### `clear` — **[covered]**

```
monitorRequest → updateWotaskd → clear (any string value)
```

Dead-code branch on the wotaskd side (see FIXME at `DirectAction.java:145`
— no client sends this today). Snapshotted anyway so a future deletion is a
deliberate decision rather than an accidental one.

- Request: `MiscWireShapes/clear-request.xml`
- Response: `MiscWireShapes/clear-response.xml`

#### `overwrite` — **[covered]**

```
monitorRequest → updateWotaskd → overwrite → SiteConfig (full dict)
```

Pushes a complete SiteConfig to wotaskd, replacing what's there. Sent by
JavaMonitor when adding a new host (the new host gets an overwrite to
bootstrap it with the full topology).

- Request: `JavaMonitorAddFirstHost/javamonitor-to-wotaskd-overwrite-request.xml`
- Response: `JavaMonitorAddFirstHost/javamonitor-to-wotaskd-overwrite-response.xml`

#### `sync` — **[covered]**

```
monitorRequest → updateWotaskd → sync → SiteConfig
```

JavaMonitor sends this to wotaskds that had previous errors, to re-establish
the canonical view. See `WOTaskdComms.syncHostsWithErrors`. Wire detail
caught by snapshotting: the response carries an *empty* `updateWotaskdResponse`
with no `sync` key inside (wotaskd processes the sync but doesn't add a per-call
response entry).

- Request: `MiscWireShapes/sync-request.xml`
- Response: `MiscWireShapes/sync-response.xml`

#### `add` — **[covered]**

```
monitorRequest → updateWotaskd → add → hostArray|applicationArray|instanceArray
```

Per-element response shape: `[{success: YES}, ...]` or error elements.

- `hostArray`: **[covered]** — `AddHostScenario/addHost-request.xml` + `addHost-response.xml`
- `applicationArray`: **[covered]** — `AddApplicationScenario/addApplication-request.xml` + `addApplication-response.xml`
- `instanceArray`: **[covered]** — `AddInstanceScenario/addInstance-request.xml` + `addInstance-response.xml`

#### `remove` — **[covered]**

```
monitorRequest → updateWotaskd → remove → hostArray|applicationArray|instanceArray
```

Symmetric to `add`. All three sub-shapes covered by `RemoveScenariosIT`.

- `hostArray`: **[covered]** — `RemoveScenarios/removeHost-request.xml` + `removeHost-response.xml`. Exercises the special local-host branch (`stopAllInstances + setSiteConfig(new MSiteConfig(null))`) since the seeded host is `localhost`.
- `applicationArray`: **[covered]** — `RemoveScenarios/removeApplication-*.xml`
- `instanceArray`: **[covered]** — `RemoveScenarios/removeInstance-*.xml`

#### `configure` — **[covered]**

```
monitorRequest → updateWotaskd → configure → site|hostArray|applicationArray|instanceArray
```

Modifies existing entries in place. `applicationArray` and `instanceArray`
elements may carry `oldname`/`oldport` to identify the entry being renamed.
All four sub-shapes plus both rename variants covered by `ConfigureScenariosIT`.

- `site`: **[covered]** — `ConfigureScenarios/configureSite-*.xml`. Wire detail
  caught: `updateValues` does a wholesale replacement, so a configure with just
  `{viewRefreshRate: 30}` drops the previous `viewRefreshEnabled` key from the
  persisted site dict.
- `hostArray`: **[covered]** — `ConfigureScenarios/configureHost-*.xml`
- `applicationArray`: **[covered]** — `ConfigureScenarios/configureApplication-*.xml`
- `applicationArray (rename)`: **[covered]** — `ConfigureScenarios/configureApplication-rename-*.xml`.
  Wire detail caught: the `oldname` key persists in the SiteConfig dict after the rename;
  instances' `applicationName` is *not* cascade-updated.
- `instanceArray`: **[covered]** — `ConfigureScenarios/configureInstance-*.xml`
- `instanceArray (port change)`: **[covered]** — `ConfigureScenarios/configureInstance-portchange-*.xml`.
  Wire detail caught: `oldport` persists in the dict after the change.

### `commandWotaskd`

Different shape from the others: an `NSArray` rather than a dict. First
element is the command name; subsequent elements are instance identifiers
(`{hostName, port}` dicts).

Valid commands: `START`, `STOP`, `REFUSE`, `ACCEPT`, `QUIT`, `CLEAR`.

#### **[covered]** — all six commands snapshotted

```
monitorRequest → commandWotaskd: ["START", {hostName, port}, ...]
                                 ["STOP",  {hostName, port}, ...]
                                 ["REFUSE", ...]
                                 ["ACCEPT", ...]
                                 ["QUIT",   ...]
                                 ["CLEAR",  ...]
```

Response per-instance: `{success: YES}` or `{failure: YES, errorMessage: ...}`.

Each command covered in `CommandScenariosIT` (six test methods,
`start-*.xml` / `stop-*.xml` / `refuse-*.xml` / `accept-*.xml` /
`quit-*.xml` / `clear-*.xml`).

Wire detail caught: the response array has *two* success elements per
command, not one — wotaskd appends an unconditional success on dispatch
recognition before processing per-instance entries.

The seeded instance is hosted at `1.2.3.4` (a numeric IP that parses
without DNS and is non-local from wotaskd's point of view), which puts
each command through the "non-local instance" short-circuit branch that
returns success without trying to manipulate a real process. That's the
canonical success-response shape; error-response shapes (instance not
found, etc.) are future work and would need normalization of the host
name in error messages.

### `queryWotaskd`

A bare string: `"SITE"`, `"HOST"`, `"APPLICATION"`, or `"INSTANCE"`.

#### `SITE` — **[covered]**

Returns the full SiteConfig dict.

- Empty config: `WotaskdBootSmoke/querySite-emptyConfig-request.xml` + `...-response.xml`
- After adding a host: `AddHostScenario/querySite-afterAddHost-response.xml`, `JavaMonitorAddFirstHost/wotaskd-querySite-afterAddHost-response.xml`

#### `HOST` — **[covered, normalized]**

Returns `{hostResponse: {runningInstances, processorType, operatingSystem}}`.

- Request: `QueryScenarios/queryHost-request.xml`
- Response (normalized): `QueryScenarios/queryHost-response-normalized.xml`

`processorType` and `operatingSystem` are read from the local machine's
system properties (`os.arch`, `os.name`, `os.version`), so the test
normalizes those values to placeholders before snapshotting. The shape is
locked in; the values aren't.

#### `APPLICATION` — **[covered]**

Returns `{applicationResponse: [{name, runningInstances}, ...]}`.

- Request: `QueryScenarios/queryApplication-request.xml`
- Response: `QueryScenarios/queryApplication-response.xml`

#### `INSTANCE` — **[covered, normalized]**

Returns `{instanceResponse: [{applicationName, id, host, port, runningState, refusingNewSessions, statistics, deaths, nextShutdown}, ...]}`.

The richest shape on the response side — touches `statistics()`, which is
itself a small nested dict — and the one most likely to grow during the
domain refactor.

- Request: `QueryScenarios/queryInstance-request.xml`
- Response (normalized): `QueryScenarios/queryInstance-response-normalized.xml`

The `<statistics>` sub-dict is replaced with a placeholder in the
snapshot since its contents may shift run-to-run; everything else
(state, deaths, etc.) is deterministic for an instance that's never
been started.

## Lifebeat (`/wlb`)

GET-only, semicolon-style query string. Four notification types from the
*instance side*, sent to wotaskd:

| Notification | When                                                |
|--------------|-----------------------------------------------------|
| `hasStarted` | An instance has finished starting up               |
| `lifebeat`   | Periodic heartbeat                                 |
| `willStop`   | Graceful shutdown announcement                     |
| `willCrash`  | Instance is about to die                           |

JavaMonitor also sends `hasStarted` for itself at boot — we see this in
`JavaMonitorAddFirstHost` exchanges captured by the proxy.

#### **[covered]** — all four notification types snapshotted

`LifebeatScenariosIT` sends each notification kind against a seeded host +
application + instance, plus two malformed-request cases (unknown
notification type, wrong field count). Snapshots capture HTTP status +
response body for each — the URL format itself is also documented in the
generated reports.

| Notification | Status | Snapshot |
|---|---|---|
| `hasStarted` | 200 | `LifebeatScenarios/hasStarted-response-status.txt` |
| `lifebeat` | 200 | `LifebeatScenarios/lifebeat-response-status.txt` |
| `willStop` | 200 | `LifebeatScenarios/willStop-response-status.txt` |
| `willCrash` | 200 | `LifebeatScenarios/willCrash-response-status.txt` |
| (unknown notification) | 400 | `LifebeatScenarios/malformed-unknownNotification-response-status.txt` |
| (wrong field count) | 400 | `LifebeatScenarios/malformed-wrongFieldCount-response-status.txt` |

Wire detail caught while writing the test: the lifebeat handler nulls out
its response for HTTP/1.0 requests (see `LifebeatRequestHandler.java` around
line 132), which means HTTP/1.0 clients can't observe 400s on malformed
requests — they get an empty 200 instead. The test uses HTTP/1.1 with
{@code Connection: close} to work around this. Worth knowing if/when that
HTTP/1.0 branch gets revisited (already flagged as pending verification in
the source).

## Other wotaskd endpoints

#### `defaultAction` — **[partial]**

Used only as readiness probe; response body is the human status page.
Currently we only assert "responds with some HTTP status," not the body.
Coverage low priority — the body is human-readable HTML, not a wire shape
we depend on.

#### `woconfigAction` — **[covered]**

Returns adaptor config XML — small, deterministic, single response shape.
Used by WO HTTP adaptors (e.g. mod_WebObjects), not by JavaMonitor.

- Response: `MiscWireShapes/woconfig-response.xml`

The current snapshot captures the empty-config case (no registered
instances → `<adaptor></adaptor>`). A richer snapshot with running
instances would require a real instance process, which the test harness
doesn't spin up today.

#### `getPathAction` — **[out of scope]**

Filesystem browser used by JavaMonitor's UI; returns directory listings.
Not part of the data-model contract — filesystem-dependent, machine-specific.
Listed here for completeness only.

> Separate concern worth flagging: exposing arbitrary server filesystem
> browsing through a web UI is a real attack surface, even gated by
> JavaMonitor's password. Worth revisiting whether we want to keep the
> feature at all, independent of test coverage.

## Summary

| Category                       | Covered | Partial | Missing |
|--------------------------------|--------:|--------:|--------:|
| `updateWotaskd/clear`          |       1 |       0 |       0 |
| `updateWotaskd/overwrite`      |       1 |       0 |       0 |
| `updateWotaskd/sync`           |       1 |       0 |       0 |
| `updateWotaskd/add` (3 arrays) |       3 |       0 |       0 |
| `updateWotaskd/remove` (3 arrays) |    3 |       0 |       0 |
| `updateWotaskd/configure` (6 variants) |  6 |     0 |       0 |
| `commandWotaskd` (6 commands)  |       6 |       0 |       0 |
| `queryWotaskd` (4 kinds)       |       4 |       0 |       0 |
| Lifebeat (4 notifications + 2 errors) | 6 |   0 |       0 |
| `defaultAction`                |       0 |       1 |       0 |
| `woconfigAction`               |       1 |       0 |       0 |
| **Total**                      |  **32** |   **1** |   **0** |

`getPathAction` is intentionally not counted — out of scope for the wire
inventory.

The remaining `partial` is `defaultAction`, which we only use as a
readiness probe; the human-readable HTML body isn't a wire shape we depend
on. Could be promoted to covered if we ever want to lock in that page's
content, but low value.
