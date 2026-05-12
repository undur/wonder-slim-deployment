# query_APPLICATION_returnsApplicationsAndRunningCounts

## Query applications

> APPLICATION returns each configured application with its current running-instances count.

### Action — POST monitorRequest{queryWotaskd: APPLICATION} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<queryWotaskd type="NSString">APPLICATION</queryWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<queryWotaskdResponse type="NSDictionary">
		<applicationResponse type="NSArray">
			<element type="NSDictionary">
				<name type="NSString">DemoApp</name>
				<runningInstances type="NSNumber">0</runningInstances>
			</element>
		</applicationResponse>
	</queryWotaskdResponse>
</monitorResponse>
```

