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

#### `clear` — **[missing]**

```
monitorRequest → updateWotaskd → clear (any string value)
```

Dead-code branch on the wotaskd side (see FIXME at `DirectAction.java:145`
— no client sends this today). Listed for completeness; coverage optional.

#### `overwrite` — **[covered]**

```
monitorRequest → updateWotaskd → overwrite → SiteConfig (full dict)
```

Pushes a complete SiteConfig to wotaskd, replacing what's there. Sent by
JavaMonitor when adding a new host (the new host gets an overwrite to
bootstrap it with the full topology).

- Request: `JavaMonitorAddFirstHost/javamonitor-to-wotaskd-overwrite-request.xml`
- Response: `JavaMonitorAddFirstHost/javamonitor-to-wotaskd-overwrite-response.xml`

#### `sync` — **[missing]**

```
monitorRequest → updateWotaskd → sync → SiteConfig
```

JavaMonitor sends this to wotaskds that had previous errors, to re-establish
the canonical view. See `WOTaskdComms.syncHostsWithErrors`.

#### `add` — **[partial]**

```
monitorRequest → updateWotaskd → add → hostArray|applicationArray|instanceArray
```

Per-element response shape: `[{success: YES}, ...]` or error elements.

- `hostArray`: **[covered]** — `AddHostScenario/addHost-request.xml` + `addHost-response.xml`
- `applicationArray`: **[missing]**
- `instanceArray`: **[missing]**

#### `remove` — **[missing]**

```
monitorRequest → updateWotaskd → remove → hostArray|applicationArray|instanceArray
```

Symmetric to `add`. Same three sub-shapes, all uncovered.

- `hostArray`: **[missing]** — note also the special case where removing the
  local host triggers `stopAllInstances + setSiteConfig(new MSiteConfig(null))`
  rather than `removeHost_W` (see `DirectAction.java:184-191`)
- `applicationArray`: **[missing]**
- `instanceArray`: **[missing]**

#### `configure` — **[missing]**

```
monitorRequest → updateWotaskd → configure → site|hostArray|applicationArray|instanceArray
```

Modifies existing entries in place. `applicationArray` and `instanceArray`
elements may carry `oldname`/`oldport` to identify the entry being renamed.

- `site`: **[missing]** — touches global site settings (`viewRefreshEnabled`, `viewRefreshRate`, etc.)
- `hostArray`: **[missing]**
- `applicationArray`: **[missing]** — including the rename path
- `instanceArray`: **[missing]** — including the port-change path

### `commandWotaskd`

Different shape from the others: an `NSArray` rather than a dict. First
element is the command name; subsequent elements are instance identifiers
(`{hostName, port}` dicts).

Valid commands: `START`, `STOP`, `REFUSE`, `ACCEPT`, `QUIT`, `CLEAR`.

#### **[missing]** — each command needs a snapshot

```
monitorRequest → commandWotaskd: ["START", {hostName, port}, ...]
                                 ["STOP",  {hostName, port}, ...]
                                 ["REFUSE", ...]
                                 ["ACCEPT", ...]
                                 ["QUIT",   ...]
                                 ["CLEAR",  ...]
```

Response per-instance: `{success: YES}` or `{failure: YES, errorMessage: ...}`.

Adding meaningful coverage here requires the test to first stand up an
instance — i.e. add host → add application → add instance → command it. So
this depends on `add/applicationArray` and `add/instanceArray` shapes being
exercisable first.

### `queryWotaskd`

A bare string: `"SITE"`, `"HOST"`, `"APPLICATION"`, or `"INSTANCE"`.

#### `SITE` — **[covered]**

Returns the full SiteConfig dict.

- Empty config: `WotaskdBootSmoke/querySite-emptyConfig-request.xml` + `...-response.xml`
- After adding a host: `AddHostScenario/querySite-afterAddHost-response.xml`, `JavaMonitorAddFirstHost/wotaskd-querySite-afterAddHost-response.xml`

#### `HOST` — **[missing]**

Returns `{hostResponse: {runningInstances, processorType, operatingSystem}}`.

#### `APPLICATION` — **[missing]**

Returns `{applicationResponse: [{name, runningInstances}, ...]}`.

#### `INSTANCE` — **[missing]**

Returns `{instanceResponse: [{applicationName, id, hostName, port, runningState, refusingNewSessions, statistics, deaths, nextScheduledShutdown}, ...]}`.

The richest shape on the response side — touches `statistics()`, which is
itself a small nested dict — and the one most likely to grow during the
domain refactor.

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

#### **[partial]** — captured but not asserted

The wire-capturing proxy in `JavaMonitorAddFirstHostIT` sees JavaMonitor's
own `hasStarted` lifebeat, but the test doesn't snapshot it (the proxy
records it as a `CapturedExchange` but the report-recording filter excludes
the `/wlb` path).

Adding coverage means snapshotting at least one lifebeat per notification
type. Could live in a dedicated `LifebeatIT` that:

1. Boots wotaskd
2. Sends a hand-crafted GET to `/wlb?<notification>;<args>` for each type
3. Snapshots wotaskd's response and the resulting SiteConfig state

## Other wotaskd endpoints

#### `defaultAction` — **[partial]**

Used only as readiness probe; response body is the human status page.
Currently we only assert "responds with some HTTP status," not the body.
Coverage low priority — the body is human-readable HTML, not a wire shape
we depend on.

#### `woconfigAction` — **[missing]**

Returns adaptor config XML — small, deterministic, single response shape.
Used by WO HTTP adaptors (e.g. mod_WebObjects), not by JavaMonitor. Worth
one snapshot of the empty-config and one-host cases.

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
| `updateWotaskd/clear`          |       0 |       0 |       1 |
| `updateWotaskd/overwrite`      |       1 |       0 |       0 |
| `updateWotaskd/sync`           |       0 |       0 |       1 |
| `updateWotaskd/add` (3 arrays) |       1 |       0 |       2 |
| `updateWotaskd/remove` (3 arrays) |    0 |       0 |       3 |
| `updateWotaskd/configure` (4 entries) |  0 |       0 |       4 |
| `commandWotaskd` (6 commands)  |       0 |       0 |       6 |
| `queryWotaskd` (4 kinds)       |       1 |       0 |       3 |
| Lifebeat (4 notifications)     |       0 |       1 |       3 |
| `defaultAction`                |       0 |       1 |       0 |
| `woconfigAction`               |       0 |       0 |       1 |
| **Total**                      |   **4** |   **2** |  **24** |

`getPathAction` is intentionally not counted — out of scope for the wire
inventory.
