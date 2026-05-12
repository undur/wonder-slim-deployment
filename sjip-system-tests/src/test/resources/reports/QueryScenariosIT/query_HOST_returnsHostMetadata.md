# query_HOST_returnsHostMetadata

## Query the local host

> HOST returns the local wotaskd's machine info — processor type, OS, running-instance count.

### Action — POST monitorRequest{queryWotaskd: HOST} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<queryWotaskd type="NSString">HOST</queryWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<queryWotaskdResponse type="NSDictionary">
		<hostResponse type="NSDictionary">
			<operatingSystem type="NSString">Mac OS X 15.7.1</operatingSystem>
			<processorType type="NSString">aarch64</processorType>
			<runningInstances type="NSNumber">0</runningInstances>
		</hostResponse>
	</queryWotaskdResponse>
</monitorResponse>
```

