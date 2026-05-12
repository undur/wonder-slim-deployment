# wotaskdBoots_createsEmptySiteConfigFile

> On startup, wotaskd writes a SiteConfig.xml to its configured directory even if no config exists yet.

### Action — Read wotaskd's SiteConfig.xml from its config directory

### State — wotaskd SiteConfig.xml after boot

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

