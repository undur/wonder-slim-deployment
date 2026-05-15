package sjip.core.model;

/**
 * Wire-shape representation of an {@link MHost}: a plain data record whose component
 * names map 1:1 to the dictionary keys carried on the deployment wire protocol and
 * persisted in {@code SiteConfig.xml}.
 *
 * <p>This DTO is the typed bridge between {@code FoundationCoder} (which knows how to
 * encode/decode records to the wire XML) and {@link MHost} (which knows the domain).
 *
 * <p>All components are nullable. The wire envelope for an "add host" carries just
 * {@code name} and {@code type}, but the same shape may participate in update/configure
 * envelopes that omit one or both. {@code FoundationCoder.encodeRecord} omits null
 * components from the emitted dict, matching the contract the M-classes followed
 * pre-DTO.
 */
public record MHostDto( String name, String type ) {}
