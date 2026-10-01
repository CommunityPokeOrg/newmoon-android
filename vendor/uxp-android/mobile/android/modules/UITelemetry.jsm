/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

this.EXPORTED_SYMBOLS = ["UITelemetry"];

const { classes: Cc, interfaces: Ci, utils: Cu } = Components;

Cu.import("resource://gre/modules/XPCOMUtils.jsm");

/**
 * Records UI telemetry events and sessions.
 *
 * This object doubles as the browser app's nsIUITelemetryObserver
 * implementation (see nsIAndroidBridge.idl): the Java frontend's
 * Telemetry helper reaches it through
 * nsIAndroidBrowserApp.getUITelemetryObserver().
 */
var UITelemetry = {
  QueryInterface: XPCOMUtils.generateQI([Ci.nsIUITelemetryObserver]),

  _events: [],
  _sessions: {},
  _sessionStarts: {},

  addEvent: function(aAction, aMethod, aTimestamp, aExtras) {
    if (!aTimestamp) {
      aTimestamp = Date.now();
    }
    this._events.push({
      action: aAction,
      method: aMethod || "none",
      timestamp: aTimestamp,
      extras: aExtras || null,
    });
  },

  startSession: function(aName, aTimestamp) {
    if (!aTimestamp) {
      aTimestamp = Date.now();
    }
    this._sessionStarts[aName] = aTimestamp;
  },

  stopSession: function(aName, aReason, aTimestamp) {
    if (!aTimestamp) {
      aTimestamp = Date.now();
    }
    let start = (aName in this._sessionStarts) ? this._sessionStarts[aName]
                                               : null;
    delete this._sessionStarts[aName];
    this._sessions[aName] = {
      reason: aReason || "",
      start: start,
      end: aTimestamp,
    };
  },
};
