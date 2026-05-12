# removeInstance

## Initial state (before removeInstance)

### State — wotaskd SiteConfig.xml

```xml
<SiteConfig type="NSDictionary">
	<applicationArray type="NSArray">
		<element type="NSDictionary">
			<adaptor type="NSString">WODefaultAdaptor</adaptor>
			<adaptorThreads type="NSNumber">8</adaptorThreads>
			<adaptorThreadsMax type="NSNumber">256</adaptorThreadsMax>
			<adaptorThreadsMin type="NSNumber">16</adaptorThreadsMin>
			<additionalArgs type="NSString"></additionalArgs>
			<autoOpenInBrowser type="NSString">NO</autoOpenInBrowser>
			<autoRecover type="NSString">YES</autoRecover>
			<cachingEnabled type="NSString">YES</cachingEnabled>
			<debuggingEnabled type="NSString">NO</debuggingEnabled>
			<lifebeatInterval type="NSNumber">30</lifebeatInterval>
			<listenQueueSize type="NSNumber">128</listenQueueSize>
			<macOutputPath type="NSString"></macOutputPath>
			<macPath type="NSString"></macPath>
			<minimumActiveSessionsCount type="NSNumber">0</minimumActiveSessionsCount>
			<name type="NSString">DemoApp</name>
			<notificationEmailEnabled type="NSString">NO</notificationEmailEnabled>
			<phasedStartup type="NSString">YES</phasedStartup>
			<projectSearchPath type="NSString">()</projectSearchPath>
			<sessionTimeOut type="NSNumber">3600</sessionTimeOut>
			<startingPort type="NSNumber">2001</startingPort>
			<statisticsPassword type="NSString"></statisticsPassword>
			<timeForStartup type="NSNumber">30</timeForStartup>
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
			<additionalArgs type="NSString"></additionalArgs>
			<applicationName type="NSString">DemoApp</applicationName>
			<autoOpenInBrowser type="NSString">NO</autoOpenInBrowser>
			<autoRecover type="NSString">YES</autoRecover>
			<cachingEnabled type="NSString">YES</cachingEnabled>
			<debuggingEnabled type="NSString">NO</debuggingEnabled>
			<gracefulScheduling type="NSString">YES</gracefulScheduling>
			<hostName type="NSString">localhost</hostName>
			<id type="NSNumber">1</id>
			<lifebeatInterval type="NSNumber">30</lifebeatInterval>
			<minimumActiveSessionsCount type="NSNumber">0</minimumActiveSessionsCount>
			<oldport type="NSNumber">2001</oldport>
			<outputPath type="NSString">/dev/null</outputPath>
			<port type="NSNumber">12345</port>
			<schedulingDailyStartTime type="NSNumber">3</schedulingDailyStartTime>
			<schedulingEnabled type="NSString">NO</schedulingEnabled>
			<schedulingHourlyStartTime type="NSNumber">3</schedulingHourlyStartTime>
			<schedulingInterval type="NSNumber">12</schedulingInterval>
			<schedulingStartDay type="NSNumber">1</schedulingStartDay>
			<schedulingType type="NSString">DAILY</schedulingType>
			<schedulingWeeklyStartTime type="NSNumber">3</schedulingWeeklyStartTime>
		</element>
	</instanceArray>
	<site type="NSDictionary">
		<viewRefreshEnabled type="NSString">YES</viewRefreshEnabled>
		<viewRefreshRate type="NSNumber">60</viewRefreshRate>
	</site>
</SiteConfig>
```

## removeInstance

### Action — POST JavaMonitor /test/removeInstance

### Wire send — test → JavaMonitor (POST /test/removeInstance)

```
port=12345&hostName=localhost
```

### Wire send — JavaMonitor → wotaskd (POST /cgi-bin/WebObjects/wotaskd.woa/wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<remove type="NSDictionary">
			<instanceArray type="NSArray">
				<element type="NSDictionary">
					<additionalArgs type="NSString"></additionalArgs>
					<applicationName type="NSString">DemoApp</applicationName>
					<autoOpenInBrowser type="NSString">NO</autoOpenInBrowser>
					<autoRecover type="NSString">YES</autoRecover>
					<cachingEnabled type="NSString">YES</cachingEnabled>
					<debuggingEnabled type="NSString">NO</debuggingEnabled>
					<gracefulScheduling type="NSString">YES</gracefulScheduling>
					<hostName type="NSString">localhost</hostName>
					<id type="NSNumber">1</id>
					<lifebeatInterval type="NSNumber">30</lifebeatInterval>
					<minimumActiveSessionsCount type="NSNumber">0</minimumActiveSessionsCount>
					<oldport type="NSNumber">2001</oldport>
					<outputPath type="NSString">/dev/null</outputPath>
					<port type="NSNumber">12345</port>
					<schedulingDailyStartTime type="NSNumber">3</schedulingDailyStartTime>
					<schedulingEnabled type="NSString">NO</schedulingEnabled>
					<schedulingHourlyStartTime type="NSNumber">3</schedulingHourlyStartTime>
					<schedulingInterval type="NSNumber">12</schedulingInterval>
					<schedulingStartDay type="NSNumber">1</schedulingStartDay>
					<schedulingType type="NSString">DAILY</schedulingType>
					<schedulingWeeklyStartTime type="NSNumber">3</schedulingWeeklyStartTime>
				</element>
			</instanceArray>
		</remove>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → JavaMonitor (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<remove type="NSDictionary">
			<instanceArray type="NSArray">
				<element type="NSDictionary">
					<success type="NSString">YES</success>
				</element>
			</instanceArray>
		</remove>
	</updateWotaskdResponse>
</monitorResponse>
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
		<element type="NSDictionary">
			<adaptor type="NSString">WODefaultAdaptor</adaptor>
			<adaptorThreads type="NSNumber">8</adaptorThreads>
			<adaptorThreadsMax type="NSNumber">256</adaptorThreadsMax>
			<adaptorThreadsMin type="NSNumber">16</adaptorThreadsMin>
			<additionalArgs type="NSString"></additionalArgs>
			<autoOpenInBrowser type="NSString">NO</autoOpenInBrowser>
			<autoRecover type="NSString">YES</autoRecover>
			<cachingEnabled type="NSString">YES</cachingEnabled>
			<debuggingEnabled type="NSString">NO</debuggingEnabled>
			<lifebeatInterval type="NSNumber">30</lifebeatInterval>
			<listenQueueSize type="NSNumber">128</listenQueueSize>
			<macOutputPath type="NSString"></macOutputPath>
			<macPath type="NSString"></macPath>
			<minimumActiveSessionsCount type="NSNumber">0</minimumActiveSessionsCount>
			<name type="NSString">DemoApp</name>
			<notificationEmailEnabled type="NSString">NO</notificationEmailEnabled>
			<phasedStartup type="NSString">YES</phasedStartup>
			<projectSearchPath type="NSString">()</projectSearchPath>
			<sessionTimeOut type="NSNumber">3600</sessionTimeOut>
			<startingPort type="NSNumber">2001</startingPort>
			<statisticsPassword type="NSString"></statisticsPassword>
			<timeForStartup type="NSNumber">30</timeForStartup>
		</element>
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

