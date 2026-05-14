package sjip.core.model;

import java.util.List;

/**
 * Wire-shape representation of an {@link MSiteConfig}: the full archive
 * shape persisted in {@code SiteConfig.xml} and carried on the wire for
 * {@code queryWotaskd=SITE} responses and {@code updateWotaskd/sync} envelopes.
 *
 * <p>Composes the site-level scalars ({@link MSiteConfigSiteDto}) with the
 * three child-object collections.
 */
public record MSiteConfigDto(
		MSiteConfigSiteDto site,
		List<MHostDto> hostArray,
		List<MApplicationDto> applicationArray,
		List<MInstanceDto> instanceArray ) {}
