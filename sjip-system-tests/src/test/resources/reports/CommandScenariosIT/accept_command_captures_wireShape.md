# accept_command_captures_wireShape

## Initial state

### State — wotaskd SiteConfig.xml

```xml
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
		<element type="NSDictionary">
			<name type="NSString">DemoApp</name>
		</element>
	</applicationArray>
	<hostArray type="NSArray">
		<element type="NSDictionary">
			<name type="NSString">1.2.3.4</name>
			<type type="NSString">UNIX</type>
		</element>
	</hostArray>
	<instanceArray type="NSArray">
		<element type="NSDictionary">
			<applicationName type="NSString">DemoApp</applicationName>
			<hostName type="NSString">1.2.3.4</hostName>
			<id type="NSNumber">1</id>
			<port type="NSNumber">12345</port>
		</element>
	</instanceArray>
	<site type="NSDictionary">
		<viewRefreshEnabled type="NSString">YES</viewRefreshEnabled>
		<viewRefreshRate type="NSNumber">60</viewRefreshRate>
	</site>
</SiteConfig>
```

## Send ACCEPT to the instance

### Action — POST monitorRequest{commandWotaskd: [ACCEPT, {hostName, port}]} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<commandWotaskd type="NSArray">
		<element type="NSString">ACCEPT</element>
		<element type="NSDictionary">
			<hostName type="NSString">1.2.3.4</hostName>
			<port type="NSNumber">12345</port>
		</element>
	</commandWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<commandWotaskdResponse type="NSArray">
		<element type="NSDictionary">
			<success type="NSString">YES</success>
		</element>
		<element type="NSDictionary">
			<success type="NSString">YES</success>
		</element>
	</commandWotaskdResponse>
</monitorResponse>
```

