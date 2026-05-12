# woconfigAction_returnsAdaptorConfigXml

## GET woconfig

> woconfigAction returns adaptor-config XML used by WO HTTP adaptors. Distinct shape from monitorRequest envelopes. Headers indicate text/xml and a Last-Modified timestamp.

### Action — GET /cgi-bin/WebObjects/wotaskd.woa/wa/woconfig

### Wire send — test → wotaskd (GET /wa/woconfig)

```
(empty body)
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<?xml version="1.0" encoding="ASCII"?>
<adaptor>
</adaptor>
```

