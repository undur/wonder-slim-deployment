# removeHost

## Initial state (before removeHost)

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

## removeHost

### Action — POST JavaMonitor /test/removeHost

### Wire send — test → JavaMonitor (POST /test/removeHost)

```
name=localhost
```

### Wire receive — JavaMonitor → test (HTTP 200)

```
OK
```

## Resulting state

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

