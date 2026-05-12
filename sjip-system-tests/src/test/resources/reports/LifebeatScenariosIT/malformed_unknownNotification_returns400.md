# malformed_unknownNotification_returns400

## Send a lifebeat with an unrecognised notification type

> Wotaskd should reject unknown notification types. The exact wire shape: same four-field query string format, with an invalid value at position 0.

### Action — GET http://127.0.0.1:<port>/cgi-bin/WebObjects/wotaskd.woa/wlb?bogusNotification&DemoApp&localhost&12345

### Wire send — test → wotaskd (GET /wlb?bogusNotification&...)

```
(empty body)
```

### Wire receive — wotaskd → test (HTTP 400)

```
(empty body)
```

