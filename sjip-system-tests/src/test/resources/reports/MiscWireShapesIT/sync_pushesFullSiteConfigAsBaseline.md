# sync_pushesFullSiteConfigAsBaseline

## Send sync

> `sync` is what JavaMonitor pushes to wotaskds that previously had errors, to re-establish canonical state. The envelope carries a complete SiteConfig dict, similar to overwrite but with different intent (the receiver merges rather than replacing).

### Action — POST monitorRequest{updateWotaskd: {sync: {SiteConfig: {...}}}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<sync type="NSDictionary">
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
			</SiteConfig>
		</sync>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
	</updateWotaskdResponse>
</monitorResponse>
```

