package com.amhs.integration;
import java.util.Set;
/**
 * The adapters this build can actually speak to a machine with, named by the key a protocol profile
 * carries.
 *
 * <p>A protocol profile says what a machine speaks; this says what we have written. The two are
 * separate on purpose: a site may record the protocol of a controller long before an adapter for it
 * exists, and recording it must not make the system claim it can drive it. Configuration may only
 * put a vehicle or a piece of equipment in use on a key named here.
 */
public final class DeviceAdapters {
 private DeviceAdapters(){ }
 /**
  * One JSON object per line over TCP, this system's own contract, with a controller or gateway in
  * front of the line translating to whatever the vehicles themselves speak.
  * See {@code docs/vehicle-tcp-contract.md}.
  */
 public static final String JSON_LINE_V1="JSON_LINE_V1";
 /** One frame of bytes per command over TCP, laid out by a mapping document. */
 public static final String PC_FRAME_V1="PC_FRAME_V1";
 /** A block of PLC words, laid out by a mapping document, over Modbus TCP or MELSEC. */
 public static final String PLC_WORD_V1="PLC_WORD_V1";
 /**
  * The same block of words, but a piece of equipment rather than a vehicle.
  *
  * <p>A separate key, because what a document may say about it is different. A stocker has no node
  * and no battery; it has whether it is able, whether it will take a call, and what it is doing.
  * Sharing the key would mean sharing the vocabulary, and a document that could report a stocker's
  * battery is a document somebody will try to write.
  */
 public static final String EQUIPMENT_WORD_V1="EQUIPMENT_WORD_V1";
 /**
  * Adapters a vehicle may be put in use on without a mapping document. The rest -- a frame, a
  * register block, a broker -- are named by the document that describes them, and configuration
  * accepts those keys when a document of that name has been read.
  */
 public static final Set<String> KNOWN=Set.of(JSON_LINE_V1);
}
