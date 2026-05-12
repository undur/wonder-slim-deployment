# addApplication_succeeds_andPersistsSiteConfig

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

## Add an application

### Action — POST monitorRequest{updateWotaskd: {add: {applicationArray: [{name: DemoApp}]}}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<add type="NSDictionary">
			<applicationArray type="NSArray">
				<element type="NSDictionary">
					<name type="NSString">DemoApp</name>
				</element>
			</applicationArray>
		</add>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<add type="NSDictionary">
			<applicationArray type="NSArray">
				<element type="NSDictionary">
					<success type="NSString">YES</success>
				</element>
			</applicationArray>
		</add>
	</updateWotaskdResponse>
</monitorResponse>
```

## Resulting state

### State — wotaskd SiteConfig.xml after addApplication

```xml
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
		<element type="NSDictionary">
			<name type="NSString">DemoApp</name>
		</element>
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

