# querySite_afterAddHost_reportsTheNewHost

> Verifies that a subsequent SITE query reports the host added by the previous test.

### Action — POST monitorRequest{queryWotaskd: SITE} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<queryWotaskd type="NSString">SITE</queryWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<queryWotaskdResponse type="NSDictionary">
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
	</queryWotaskdResponse>
</monitorResponse>
```

