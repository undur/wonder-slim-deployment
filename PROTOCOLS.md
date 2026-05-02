# WebObjects Deployment Infrastructure — Protocol Documentation

This document describes the configuration formats, wire protocols, and communication patterns used by **wotaskd** and **JavaMonitor** in the WebObjects deployment infrastructure as it lives in this repository today. All claims have been cross-referenced with source.

---

## Table of Contents

1. [Overview](#overview)
2. [Process Model](#process-model)
3. [The Three Protocol Layers](#the-three-protocol-layers)
4. [Encoding](#encoding)
5. [Configuration Storage](#configuration-storage)
6. [Configuration Data Model](#configuration-data-model)
7. [JavaMonitor ↔ wotaskd Protocol](#javamonitor--wotaskd-protocol)
8. [wotaskd ↔ Application Protocol](#wotaskd--application-protocol)
9. [Lifebeat Protocol](#lifebeat-protocol)
10. [Multicast Discovery](#multicast-discovery)
11. [Synchronization Flow](#synchronization-flow)
12. [Authentication & Security](#authentication--security)
13. [External API Surface](#external-api-surface)
14. [System Properties Reference](#system-properties-reference)
15. [Source Map](#source-map)

---

## Overview

The deployment infrastructure consists of three components:

| Component | Role | Default Port |
|-----------|------|--------------|
| **JavaMonitor** | Web-based admin UI; talks to one or more `wotaskd`s | configured via `WOApplication` |
| **wotaskd** | Per-host daemon; manages local app instances; relays JavaMonitor's requests | 1085 (`wotaskd.Application.port()`) |
| **Application** | A deployed WOApplication instance | configured per-instance |

```
┌─────────────────┐    HTTP/XML envelope    ┌─────────────────┐
│   JavaMonitor   │◄───────────────────────►│     wotaskd     │
│   (admin UI)    │   monitorRequest /      │   (per host)    │
└─────────────────┘   monitorResponse       └────────┬────────┘
                                                     │
                                  HTTP/XML envelope  │
                                  instanceRequest /  │
                                  instanceResponse   │
                                  (plist string      │
                                  inside response    │
                                  for STATISTICS)    │
                                                     ▼
                                            ┌─────────────────┐
                                            │   Application   │
                                            │     instance    │
                                            └────────┬────────┘
                                                     │
                                            HTTP GET │  Lifebeat
                                            (local)  │
                                                     ▼
                                          (back to local wotaskd)
```

---

## Process Model

`wotaskd` launches each WOApplication instance as a **child process** via `Runtime.getRuntime().exec(aLaunchPath)` (`InstanceController.java:501`). The child inherits `wotaskd`'s process group.

Optionally, when the `WOShouldUseSpawn` system property is set and a `SpawnOfWotaskd.sh` (or `SpawnOfWotaskd.exe` on Windows) helper is present at `Contents/Resources/`, the launch path is prefixed with that helper.

**Operational consequence — wotaskd shutdown takes child apps with it.** This is a process-tree reaping property of the current launch implementation, not a property of any of the application-level protocols.

JavaMonitor and wotaskd are independent processes today; deployments often run both on the same host.

---

## The Three Protocol Layers

| # | Direction | Endpoint | Envelope |
|---|---|---|---|
| 1 | JavaMonitor → wotaskd | `POST /cgi-bin/WebObjects/wotaskd.woa/wa/monitorRequest` | XML, root key `monitorRequest`/`monitorResponse` |
| 2 | wotaskd → application | `POST /cgi-bin/WebObjects/<App>.woa/womp/instanceRequest` | XML, root key `instanceRequest`/`instanceResponse` |
| 3 | application → wotaskd | `GET  /cgi-bin/WebObjects/wotaskd.woa/wlb?…` | (no body) |

Layer 1 and Layer 2 use the same XML format (see [Encoding](#encoding)). Layer 2's response to a STATISTICS query additionally carries an OpenStep ASCII property list **as a string** inside the XML envelope — see [wotaskd ↔ Application Protocol](#wotaskd--application-protocol).

A separate adaptor-discovery channel uses IP multicast — see [Multicast Discovery](#multicast-discovery).

---

## Encoding

### Primary format: WebObjects-flavoured XML

`x.FoundationCoder` is a JDK-only encoder/decoder that produces WebObjects-flavoured typed XML, byte-compatible with the historical Apple `_JavaMonitorCoder` / `_JavaMonitorDecoder` pair for the value subset deployment uses. The Apple reference classes are no longer used in production code; they remain on the test classpath as a byte-equivalence oracle (`FoundationCoderTest.java`).

The format is structural, with `type` attributes naming the original Foundation class. Captured fixture (`SiteConfig.xml`):

```xml
<SiteConfig type="NSDictionary">
    <hostArray type="NSArray">
        <element type="NSDictionary">
            <type type="NSString">UNIX</type>
            <name type="NSString">app1.example.test</name>
        </element>
    </hostArray>
    <applicationArray type="NSArray">
        <element type="NSDictionary">
            <cachingEnabled type="NSString">YES</cachingEnabled>
            <startingPort type="NSNumber">2001</startingPort>
            <lifebeatInterval type="NSNumber">30</lifebeatInterval>
            <!-- ... -->
        </element>
    </applicationArray>
</SiteConfig>
```

**Supported value types** (per `FoundationCoder.encodeObjectForKey`):

| Java | XML `type` | Notes |
|------|------------|-------|
| `Map` | `NSDictionary` | **Keys are sorted alphabetically on encode** (FoundationCoder.java:194-200). The legacy reference encoder did *not* sort and emitted in NSMutableDictionary's hash order, so byte-for-byte equality between the two writers requires using the same input map. |
| `List` | `NSArray` | Elements emitted as `<element type="…">` |
| `String` | `NSString` | XML-escaped (`& < > ' "`) |
| `Number` | `NSNumber` | `toString()`'d. Decoder parses as `Integer` first, falls back to `Double` |
| `Boolean` | `NSString` (lossy — see below) | Encoded as the literal `"YES"` / `"NO"` strings |
| `byte[]` / `NSData` | `NSData` | Base64 |
| `null` | `?` | Encoded as `<key type="?">null</key>` |

**Boolean caveat (lossy round-trip).** Booleans become `NSString` `"YES"` / `"NO"` on the wire. The decoder rewrites *every* string whose text content is exactly `"YES"` or `"NO"` back to `Boolean.TRUE` / `Boolean.FALSE`. **A string field containing the literal word "YES" is therefore indistinguishable from a boolean on the wire.** This matches the historical reference encoder.

### Secondary format: OpenStep ASCII property lists

Used in two places:

1. **Inside `instanceResponse`** when answering a `STATISTICS` query — the `queryInstanceResponse` field contains a `String` holding an ASCII plist.
2. **JavaMonitor's `/stats` external endpoint** emits ASCII plist as its response body.

`x.FoundationPropertyListSerialization` is a JDK-only reader/writer mimicking `NSPropertyListSerialization`'s ASCII output byte-for-byte for the value subset used. The writer always quotes scalars (numbers, identifiers, booleans alike); the parser accepts both quoted and bare tokens, which is what live WebObjects apps emit.

---

## Configuration Storage

### Files

| File | Purpose |
|------|---------|
| `${WODeploymentConfigurationDirectory}/SiteConfig.xml` | Master configuration |
| `${WODeploymentConfigurationDirectory}/WOConfig.xml` | Generated adaptor configuration (when `WOSavesAdaptorConfiguration` is enabled) |
| `${WODeploymentConfigurationDirectory}/SiteConfig.xml.<timestamp>` | Recovery-rename when `SiteConfig.xml` fails to parse on load |
| `${WODeploymentConfigurationDirectory}/SiteConfigBackup.xml.<date>.<reason>` | Routine backups (when `WODeploymentBackups` is enabled) |

### Two distinct backup mechanisms

These are **two different code paths** with different formats and triggers — they are not the same:

1. **Recovery rename** (`MSiteConfig.backupSiteConfig()`, line 870): runs only inside wotaskd, only when `SiteConfig.xml` fails to parse on startup. Renames the existing file to `SiteConfig.xml.<yyyyMMddHHmmssSSS>` (compact filename-safe timestamp). Not gzipped.

2. **Routine backups** (`MSiteConfig.backup(action)`, line 1066): runs only when `WODeploymentBackups=true` and the just-saved config differs from the last-saved config. Writes a new file `SiteConfigBackup.xml.<yyyy-MM-dd-hh_mm_ss>.<action>`. **Always GZIP compressed**, regardless of compression flags. Note: the timestamp uses lowercase `hh` (12-hour clock 1-12) — morning/afternoon timestamps collide.

3. `forceBackup(reason)` (line 1077) is a public variant of #2 that ignores the property and always backs up.

### What's stored vs. derived

| Persisted to `SiteConfig.xml` | Runtime-only (never serialized) |
|---|---|
| Site config, hosts, applications, instances | `_lastRegistration` (per-instance) |
| Scheduling settings (type, hour, day, etc.) | `_finishStartingByDate` (startup grace deadline) |
| | `_nextScheduledShutdown` (computed from scheduling settings) |
| | Statistics dict (live cache from app) |
| | Death array (in-memory log) |
| | `_connectFailureCount`, `state`, `isRefusingNewSessions` |

`nextScheduledShutdownString` is a derived display string in the local time zone, computed by wotaskd at each `INSTANCE` query and shipped under the wire field name `nextShutdown`. It is never persisted.

---

## Configuration Data Model

### Hierarchy

```
SiteConfig
├── site                   (global settings, dictionary)
├── hostArray[]            (deployment hosts)
├── applicationArray[]     (application definitions)
└── instanceArray[]        (running instances)
```

### Site

Global settings.

| Key | Type | Description |
|-----|------|-------------|
| `password` | String | Salted MD5 admin password (see [Authentication](#authentication--security)) |
| `woAdaptor` | String | Web adaptor URL |
| `SMTPhost` | String | SMTP server for crash notifications |
| `emailReturnAddr` | String | From-address for crash emails |
| `viewRefreshEnabled` | Boolean | UI auto-refresh toggle |
| `viewRefreshRate` | Integer | UI refresh interval (seconds) |
| `sequence` | Integer | Configuration sequence number |
| `retries` | Integer | Adaptor connect retry count |
| `scheduler` | String | Adaptor load balancing — `RANDOM`, `ROUNDROBIN`, `LOADAVERAGE`, or a custom scheduler name |
| `dormant` | Integer | Adaptor dormant connection timeout |
| `redir` | String | Adaptor redirect URL on error |
| `sendTimeout` / `recvTimeout` / `cnctTimeout` | Integer | Adaptor socket timeouts |
| `sendBufSize` / `recvBufSize` | Integer | Adaptor socket buffer sizes |
| `poolsize` | Integer | Adaptor connection pool size |
| `urlVersion` | Integer | Adaptor URL format version (`3` or `4`) |

### Host

| Key | Type | Description |
|-----|------|-------------|
| `name` | String | Hostname |
| `type` | String | `UNIX`, `WINDOWS`, or `MACOSX` |

### Application

Each entry in `applicationArray`:

| Key | Type | Description |
|-----|------|-------------|
| `name` | String | Application name |
| `startingPort` | Integer | Base port for this app's instances |
| `timeForStartup` | Integer | Startup grace period (seconds) |
| `phasedStartup` | Boolean | Stagger instance starts |
| `autoRecover` | Boolean | Auto-restart crashed instances |
| `minimumActiveSessionsCount` | Integer | Min sessions before scheduled shutdown proceeds |
| `unixPath` / `winPath` / `macPath` | String | Per-OS executable path |
| `unixOutputPath` / `winOutputPath` / `macOutputPath` | String | Per-OS log directory |
| `cachingEnabled` | Boolean | App-level caching |
| `debuggingEnabled` | Boolean | Debug mode |
| `autoOpenInBrowser` | Boolean | Open browser on start |
| `adaptor` | String | Adaptor class name |
| `adaptorThreads` / `adaptorThreadsMin` / `adaptorThreadsMax` | Integer | Adaptor thread counts |
| `listenQueueSize` | Integer | Listen queue depth |
| `projectSearchPath` | String | Project search path |
| `sessionTimeOut` | Integer | Session timeout (seconds) |
| `statisticsPassword` | String | Password protecting the per-app stats endpoint |
| `lifebeatInterval` | Integer | Expected lifebeat interval (seconds) |
| `additionalArgs` | String | Extra command-line args |
| `notificationEmailEnabled` | Boolean | Send email on crash |
| `notificationEmailAddr` | String | Crash notification address |

Plus all site-level adaptor settings (`retries`, `scheduler`, `dormant`, etc.) which **override** site-level defaults at the application level.

### Instance

Each entry in `instanceArray`:

| Key | Type | Description |
|-----|------|-------------|
| `hostName` | String | Host running this instance |
| `id` | Integer | Unique instance ID within an application |
| `port` | Integer | Listen port |
| `applicationName` | String | Parent application's name |
| `autoRecover` / `minimumActiveSessionsCount` | inherited | Per-instance overrides |
| `path` / `outputPath` | String | Resolved per-OS executable / log paths |
| `cachingEnabled` / `debuggingEnabled` / `autoOpenInBrowser` / `lifebeatInterval` / `additionalArgs` | inherited | Per-instance overrides |
| `schedulingEnabled` | Boolean | Scheduled-restart toggle |
| `schedulingType` | String | `HOURLY`, `DAILY`, or `WEEKLY` |
| `schedulingHourlyStartTime` / `schedulingDailyStartTime` / `schedulingWeeklyStartTime` | Integer | Hour `[0,23]` |
| `schedulingStartDay` | Integer | Day of week, **0=Sunday..6=Saturday** ([see below](#day-of-week-convention)) |
| `schedulingInterval` | Integer | Hours between restarts (HOURLY mode) |
| `gracefulScheduling` | Boolean | Graceful (drain sessions) vs. immediate shutdown |

Plus per-instance overrides for adaptor settings (`sendTimeout`, `recvTimeout`, etc.), inherited from application → site if not set on the instance.

### Day-of-week convention

`schedulingStartDay` uses **0=Sunday..6=Saturday** on disk and on the wire. This is the historical NeXTSTEP / classic Foundation convention. java.time's `DayOfWeek.getValue()` returns `1=Monday..7=Sunday`; the conversion happens internally in `MInstance.calculateNextScheduledShutdown` via `now.getDayOfWeek().getValue() % 7`. **Do not change the persisted convention** — old `SiteConfig.xml` files in the wild use it.

---

## JavaMonitor ↔ wotaskd Protocol

### Endpoint

```
POST /cgi-bin/WebObjects/wotaskd.woa/wa/monitorRequest
```

(constant `MUtil.WOTASKD_DIRECT_ACTION_URL`)

### Headers

| Header | Notes |
|---|---|
| `password` | The **already-encrypted** password (4-char hex salt + 32-char MD5 hex). JavaMonitor reads `siteConfig.password()` (the stored, encrypted form) and passes it directly. wotaskd does plain string equality — *not* a re-hash — via `MSiteConfig.comparePasswordWithPassword`. |

### Timeouts

- **JavaMonitor → wotaskd HTTP receive timeout** is hardcoded to **10000 ms** in `MHost.WOTASKD_RECEIVE_TIMEOUT`. A FIXME notes it used to be the property `JavaMonitor.receiveTimeout`; the property is no longer read.
- **wotaskd → instance HTTP receive timeout** is configurable via `WOTaskd.receiveTimeout` (default 5000 ms).

### Envelope

Both directions use FoundationCoder XML (see [Encoding](#encoding)).

- Request root key: `monitorRequest`
- Response root key: `monitorResponse`

### Request types

A single `monitorRequest` may carry one or more of:

| Top-level key | Purpose |
|---|---|
| `updateWotaskd` | Mutate wotaskd's in-memory model |
| `commandWotaskd` | Issue a command for one or more instances |
| `queryWotaskd` | Read state |

These are processed in order in `DirectAction.monitorRequestAction`: updates first, then commands, then queries.

### `updateWotaskd` payload

A dictionary containing one or more of these verbs. The dispatch is `if/else if`, so **only one of `clear` / `overwrite` / `sync` runs per request**, in that priority order. If none of those is present, `remove`, `add`, and `configure` may each run (in that order):

| Verb | Payload | Effect |
|---|---|---|
| `clear` | string flag (any non-null) | Stop all local instances, reset wotaskd's `MSiteConfig` to empty |
| `overwrite` | dict containing key `SiteConfig` (a full SiteConfig dictionary) | Stop all instances, replace wotaskd's `MSiteConfig` with the supplied one |
| `sync` | dict containing key `SiteConfig` | Reconcile wotaskd's model against the supplied authoritative model (merge semantics, no instance restart) |
| `remove` | dict with `hostArray` / `applicationArray` / `instanceArray` | Remove the named objects |
| `add` | same shape | Add new objects |
| `configure` | same shape | Modify existing objects |

### `commandWotaskd` payload

An NSArray. Slot 0 is the command verb (string). Slots 1..N are instance dictionaries — wotaskd's command dispatcher uses **only `hostName` and `port`** to look up the instance, but JavaMonitor's sender includes all four of `applicationName`, `id`, `hostName`, `port`.

| Command | wotaskd's response |
|---|---|
| `START` | Calls `localMonitor.startInstance()` → `Runtime.getRuntime().exec(...)`. No instanceRequest is sent. |
| `STOP` | Calls `terminateInstance()` → sends `instanceRequest{commandInstance:{command:"TERMINATE"}}`. |
| `QUIT` | Sets `instance.setShouldDie(true)`. **No instanceRequest is sent.** The instance is told to die on its next lifebeat (via `DieResponse` 500). |
| `REFUSE` | Calls `stopInstance()` → sends `instanceRequest{commandInstance:{command:"REFUSE", minimumActiveSessionsCount:N}}`. App refuses new sessions, exits when active sessions ≤ N. |
| `ACCEPT` | Calls `setAcceptInstance()` → sends `instanceRequest{commandInstance:{command:"ACCEPT"}}`. Cancels a pending REFUSE. |
| `CLEAR` | Local-only: clears the instance's death array. No instanceRequest. |

### `queryWotaskd` payload

A single string verb:

| Query | Returns (under `queryWotaskdResponse`) |
|---|---|
| `SITE` | The full SiteConfig under key `SiteConfig` |
| `HOST` | `hostResponse` with `runningInstances`, `processorType`, `operatingSystem` |
| `APPLICATION` | `applicationResponse[]` of `{name, runningInstances}` |
| `INSTANCE` | `instanceResponse[]` of `{applicationName, id, host, port, runningState, refusingNewSessions, statistics, deaths, nextShutdown}` |

### Response shape

```
monitorResponse
├── updateWotaskdResponse?
│   └── <verb>: per-object {success: true/false, errorMessage?: "..."}
├── commandWotaskdResponse?
│   ├── [0]: {success}             (command-known status)
│   └── [1..]: per-instance {success, errorMessage?}
├── queryWotaskdResponse?
│   └── (see queries above)
└── errorResponse?                 (NSArray<String> of host-level errors)
```

### Instance running state — string, not integer

`runningState` in `instanceResponse` is sent as the **string name** indexed from `MUtil.INSTANCE_STATES`:

| Index | String | Meaning |
|---|---|---|
| 0 | `"UNKNOWN"` | Not registered |
| 1 | `"STARTING"` | In startup grace period |
| 2 | `"ALIVE"` | Running and responsive |
| 3 | `"STOPPING"` | Graceful shutdown in progress |
| 4 | `"DEAD"` | Terminated normally |
| 5 | `"CRASHING"` | Abnormal termination |

### HTTP-level errors

| Status | Body | Trigger |
|---|---|---|
| 200 | `monitorResponse` XML | Normal response (may still contain `errorResponse` array) |
| 200 | `_invalidXML` (prebuilt XML) | Body could not be decoded by FoundationCoder. **No error status is set** — the error body is returned with HTTP 200. |
| 403 | `_invalidPassword` (prebuilt XML) | Password header missing or wrong |
| 403 | `_accessDenied` (prebuilt XML) | Request came in via a web server adaptor (`isUsingWebServer()` is true) — wotaskd refuses to serve through Apache/etc. |

The static field `_emptyXML` is defined in `DirectAction.java:78` but never used. Ignore.

---

## wotaskd ↔ Application Protocol

A separate XML-over-HTTP channel used when wotaskd needs to command or query a deployed instance.

### Endpoint

```
POST /cgi-bin/WebObjects/<AppName>.woa/womp/instanceRequest
```

(constants `MUtil.ADMIN_ACTION_STRING_PREFIX` + `<AppName>` + `MUtil.ADMIN_ACTION_STRING_POSTFIX`)

### Envelope

- Request root key: `instanceRequest`
- Response root key: `instanceResponse`

Both FoundationCoder XML.

### Payload shape

A single `instanceRequest` may carry:

| Key | Value | Purpose |
|---|---|---|
| `commandInstance` | dict `{ command: <verb>, minimumActiveSessionsCount?: <int> }` | Instruct the instance |
| `queryInstance` | string verb (currently only `STATISTICS`) | Ask the instance for live state |

Both keys may appear in one message; the instance acts on whichever it processes first. The combined form isn't useful in practice.

`commandInstance` verbs (per `InstanceController.createInstanceRequestDictionary`):

| Verb | Sent by wotaskd in response to | Effect |
|---|---|---|
| `TERMINATE` | JavaMonitor's `STOP` (`terminateInstance`) | Immediate shutdown |
| `REFUSE` | JavaMonitor's `REFUSE` (`stopInstance`) | Refuse new sessions; app exits when active sessions drop to `minimumActiveSessionsCount` |
| `ACCEPT` | JavaMonitor's `ACCEPT` (`setAcceptInstance`) | Resume taking new sessions |

`minimumActiveSessionsCount` is included only with the `REFUSE` verb.

There is no `STOP` or `QUIT` verb at this layer — JavaMonitor's `STOP` becomes wire-level `TERMINATE`, and JavaMonitor's `QUIT` does not flow through this protocol at all (it sets `shouldDie` on wotaskd, which translates to `DieResponse` on the next lifebeat).

### The plist-in-XML detail

When wotaskd sends `queryInstance: "STATISTICS"`, the WOApp's response has shape:

```
instanceResponse
├── errorResponse?            (NSArray<String> of error messages)
└── queryInstanceResponse     (NSString containing an ASCII plist!)
```

The `queryInstanceResponse` value is **not** a structured nested dictionary — it is a flat NSString whose content is itself an OpenStep ASCII property list, which wotaskd then parses with `FoundationPropertyListSerialization.propertyListFromString` (`DirectAction.java:706`). The plist's shape is roughly:

```
{
  StartedAt = "2026-05-01 10:00:00 +0000";
  Transactions = {
    Transactions = "1593";
    "Avg. Transaction Time" = "0.011";
    "Avg. Idle Time" = "35.882";
  };
  Sessions = {
    "Current Active Sessions" = "6";
    ...
  };
  ...
}
```

This is the historical legacy of WOApp's stats endpoint pre-dating the XML monitor protocol — the app already serialized stats as a plist string, and the monitor protocol just shipped that string verbatim inside its XML envelope.

### Old-style fallback

If FoundationCoder fails to decode an instance's response (`DirectAction.java:685`), wotaskd falls back to parsing the raw response body as an ASCII plist directly, and emits an error noting the instance is "probably an older application that doesn't conform to the current Monitor Protocol." This branch exists for compatibility with very old WOApps that don't speak the XML envelope.

---

## Lifebeat Protocol

A lightweight keepalive from app instances to their local wotaskd.

### Endpoint

```
GET /cgi-bin/WebObjects/wotaskd.woa/wlb?<notification>&<instance_name>&<host>&<port>
```

The four parameters are supplied as a `&`-delimited query string and parsed by splitting `aRequest.queryString()` (note: query string, **not** path).

### Local-only and non-web-server check

`LifebeatRequestHandler.handleRequest` (line 64) requires both:
- `!aRequest.isUsingWebServer()` — the request must NOT come through a web server adaptor (Apache/etc.).
- `WOHostUtilities.isLocalInetAddress(aRequest._originatingAddress(), true)` — the originating IP must be local.

If either check fails, the handler returns null (no response). Lifebeat is therefore strictly intra-host.

### Notification verbs

| Verb | Effect |
|---|---|
| `hasStarted` | Register the instance (mark startup); if instance unknown to wotaskd, calls `registerUnknownInstance` |
| `lifebeat` | Update last-seen timestamp; return 500 if `instance.shouldDie` was set |
| `willStop` | Mark the instance as gracefully stopped; cancel any pending force-quit task |
| `willCrash` | Mark as crashed; trigger crash email if configured |

### Responses

| Status | Sent for | Trigger |
|---|---|---|
| 200 OK | `lifebeat` (normal), `hasStarted` | App should keep running |
| 400 Bad Request | unparseable query (wrong number of `&`-delimited fields, or unknown notification) | Logged as error |
| 500 Internal Server Error | `lifebeat` when `instance.shouldDieAndReset()` returns true | App should terminate |
| (no response) | `willStop`, `willCrash` | Handler returns `null` |
| (no response) | any case where `aRequest.httpVersion()` is `"HTTP/1.0"` | Handler returns `null` regardless |

The `DieResponse` (HTTP 500) is sent only when `setShouldDie(true)` was previously called on the instance — typically by JavaMonitor's `QUIT` command being received by wotaskd. It is **not** sent when an instance is unknown to wotaskd; in that case `registerUnknownInstance` is called and the lifebeat still returns 200.

### Lifebeat timeout math (pre-existing bug, preserved verbatim)

`MInstance.isRunning_W()` and `sendDeathNotificationEmail()` compute `cutOffTime = lastRegistration.toEpochMilli() + lifebeatCheckInterval()`. **`lifebeatCheckInterval()` returns seconds**, not milliseconds — the addition mixes units and the resulting cutoff is much further in the future than nominally configured. This is a pre-existing bug, preserved verbatim during the NSTimestamp → Instant migration; fixing it would tighten dead-detection behaviour.

---

## Multicast Discovery

Adaptor discovery uses IP multicast to find live wotaskd instances.

| Setting | Default | Notes |
|---|---|---|
| Multicast address | `239.128.14.2` | Override via `WOMulticastAddress` |
| Responding to multicast queries | **disabled** unless `WORespondsToMulticastQuery` is set to a truthy value | wotaskd always responds to non-multicast UDP packets |

The multicast listener thread is unconditionally started (`Application.java:151`) but `_shouldRespondToMulticast` defaults to false; only `WORespondsToMulticastQuery=true` (or any truthy boolean string) enables responses to multicast queries.

This channel is independent of the monitor protocol — it answers the question "is there a wotaskd here?", not "what's running?".

---

## Synchronization Flow

### Normal update

```
1. User mutates config in JavaMonitor UI.
2. MSiteConfig setters call _siteConfig.dataHasChanged() (legacy hook).
3. WOTaskdHandler builds a monitorRequest with the relevant payload.
4. Parallel HTTP POST to the wotaskd on each affected host (WOTaskdComms).
5. Each wotaskd processes and returns a monitorResponse.
6. errorResponse arrays are aggregated and surfaced in the UI.
```

### Error recovery

```
1. A request to a wotaskd fails.
2. Host is added to MSiteConfig.hostErrorArray.
3. A subsequent operation triggers a sync; failed hosts get a full SiteConfig
   pushed via the "sync" verb.
4. Host removed from hostErrorArray on success.
```

### Lock model

Both JavaMonitor and wotaskd use `ReentrantReadWriteLock`. The lock lives on the *Application* class, **not** on `MSiteConfig`:

| Process | Lock location | Helpers |
|---|---|---|
| JavaMonitor | `WOTaskdHandler._lock` (static) | `whileReading(Runnable)`, `whileLocked(Runnable)` |
| wotaskd | `Application._lock` (instance field, public) | direct `readLock()` / `writeLock()` calls at use sites |

| Operation | Lock |
|---|---|
| Status query | read |
| Configuration change | write |

---

## Authentication & Security

### Admin password

`MSiteConfig.password` stores admin credentials in a salted MD5 form:

```
Stored format: <4-char-hex-salt><32-char-hex-MD5>
                (uppercase 0-9 A-F)
```

**Compute** (`MSiteConfig.encryptStringWithKey`, line ~485):
1. Generate 4 random hex characters (`xdigit[]` = `{0..9, A..F}`) — used as the salt.
2. MD5(`fudge_constant` ‖ `plaintext` ‖ `salt`) where `fudge_constant = "X#@!"`.
3. Store `salt + hex(md5)`.

When called with a non-null `aKey` argument, that key is used in place of the random bytes (this is how `compareStringWithPassword` recomputes the hash for verification).

### Two distinct verification paths

The codebase has two functions that look similar but do different things:

1. **`MSiteConfig.comparePasswordWithPassword(String)`** — used by wotaskd's `DirectAction` for the `password` HTTP header. **Plain string equality** between the stored encrypted form and the input. Returns true when the stored password is null or empty (no-password-set means anyone may connect). The caller must therefore send the **already-encrypted** form, which is what JavaMonitor does (`MHost.sendRequestToWotaskd` passes `siteConfig.password()` verbatim).

2. **`MSiteConfig.compareStringWithPassword(String)`** — used by JavaMonitor's UI login, the `/admin/...` direct actions, and the `/stats` endpoint. Takes a plaintext input, extracts the salt from the stored encrypted password (first 4 chars), re-encrypts the input with that salt, compares the result. Used when the input is plaintext from a form / URL.

### Transport

- **No TLS** anywhere in the stack.
- **Lifebeat is local-only** by IP check and additionally rejects requests through a web-server adaptor.
- **wotaskd ↔ wotaskd password header** carries the already-hashed form, so a network attacker doesn't see the plaintext — but does see (and could replay) the hash.
- **JavaMonitor's `/admin/...` and `/stats` endpoints** use plaintext `pw=...` URL form parameters. Trivially sniffable.
- **`statisticsPassword`** on the application gates the per-app stats URL but is similarly unencrypted on the wire.

This is a 2000s-vintage protocol stack. Deploy behind a reverse proxy or VPN for any non-local network exposure.

### JavaMonitor session

The JavaMonitor UI itself has a session-based login flow (`Session.isLoggedIn`, `JMLoginPage`). External operators hitting `/admin/...` direct actions bypass the session login but still hit the `pw=` form-parameter check.

---

## External API Surface

### `/admin/<actionName>` direct actions on JavaMonitor

JavaMonitor's `Application.appDidFinishLaunching` (line ~41) registers a `WODirectActionRequestHandler` under the request handler key `admin`. The anonymous subclass overrides `getRequestHandlerPathForRequest` to inject `AdminAction`'s class name into the dispatch path, so callers can use a clean URL prefix:

```
GET /cgi-bin/WebObjects/JavaMonitor.woa/admin/<actionName>?pw=<plaintext>&<other-form-params>
```

Authentication: each call goes through `AdminAction.performActionNamed`, which checks `compareStringWithPassword(stringFormValueForKey("pw"))` (plaintext-then-encrypt). When the password is missing or wrong, returns HTTP 403 with body `"Monitor is password protected - password missing or incorrect."`. When no site password is set, the `pw` parameter is ignored.

Available actions (from `AdminAction.java`'s `xxxAction()` methods): `info`, `running`, `stopped`, `start`, `stop`, `bounce`, `forceQuit`, `clearDeaths`, `turnAutoRecoverOn`, `turnAutoRecoverOff`, `turnRefuseNewSessionsOn`, `turnRefuseNewSessionsOff`, `turnScheduledOn`, `turnScheduledOff`, `scheduleType`, `hourlyScheduleRange`, `dailyScheduleRange`, `weeklyScheduleRange`, `setAdditionalArgs`, etc. (`-Action` is implied — WO direct actions strip the suffix.)

### `/wa/statistics` on JavaMonitor

```
GET /cgi-bin/WebObjects/JavaMonitor.woa/wa/statistics?pw=<plaintext>
```

Implemented by `JavaMonitor.application.DirectAction.statisticsAction`. Authenticates via `compareStringWithPassword` (plaintext path). On success, body is an OpenStep ASCII plist describing every application — instance counts, session counts, transaction stats. **On failure (wrong password), returns HTTP 200 with empty body** (the response is created but never written to). External consumers depend on this format being stable.

### WOConfig.xml (generated adaptor configuration)

Generated when `WOSavesAdaptorConfiguration` is enabled. This is a *different* XML format from the monitor protocol — it's standard XML for the Apache mod_WebObjects adaptor.

```xml
<?xml version="1.0" encoding="ASCII"?>
<adaptor>
  <application name="MyApp"
               retries="3"
               scheduler="ROUNDROBIN"
               dormant="5"
               redir="error.html"
               poolsize="10"
               urlVersion="4">
    <instance id="1"
              port="20000"
              host="host1.example.com"
              sendTimeout="30"
              recvTimeout="30"
              cnctTimeout="10"
              sendBufSize="8192"
              recvBufSize="8192"/>
  </application>
</adaptor>
```

---

## System Properties Reference

| Property | Default | Effect |
|---|---|---|
| `WODeploymentConfigurationDirectory` | required | Where `SiteConfig.xml` lives |
| `WODeploymentBackups` | false | Enable routine `SiteConfigBackup.xml.<date>.<reason>` files (gzip) on save |
| `WOSavesAdaptorConfiguration` | false | Generate `WOConfig.xml` on save |
| `WOMulticastAddress` | `239.128.14.2` | Multicast discovery group |
| `WORespondsToMulticastQuery` | false (unless explicitly enabled) | Whether wotaskd answers multicast queries |
| `WOShouldUseSpawn` | false | Use the optional `SpawnOfWotaskd.sh` helper |
| `WOTaskd.receiveTimeout` | 5000 ms | wotaskd → instance HTTP receive timeout |
| `WOTaskd.killTimeout` | 120000 ms | Force-quit delay after graceful stop (`InstanceController.FORCE_QUIT_DELAY`); minimum 60000 ms |
| `WOTaskd.refuseNumRetries` | 3 | Number of REFUSE retries before force-quit |

`JavaMonitor.receiveTimeout` is **no longer functional** — the value is hardcoded to 10000 ms in `MHost.WOTASKD_RECEIVE_TIMEOUT` with a FIXME explaining the property used to drive it.

---

## Source Map

### Encoders / decoders

| File | Description |
|---|---|
| `JavaMonitorFramework/src/main/java/x/FoundationCoder.java` | WO XML encode/decode (the wire format) |
| `JavaMonitorFramework/src/main/java/x/FoundationPropertyListSerialization.java` | OpenStep ASCII plist read/write |
| `JavaMonitorFramework/src/test/java/x/FoundationCoderTest.java` | Cross-checks against Apple's `_JavaMonitorCoder` for byte-equivalence |
| `JavaMonitorFramework/src/test/java/x/FoundationPropertyListSerializationTest.java` | Cross-checks against `NSPropertyListSerialization` |

### Configuration model

| File | Description |
|---|---|
| `JavaMonitorFramework/.../model/MSiteConfig.java` | Root configuration object; load/save/backup; password encryption |
| `JavaMonitorFramework/.../model/MHost.java` | Host model |
| `JavaMonitorFramework/.../model/MApplication.java` | Application model |
| `JavaMonitorFramework/.../model/MInstance.java` | Instance model; lifebeat timing; scheduling math |
| `JavaMonitorFramework/.../MUtil.java` | URL constants, validators, scheduling-day mappings, `INSTANCE_STATES[]` |

### Communication

| File | Description |
|---|---|
| `JavaMonitor/.../util/WOTaskdHandler.java` | Builds and decodes monitorRequest/Response on the JavaMonitor side; holds the `_lock` |
| `JavaMonitor/.../util/WOTaskdComms.java` | Parallel HTTP request orchestration |
| `JavaMonitor/.../util/StatsUtilitiesEvenMore.java` | Builds the `/stats` ASCII-plist response |
| `JavaMonitor/.../util/JMUtil.java` | `fetchWotaskdConfigurationString` — fetches wotaskd's root URL |
| `JavaMonitor/.../application/DirectAction.java` | `/wa/...` direct actions including `statisticsAction` |
| `JavaMonitor/.../application/Application.java` | Registers the `admin` request handler |
| `JavaMonitor/.../application/admin/AdminAction.java` | `/admin/<action>` direct actions |
| `JavaMonitor/.../application/Session.java` | Session-based login flow |
| `wotaskd/.../Application.java` | wotaskd's WOApplication subclass; multicast listener; holds the `_lock` |
| `wotaskd/.../DirectAction.java` | The `monitorRequestAction` dispatcher (Layer 1 receive) |
| `wotaskd/.../InstanceController.java` | Builds and sends `instanceRequest`; spawns child processes |
| `wotaskd/.../LifebeatRequestHandler.java` | Receives and dispatches `wlb` queries |

---

*This document tracks the protocols as implemented in the codebase. Last reviewed against source on 2026-05-01.*
