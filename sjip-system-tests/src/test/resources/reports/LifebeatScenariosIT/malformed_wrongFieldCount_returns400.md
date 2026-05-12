# malformed_wrongFieldCount_returns400

## Send a lifebeat with too few fields

> Wotaskd's lifebeat parser requires exactly four ampersand-separated fields. Anything else is rejected.

### Action — GET http://127.0.0.1:<port>/cgi-bin/WebObjects/wotaskd.woa/wlb?lifebeat&DemoApp

### Wire send — test → wotaskd (GET /wlb?lifebeat&...)

```
(empty body)
```

### Wire receive — wotaskd → test (HTTP 400)

```
(empty body)
```

