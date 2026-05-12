# configureInstance_withPortChange_usesOldPort

## Initial state (instance still on port 12345)

### State — wotaskd SiteConfig.xml

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
			<applicationName type="NSString">RenamedApp</applicationName>
			<hostName type="NSString">localhost</hostName>
			<id type="NSNumber">1</id>
			<port type="NSNumber">12345</port>
			<scheduled type="NSString">YES</scheduled>
		</element>
	</instanceArray>
	<site type="NSDictionary">
		<viewRefreshRate type="NSNumber">30</viewRefreshRate>
	</site>
</SiteConfig>
```

## Change the instance's port

> When the new port doesn't match an existing instance, wotaskd falls back to looking up by `oldport`. The wire envelope carries both keys.

### Action — POST monitorRequest{updateWotaskd: {configure: {instanceArray: [{hostName, port: 54321, oldport: 12345}]}}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<configure type="NSDictionary">
			<instanceArray type="NSArray">
				<element type="NSDictionary">
					<applicationName type="NSString">RenamedApp</applicationName>
					<hostName type="NSString">localhost</hostName>
					<id type="NSNumber">1</id>
					<oldport type="NSNumber">12345</oldport>
					<port type="NSNumber">54321</port>
				</element>
			</instanceArray>
		</configure>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<configure type="NSDictionary">
			<instanceArray type="NSArray">
				<element type="NSDictionary">
					<success type="NSString">YES</success>
				</element>
			</instanceArray>
		</configure>
	</updateWotaskdResponse>
</monitorResponse>
```

## Resulting state

### State — wotaskd SiteConfig.xml after port change

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
			<applicationName type="NSString">RenamedApp</applicationName>
			<hostName type="NSString">localhost</hostName>
			<id type="NSNumber">1</id>
			<oldport type="NSNumber">12345</oldport>
			<port type="NSNumber">54321</port>
		</element>
	</instanceArray>
	<site type="NSDictionary">
		<viewRefreshRate type="NSNumber">30</viewRefreshRate>
	</site>
</SiteConfig>
```

