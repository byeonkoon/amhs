package com.amhs.integration;
/** Configuration contract only. These enums do not mean an adapter is implemented. */
public record CommunicationProfile(CommType commType, ProtoType protoType, String protoVersion,
    String manufacturer, long protocolProfileId, long connectionProfileId, String serialNumber, String interfaceName) {
    public enum CommType { TCP, MQTT }
    public enum ProtoType { PC, PLC, VDA5050 }
    public CommunicationProfile {
        if (commType == null || protoType == null || blank(protoVersion) || blank(manufacturer)
            || protocolProfileId <= 0 || connectionProfileId <= 0) throw new IllegalArgumentException("INVALID_PROFILE");
        if ((commType == CommType.MQTT) != (protoType == ProtoType.VDA5050))
            throw new IllegalArgumentException("UNSUPPORTED_PROTOCOL_COMBINATION");
        if (protoType == ProtoType.VDA5050 && (blank(serialNumber) || blank(interfaceName)))
            throw new IllegalArgumentException("VDA_IDENTITY_REQUIRED");
    }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
}
