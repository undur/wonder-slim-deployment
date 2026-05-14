package sjip.core.model;

/**
 * Wire-shape representation of the site-level scalars in an {@link MSiteConfig}.
 *
 * <p>The full SiteConfig persists as {@code {site: <these scalars>, hostArray:
 * [...], applicationArray: [...], instanceArray: [...]}}; this DTO carries
 * only the {@code site} sub-dict. It participates independently as the wire
 * payload for {@code updateWotaskd/configure/site} envelopes.
 *
 * <p>All components are nullable. {@code FoundationCoder} omits null components
 * from the emitted dict.
 */
public record MSiteConfigSiteDto(
		String password,
		String woAdaptor,
		String SMTPhost,
		String emailReturnAddr,
		Boolean viewRefreshEnabled,
		Integer viewRefreshRate,
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
		Integer urlVersion ) {}
