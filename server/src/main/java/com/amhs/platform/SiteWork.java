package com.amhs.platform;
/**
 * What another module knows about work in progress at a site, asked before the map under it is
 * changed.
 *
 * <p>A map revision is the ground every order, every granted road and every vehicle position is
 * written against. Swapping it while any of that is open would leave records pointing at nodes that
 * are no longer there -- not a wrong number but a site nobody can account for. Topology cannot see
 * that work, and should not reach into the modules that own it, so it asks them.
 *
 * <p>Each implementation answers for its own kind of work: null when nothing of its kind is open,
 * otherwise a code naming what is.
 *
 * <p>It lives here rather than with the map because both sides of the question -- the module that
 * changes the map and the modules that have work open on it -- already depend on this one, and
 * neither should have to depend on the other to be asked.
 */
public interface SiteWork {
 /** Why this site's map must not change now, or null when nothing of this kind is in the way. */
 String whyBusy(long siteId);
}
