/**
 * Live view of a site for screens: one WebSocket per screen, a snapshot first and then numbered
 * changes, so a screen that missed one notices and asks again instead of showing a stale state.
 * Reads only; nothing here controls a vehicle.
 */
package com.amhs.live;
