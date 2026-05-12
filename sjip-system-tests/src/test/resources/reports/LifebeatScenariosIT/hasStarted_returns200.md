# hasStarted_returns200

## Send a hasStarted lifebeat

> URL format: /wlb?<notification>&<instanceName>&<hostname>&<port>. Ampersand-separated, not key=value.

### Action — GET http://127.0.0.1:<port>/cgi-bin/WebObjects/wotaskd.woa/wlb?hasStarted&DemoApp&localhost&12345

### Wire send — test → wotaskd (GET /wlb?hasStarted&...)

```
(empty body)
```

### Wire receive — wotaskd → test (HTTP 200)

```
(empty body)
```

