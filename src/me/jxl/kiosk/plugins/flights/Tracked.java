// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights;

import java.util.List;
import me.jxl.kiosk.plugins.flights.enrich.Info;
import me.jxl.kiosk.plugins.flights.source.Aircraft;

/** An aircraft that passed the filter: its position on the home plane plus its trail. */
public final class Tracked {
  public final Aircraft aircraft;
  public final double x, y, distM;

  /** Trail points, oldest first. */
  public final List<TrailStore.Point> trail;

  /** Looked-up registration, type, airline and route; {@link Info#EMPTY} until known. */
  public final Info info;

  Tracked(Aircraft aircraft, double x, double y, List<TrailStore.Point> trail) {
    this(aircraft, x, y, trail, Info.EMPTY);
  }

  private Tracked(Aircraft aircraft, double x, double y, List<TrailStore.Point> trail, Info info) {
    this.info = info;
    this.aircraft = aircraft;
    this.x = x;
    this.y = y;
    this.distM = Math.hypot(x, y);
    this.trail = trail;
  }

  /** The same place in the list and trail with a re-aged observation (see Selector). */
  Tracked withAircraft(Aircraft a) {
    return new Tracked(a, x, y, trail, info);
  }

  Tracked withInfo(Info info) {
    return new Tracked(aircraft, x, y, trail, info);
  }
}
