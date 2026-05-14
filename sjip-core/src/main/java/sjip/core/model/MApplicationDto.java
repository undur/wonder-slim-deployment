package sjip.core.model;

/**
 * Wire-shape representation of an {@link MApplication}: a plain data record whose
 * component names map 1:1 to the {@code NSDictionary} keys carried on the deployment
 * wire protocol and persisted in {@code SiteConfig.xml}.
 *
 * <p>All components are nullable. A given envelope may carry only a small subset
 * (e.g. an {@code addApplication} request typically just sets {@code name}); the
 * rest are populated from defaults via {@link MApplication#takeValuesFromDefaults}
 * or set later through the UI. {@code FoundationCoder} omits null components from
 * the emitted dict, matching the contract the M-classes followed pre-DTO.
 *
 * <p>{@code oldname} is the bookkeeping field {@link MApplication#setName} records
 * before a rename, used by wotaskd's configure path to find the existing application
 * under its previous name. Persisted in the dict.
 */
public record MApplicationDto(
		String name,
		Integer startingPort,
		Integer timeForStartup,
		Boolean phasedStartup,
		Boolean autoRecover,
		Integer minimumActiveSessionsCount,
		String unixPath,
		String winPath,
		String macPath,
		Boolean cachingEnabled,
		String adaptor,
		Integer adaptorThreads,
		Integer listenQueueSize,
		Integer adaptorThreadsMin,
		Integer adaptorThreadsMax,
		String projectSearchPath,
		Integer sessionTimeOut,
		String statisticsPassword,
		Boolean debuggingEnabled,
		String unixOutputPath,
		String winOutputPath,
		String macOutputPath,
		Boolean autoOpenInBrowser,
		Integer lifebeatInterval,
		String additionalArgs,
		Boolean notificationEmailEnabled,
		String notificationEmailAddr,
		Integer retries,
		String scheduler,
		Integer dormant,
		String redir,
		Integer sendTimeout,
		Integer recvTimeout,
		Integer cnctTimeout,
		Integer sendBufSize,
		Integer recvBufSize,
		Integer poolsize,
		Integer urlVersion,
		String oldname ) {}
