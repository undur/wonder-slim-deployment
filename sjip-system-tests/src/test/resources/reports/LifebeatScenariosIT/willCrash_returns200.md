# willCrash_returns200

## Send a willCrash lifebeat

> URL format: /wlb?<notification>&<instanceName>&<hostname>&<port>. Ampersand-separated, not key=value.

### Action — GET http://127.0.0.1:<port>/cgi-bin/WebObjects/wotaskd.woa/wlb?willCrash&DemoApp&localhost&12345

### Wire send — test → wotaskd (GET /wlb?willCrash&...)

```
(empty body)
```

### Wire receive — wotaskd → test (HTTP 200)

```
(empty body)
```

