/**
 * How a device's own dialect is written down, and read.
 *
 * <p>Named rather than internal because it is no longer one module's private business. A mapping
 * document describes a vehicle behind a PLC and a stocker behind one in the same words, with the
 * same layout, the same wires and the same rules -- so the equipment module reads documents too,
 * and the thing both of them read has to be something either may depend on.
 *
 * <p>What stays internal is the links themselves ({@code integration.infrastructure}): how a socket
 * is held open and when it is retried is nobody else's concern.
 */
@org.springframework.modulith.NamedInterface("mapping")
package com.amhs.integration.mapping;
