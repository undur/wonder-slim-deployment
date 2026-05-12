# clear_command_dispatchIsRecognised

## Send clear

> `clear` is a dead branch on the wotaskd side — no client in our codebase sends it today, but the dispatch still recognises it (see FIXME at DirectAction.java:145). Snapshotted for completeness so a future deletion is a deliberate decision.

### Action — POST monitorRequest{updateWotaskd: {clear: "y"}} to wotaskd

### Wire send — test → wotaskd (POST /wa/monitorRequest)

```xml
<monitorRequest type="NSDictionary">
	<updateWotaskd type="NSDictionary">
		<clear type="NSString">y</clear>
	</updateWotaskd>
</monitorRequest>
```

### Wire receive — wotaskd → test (HTTP 200)

```xml
<monitorResponse type="NSDictionary">
	<updateWotaskdResponse type="NSDictionary">
		<clear type="NSDictionary">
			<success type="NSString">YES</success>
		</clear>
	</updateWotaskdResponse>
</monitorResponse>
```

