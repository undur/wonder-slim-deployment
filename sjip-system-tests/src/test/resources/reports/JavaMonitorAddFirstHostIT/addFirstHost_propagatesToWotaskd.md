# addFirstHost_propagatesToWotaskd

## Initial state

### State — wotaskd SiteConfig.xml (empty)

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

## Drive JavaMonitor's addHost direct action

> JavaMonitor's `WOTaskdHandler.sendOverwriteToWotaskd` should push the new SiteConfig to wotaskd over the wire — captured by the proxy below.

### Action — POST JavaMonitor /admin/addHost (name=localhost, hostType=UNIX)

### Wire send — test → JavaMonitor (POST /admin/addHost)

```
name=localhost&hostType=UNIX
```

### Wire send — JavaMonitor → wotaskd (POST /cgi-bin/WebObjects/wotaskd.woa/wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<overwrite type="NSDictionary">
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
		</overwrite>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → JavaMonitor (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<overwrite type="NSDictionary">
			<success type="NSString">YES</success>
		</overwrite>
	</updateWotaskdResponse>
</monitorResponse>
```

### Wire receive — JavaMonitor → test (HTTP 200)

```
OK
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

