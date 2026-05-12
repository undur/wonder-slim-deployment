# configureHost_updatesHostFields

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
			<name type="NSString">localhost</name>
			<type type="NSString">UNIX</type>
		</element>
	</hostArray>
	<instanceArray type="NSArray">
		<element type="NSDictionary">
			<applicationName type="NSString">DemoApp</applicationName>
			<hostName type="NSString">localhost</hostName>
			<id type="NSNumber">1</id>
			<port type="NSNumber">12345</port>
		</element>
	</instanceArray>
	<site type="NSDictionary">
		<viewRefreshRate type="NSNumber">30</viewRefreshRate>
	</site>
</SiteConfig>
```

## Configure the host

> Configuring a host replaces the host's values dict wholesale — the full intended state goes in the envelope. We change `type` here from UNIX to MACOSX.

### Action — POST monitorRequest{updateWotaskd: {configure: {hostArray: [{name, type}]}}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<configure type="NSDictionary">
			<hostArray type="NSArray">
				<element type="NSDictionary">
					<name type="NSString">localhost</name>
					<type type="NSString">MACOSX</type>
				</element>
			</hostArray>
		</configure>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<configure type="NSDictionary">
			<hostArray type="NSArray">
				<element type="NSDictionary">
					<success type="NSString">YES</success>
				</element>
			</hostArray>
		</configure>
	</updateWotaskdResponse>
</monitorResponse>
```

## Resulting state

### State — wotaskd SiteConfig.xml after configureHost

```xml
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
		<element type="NSDictionary">
			<name type="NSString">DemoApp</name>
		</element>
	</applicationArray>
	<hostArray type="NSArray">
		<element type="NSDictionary">
			<name type="NSString">localhost</name>
			<type type="NSString">MACOSX</type>
		</element>
	</hostArray>
	<instanceArray type="NSArray">
		<element type="NSDictionary">
			<applicationName type="NSString">DemoApp</applicationName>
			<hostName type="NSString">localhost</hostName>
			<id type="NSNumber">1</id>
			<port type="NSNumber">12345</port>
		</element>
	</instanceArray>
	<site type="NSDictionary">
		<viewRefreshRate type="NSNumber">30</viewRefreshRate>
	</site>
</SiteConfig>
```

