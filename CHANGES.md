# Changelog

## Unreleased

- **Deploy a new build through JavaMonitor: `admin/deploy`**
  POST a `.tar.gz` containing `<App>.woa`, as `application/octet-stream`, to
  `…/JavaMonitor.woa/admin/deploy?type=app&name=<App>`. JavaMonitor hands it to the wotaskd on every host
  the application has instances on, which swaps the new bundle into place and restarts the instances that
  were running. The response reports each host's outcome. The archive is streamed to disk as it arrives,
  never held in memory. vermilingua's `deploy` goal deploys this way.

- **Deploys keep the newest 5 previous builds**
  The bundle a deploy replaces is kept as `x<App>_<timestamp>.woa` beside the new one, and older ones are
  deleted beyond `WOTaskd.deploy.retainedBuilds` (5 by default, a negative value keeps all). Only
  directories named exactly that way are ever deleted, symlinks are never followed, and a build that won't
  delete is reported without failing the deploy.

- **Instances can be launched detached from wotaskd**
  With `WOTaskd.detachLaunch=true`, instances start outside wotaskd's process group, so stopping or
  restarting wotaskd leaves them running. Off by default; Unix only.

- **wotaskd runs on wo-adaptor-jetty**
  WO lifebeats arrive without a Host header, which Jetty rejects, so wotaskd recognizes them before HTTP
  parsing and answers them exactly as the classic adaptor does. Everything else is ordinary HTTP, including
  lifebeats from ng-objects applications, which carry a Host header and are now answered without the
  minutes-long wait the classic adaptor's reply caused them.

- **Lifebeats from an address wotaskd doesn't accept are logged**
  With `WOHost` set, wotaskd hears lifebeats only from that exact address. Beats from anywhere else used to
  be ignored silently; now the first from each address logs a warning.

- **Application types**
  The applications list shows what each application is built on: ng-objects (green), wonder-slim (blue),
  Project Wonder (orange) or Other (gray). An application serving more than one framework shows a badge
  for each. wotaskd finds out by asking an instance when it registers, and asks again whenever an instance
  starts, so a redeployed application's badges follow its new build. (#56)

- **A live log page**
  JavaMonitor's "Live log" page shows its log as it's written, streamed over server-sent events.

- **Breadcrumbs, and a redesign around tabler's page conventions**
  Every page has a breadcrumb trail in a fixed place at the top, saying where you are and linking back up
  the hierarchy. Pages follow tabler's layouts throughout: consistent cards, form controls and buttons, and
  status shown as badges. The application page keeps its bulk actions in the table, under the columns they
  act on. The logo has a transparent background.

- **Applications are no longer passed `-WOAdaptor WODefaultAdaptor`**
  `WODefaultAdaptor` means the application's default adaptor, so it's no longer passed to instances, where
  it overrode an adaptor the application selects for itself (as wo-adaptor-jetty does when present). Set
  `WOClassicAdaptor` to force the classic adaptor. (#54)

- **The adaptor configuration lists each application once**
  Instances that send lifebeats without being configured were listed as a second element with the same
  application name, and an adaptor that kept the last element routed only to them. They're now merged into
  the application's element.

- **Only wotaskd writes the configuration**
  JavaMonitor no longer creates the configuration directory or an empty `SiteConfig.xml`. Pointed at a
  directory that doesn't exist, it logs a warning and starts with an empty configuration.
  `WODeploymentConfigurationDirectory` must be set.

- **E-mail notifications are sent**
  When an application has notifications enabled, its addresses get an e-mail when one of its instances
  stops running, through the site's SMTP host. CC and BCC addresses are sent as CC and BCC recipients.

- **Startup and requests no longer wait on reverse DNS**
  Working out the machine's own addresses no longer looks up each one's hostname, which could stall
  startup, or a request, for tens of seconds per address on a machine with missing reverse DNS records.

- **wotaskd and JavaMonitor log their effective configuration at startup**
  Every deployment property, its value, and whether it was set or defaulted. Passwords are redacted.

- **JavaMonitor's front page answers at more URLs**
  Under the `/Apps/WebObjects` prefix as well as `/cgi-bin/WebObjects`, and without the `.woa` extension,
  which redirects to the URL with it. The mod_proxy page is always in the menu.

- **Removed: JMX support in wotaskd, and the `findPort` action**
  Neither had any users.

- **Rebuilt on plain Java**
  The model, the wire protocol between JavaMonitor and wotaskd, and the configuration files are handled
  with plain Java types instead of Foundation's (`NSArray`, `NSDictionary`, `NSTimestamp`,
  `NSPropertyListSerialization`, the old coders), and logging goes through slf4j. The wire format and
  `SiteConfig.xml` are unchanged. The code lives in `sjip` packages and artifacts.

- **The protocols are documented and tested**
  `PROTOCOLS.md` documents how JavaMonitor, wotaskd and instances talk to each other. The
  `sjip-system-tests` module runs a real wotaskd and JavaMonitor and checks the wire shapes of every
  request, including lifebeats sent the way WO instances send them.

- **`scripts/deploy.sh` deploys wotaskd and JavaMonitor to a server**
  It builds both, moves the running bundles aside on the server and installs the new ones under
  `/opt/webobjects/apps`, built for the newest JDK installed there, then restarts the services.

- **Requires JDK 25.**

- **Dependencies**: wonder-slim 8.0.16 (with AjaxSlim in place of Ajax), wo-adaptor-jetty 0.12.2,
  vermilingua 1.1.11, tabler 1.3.0 (was 1.0.0), simple-java-mail 9.3.5, slf4j 2.0.20, JUnit 6.1.3.

## 2024-11-08

- Replaced `WOHTTPConnection` with java's built in HTTP client in `JavaMonitor -> wotaskd` and `wotaskd -> Application` comms.
- Cleaned out usage of foundation collections (`NSArray`/`NSDictionary`/`NSSet`) where possible, making the whole thing a little more "generic" in the java sense. Foundation collections can't be removed everywhere until we've replaced `_JavaMonitorCoder`/`_JavaMonitorCoder` and `NSPropertyListSerialization` which depend on them, so they're still present in some cases.
- Added an instance detail page, allowing us to show in one location a little more info/stats about each instance. A little weak at the moment but will get populated fast (and will probably take over things like statistics display, death listing etc.)
- Allow the instance detail to fetch/dispaly a `jstack`-style thread dump from an instance. Usage of this fucntionality depends on the instance running an [ERXMonitorServer](https://github.com/undur/wonder-slim/blob/master/ERExtensions/src/main/java/er/extensions/ERXMonitorServer.java) which is currently only present in `wonder-slim`.
- Added a log viewer for an instance's main log file. Currently, use of this depends on Monitor running on the same machine and having access to the log file specified by `-WOOutputPath`. Eventually, we should probably make `wotaskd` read the file and proxy the data to monitor.
- Refactored/fixed a couple of locations where KVC conflicts with java's hardened access restrictions. The primary example being the construction of an anonymous inner class when asking for an action's confirmation, which caused problems for every location where a `ConfirmationPage` was used.
- Lots and lots of cleanup in code (making privates private, making constants constant, using modern java language constructs, fixing up design, changing code to adopt java coding/syntax conventions etc. etc.).

## 2024-10-26

- Upgraded Monitor's look to something a little more modern. The new look is based on the [tabler](https://www.tabler.io/) template, which itself is based on bootstrap 5.3.
- Converted all components to inline bindings.
- Made Monitor logins persistent (well, at least for the lifetime of the session).
- Moved sessionID storage from URL to cookie (to enable the persistent logins).
- Made those darn links into the actual monitored applications a little more explicit about where they point. I've been pressing those things accidentally for 25 years. Links on application names and instance IDs now point to their corresponding configuration pages. As They Should.
