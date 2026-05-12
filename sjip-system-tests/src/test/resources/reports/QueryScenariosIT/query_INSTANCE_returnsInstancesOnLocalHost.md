# query_INSTANCE_returnsInstancesOnLocalHost

## Query instances on the local host

> INSTANCE returns every instance whose host matches the local wotaskd's host, with state, refusal flag, statistics, deaths, and next-shutdown info.

### Action — POST monitorRequest{queryWotaskd: INSTANCE} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<queryWotaskd type="NSString">INSTANCE</queryWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<queryWotaskdResponse type="NSDictionary">
		<instanceResponse type="NSArray">
			<element type="NSDictionary">
				<applicationName type="NSString">DemoApp</applicationName>
				<deaths type="NSArray">
				</deaths>
				<host type="NSString">localhost</host>
				<id type="NSNumber">1</id>
				<nextShutdown type="NSString">-</nextShutdown>
				<port type="NSNumber">12345</port>
				<refusingNewSessions type="NSString">NO</refusingNewSessions>
				<runningState type="NSString">DEAD</runningState>
				<statistics type="NSDictionary">
				</statistics>
			</element>
		</instanceResponse>
	</queryWotaskdResponse>
</monitorResponse>
```

