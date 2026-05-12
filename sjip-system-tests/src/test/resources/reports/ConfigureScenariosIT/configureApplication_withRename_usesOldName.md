# configureApplication_withRename_usesOldName

## Initial state (application still named DemoApp)

### State — wotaskd SiteConfig.xml

```xml
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
		<element type="NSDictionary">
			<autoRecover type="NSString">YES</autoRecover>
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

## Rename the application

> When the new `name` doesn't match an existing application, wotaskd falls back to looking up by `oldname`. The wire envelope carries both keys.

### Action — POST monitorRequest{updateWotaskd: {configure: {applicationArray: [{name: RenamedApp, oldname: DemoApp}]}}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<configure type="NSDictionary">
			<applicationArray type="NSArray">
				<element type="NSDictionary">
					<name type="NSString">RenamedApp</name>
					<oldname type="NSString">DemoApp</oldname>
				</element>
			</applicationArray>
		</configure>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<configure type="NSDictionary">
			<applicationArray type="NSArray">
				<element type="NSDictionary">
					<success type="NSString">YES</success>
				</element>
			</applicationArray>
		</configure>
	</updateWotaskdResponse>
</monitorResponse>
```

## Resulting state

### State — wotaskd SiteConfig.xml after rename

```xml
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
		<element type="NSDictionary">
			<name type="NSString">RenamedApp</name>
			<oldname type="NSString">DemoApp</oldname>
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

