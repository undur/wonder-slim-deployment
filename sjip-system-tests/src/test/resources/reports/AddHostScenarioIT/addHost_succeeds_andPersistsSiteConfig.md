# addHost_succeeds_andPersistsSiteConfig

## Initial state

### State — wotaskd SiteConfig.xml

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

## Add a host

### Action — POST monitorRequest{updateWotaskd: {add: {hostArray: [{name: localhost, type: UNIX}]}}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<add type="NSDictionary">
			<hostArray type="NSArray">
				<element type="NSDictionary">
					<name type="NSString">localhost</name>
					<type type="NSString">UNIX</type>
				</element>
			</hostArray>
		</add>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<add type="NSDictionary">
			<hostArray type="NSArray">
				<element type="NSDictionary">
					<success type="NSString">YES</success>
				</element>
			</hostArray>
		</add>
	</updateWotaskdResponse>
</monitorResponse>
```

## Resulting state

### State — wotaskd SiteConfig.xml after addHost

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

