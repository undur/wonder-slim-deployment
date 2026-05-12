# removeHost_succeeds_andPersistsSiteConfig

## Initial state (instance + application already removed; only host left)

### State — wotaskd SiteConfig.xml

```xml
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
	</applicationArray>
	<hostArray type="NSArray">
		<element type="NSDictionary">
			<name type="NSString">localhost</name>
			<type type="NSString">UNIX</type>
		</element>
	</hostArray>
	<instanceArray type="NSArray">
	</instanceArray>
	<site type="NSDictionary">
		<viewRefreshEnabled type="NSString">YES</viewRefreshEnabled>
		<viewRefreshRate type="NSNumber">60</viewRefreshRate>
	</site>
</SiteConfig>
```

## Remove the host

> Removing the local host triggers a different code path in wotaskd than removing a remote host — stopAllInstances + replacing the SiteConfig wholesale. The wire envelope is identical though.

### Action — POST monitorRequest{updateWotaskd: {remove: {hostArray: [{name}]}}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<remove type="NSDictionary">
			<hostArray type="NSArray">
				<element type="NSDictionary">
					<name type="NSString">localhost</name>
				</element>
			</hostArray>
		</remove>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<remove type="NSDictionary">
			<hostArray type="NSArray">
				<element type="NSDictionary">
					<success type="NSString">YES</success>
				</element>
			</hostArray>
		</remove>
	</updateWotaskdResponse>
</monitorResponse>
```

## Resulting state

### State — wotaskd SiteConfig.xml after removeHost

```xml
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
	</applicationArray>
	<hostArray type="NSArray">
	</hostArray>
	<instanceArray type="NSArray">
	</instanceArray>
	<site type="NSDictionary">
		<viewRefreshEnabled type="NSString">YES</viewRefreshEnabled>
		<viewRefreshRate type="NSNumber">60</viewRefreshRate>
	</site>
</SiteConfig>
```

