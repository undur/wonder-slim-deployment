package sjip.core.model;

/**
 * Wire-shape representation of an {@link MInstance}: a plain data record whose
 * component names map 1:1 to the {@code NSDictionary} keys carried on the deployment
 * wire protocol and persisted in {@code SiteConfig.xml}.
 *
 * <p>All components are nullable. A given envelope may carry only a small subset
 * (e.g. an {@code addInstance} request typically just sets {@code applicationName},
 * {@code id}, {@code hostName}, {@code port}); the rest are filled in via setters
 * or from application defaults after construction. {@code FoundationCoder} omits
 * null components from the emitted dict, matching the contract the M-classes
 * followed pre-DTO.
 *
 * <p>{@code oldport} is the bookkeeping field {@link MInstance#setPort} records
 * before a port change, used by wotaskd's configure path to find the existing
 * instance under its previous port. Persisted in the dict, marked deprecated
 * because callers shouldn't read it as application state.
 */
public record MInstanceDto(
		String hostName,
		Integer id,
		Integer port,
		String applicationName,
		Boolean autoRecover,
		Integer minimumActiveSessionsCount,
		String path,
		Boolean cachingEnabled,
		Boolean debuggingEnabled,
		String outputPath,
		Boolean autoOpenInBrowser,
		Integer lifebeatInterval,
		String additionalArgs,
		Boolean schedulingEnabled,
		String schedulingType,
		Integer schedulingHourlyStartTime,
		Integer schedulingDailyStartTime,
		Integer schedulingWeeklyStartTime,
		Integer schedulingStartDay,
		Integer schedulingInterval,
		Boolean gracefulScheduling,
		Integer sendTimeout,
		Integer recvTimeout,
		Integer cnctTimeout,
		Integer sendBufSize,
		Integer recvBufSize,
		Integer oldport ) {}
