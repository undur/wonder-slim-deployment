# configureSite_updatesSiteWideSettings

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
		<viewRefreshEnabled type="NSString">YES</viewRefreshEnabled>
		<viewRefreshRate type="NSNumber">60</viewRefreshRate>
	</site>
</SiteConfig>
```

## Configure the site dict

> The `site` sub-shape touches site-wide knobs — viewRefresh settings, password, etc. We send a minimal change here: just a new viewRefreshRate.

### Action — POST monitorRequest{updateWotaskd: {configure: {site: {viewRefreshRate: 30}}}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<configure type="NSDictionary">
			<site type="NSDictionary">
				<viewRefreshRate type="NSNumber">30</viewRefreshRate>
			</site>
		</configure>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<configure type="NSDictionary">
			<site type="NSDictionary">
				<success type="NSString">YES</success>
			</site>
		</configure>
	</updateWotaskdResponse>
</monitorResponse>
```

## Resulting state

### State — wotaskd SiteConfig.xml after configureSite

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

