package com.amhs.integration;
/**
 * The ports held open for vehicles that dial in to this controller.
 *
 * <p>Which ports those are is configuration, and configuration changes while the server runs. This
 * is the one thing an operator cannot undo by editing a row -- a port stays open until something
 * closes it -- so it is brought back in line with the rows rather than remembered from startup.
 */
public interface VehicleListening {
 /** Opens the ports the configuration asks for and closes the ones it no longer does. */
 void listen();
}
