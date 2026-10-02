# Changelog

We're currently going through changes fast so changes are merely listed by date. This will change once I get around to do proper versioning.

## 2024-10-26

* Upgraded Monitor's look to something a little more modern. The new look is based on the [tabler](https://www.tabler.io/) template, which itself is based on bootstrap 5.3.
* Converted all components to inline bindings.
* Made Monitor logins persistent (well, at least for the lifetime of the session).
* Moved sessionID storage from URL to cookie (to enable the persistent logins).
* Made those darn links into the actual monitored applications a little more explicit about where they point. I've been pressing those things accidentally for 25 years. Links on application names and instance IDs now point to their corresponding configuration pages. As They Should.

## 2024-11-08

* Replaced `WOHTTPConnection` with java's built in HTTP client in `JavaMonitor -> wotaskd` and `wotaskd -> Application` comms.
* Cleaned out usage of foundation collections (`NSArray`/`NSDictionary`/`NSSet`) where possible, making the whole thing a little more "generic" in the java sense. Foundation collections can't be removed everywhere until we've replaced `_JavaMonitorCoder`/`_JavaMonitorCoder` and `NSPropertyListSerialization` which depend on them, so they're still present in some cases.
* Added an instance detail page, allowing us to show in one location a little more info/stats about each instance. A little weak at the moment but will get populated fast (and will probably take over things like statistics display, death listing etc.)
* Allow the instance detail to fetch/dispaly a `jstack`-style thread dump from an instance. Usage of this fucntionality depends on the instance running an [ERXMonitorServer](https://github.com/undur/wonder-slim/blob/master/ERExtensions/src/main/java/er/extensions/ERXMonitorServer.java) which is currently only present in `wonder-slim`.
* Added a log viewer for an instance's main log file. Currently, use of this depends on Monitor running on the same machine and having access to the log file specified by `-WOOutputPath`. Eventually, we should probably make `wotaskd` read the file and proxy the data to monitor.
* Refactored/fixed a couple of locations where KVC conflicts with java's hardened access restrictions. The primary example being the construction of an anonymous inner class when asking for an action's confirmation, which caused problems for every location where a `ConfirmationPage` was used.
* Lots and lots of cleanup in code (making privates private, making constants constant, using modern java language constructs, fixing up design, changing code to adopt java coding/syntax conventions etc. etc.).