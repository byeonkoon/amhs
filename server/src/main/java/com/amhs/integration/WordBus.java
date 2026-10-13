package com.amhs.integration;
import java.io.IOException;

/**
 * A block of words in a controller, read and written whole.
 *
 * <p>This is the whole of what the PLC link needs, and it is deliberately small. A controller that
 * holds a vehicle's command in registers is the same arrangement whoever made it: values go in, a
 * block comes back, and the difference between two reads is the news. What changes between makers
 * is the wire and how an address is written on it -- so that, and only that, is what a driver is.
 *
 * <p>An address stays a string all the way down here. {@code 40100} and {@code D100} name the same
 * kind of thing in two conventions, and which convention it is in is the driver's to know; parsing
 * it higher up would make the caller know too.
 */
public interface WordBus extends AutoCloseable {
 /** Opens the link if it is not open. Doing nothing when it already is, is the point. */
 void open() throws IOException;
 /** The block at that address, as unsigned 16-bit words in the order the controller holds them. */
 int[] read(String area,int words) throws IOException;
 /** Puts those words at that address. */
 void write(String area,int[] values) throws IOException;
 @Override void close();

 /**
  * What the controller itself refused, as it said it.
  *
  * <p>Not a link problem, and the difference matters to what a command may claim: the controller
  * answered, and the answer was no. Nothing was taken, so the command may be sent again.
  */
 final class ControllerRefused extends IOException {
  private final int code;
  ControllerRefused(String wire,int code){ super(wire+" refused with "+code); this.code=code; }
  public int code(){ return code; }
 }
}
