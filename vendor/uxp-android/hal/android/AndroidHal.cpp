/* -*- Mode: C++; tab-width: 2; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "Hal.h"
#include "HalImpl.h"
#include "WindowIdentifier.h"
#include "AndroidBridge.h"
#include "mozilla/dom/network/Constants.h"
#include "mozilla/dom/ScreenOrientation.h"
#include "nsIScreenManager.h"
#include "nsServiceManagerUtils.h"

using namespace mozilla::dom;
using namespace mozilla::hal;

namespace java = mozilla::java;

namespace mozilla {
namespace hal_impl {

void
EnableNetworkNotifications()
{
  java::GeckoAppShell::EnableNetworkNotifications();
}

void
DisableNetworkNotifications()
{
  java::GeckoAppShell::DisableNetworkNotifications();
}

void
GetCurrentNetworkInformation(hal::NetworkInformation* aNetworkInfo)
{
  AndroidBridge::Bridge()->GetCurrentNetworkInformation(aNetworkInfo);
}

void
EnableScreenConfigurationNotifications()
{
  java::GeckoAppShell::EnableScreenOrientationNotifications();
}

void
DisableScreenConfigurationNotifications()
{
  java::GeckoAppShell::DisableScreenOrientationNotifications();
}

void
GetCurrentScreenConfiguration(ScreenConfiguration* aScreenConfiguration)
{
  AndroidBridge* bridge = AndroidBridge::Bridge();
  if (!bridge) {
    return;
  }

  nsresult rv;
  nsCOMPtr<nsIScreenManager> screenMgr =
    do_GetService("@mozilla.org/gfx/screenmanager;1", &rv);
  if (NS_FAILED(rv)) {
    NS_ERROR("Can't find nsIScreenManager!");
    return;
  }

  nsIntRect rect;
  int32_t colorDepth, pixelDepth;
  int16_t angle;
  ScreenOrientationInternal orientation;
  nsCOMPtr<nsIScreen> screen;

  screenMgr->GetPrimaryScreen(getter_AddRefs(screen));
  screen->GetRect(&rect.x, &rect.y, &rect.width, &rect.height);
  screen->GetColorDepth(&colorDepth);
  screen->GetPixelDepth(&pixelDepth);
  orientation = static_cast<ScreenOrientationInternal>(bridge->GetScreenOrientation());
  angle = bridge->GetScreenAngle();

  *aScreenConfiguration =
    hal::ScreenConfiguration(rect, orientation, angle, colorDepth, pixelDepth);
}

bool
LockScreenOrientation(const ScreenOrientationInternal& aOrientation)
{
  // Force the default orientation to be portrait-primary.
  ScreenOrientationInternal orientation =
    aOrientation == eScreenOrientation_Default ? eScreenOrientation_PortraitPrimary
                                               : aOrientation;

  switch (orientation) {
    // The Android backend only supports these orientations.
    case eScreenOrientation_PortraitPrimary:
    case eScreenOrientation_PortraitSecondary:
    case eScreenOrientation_PortraitPrimary | eScreenOrientation_PortraitSecondary:
    case eScreenOrientation_LandscapePrimary:
    case eScreenOrientation_LandscapeSecondary:
    case eScreenOrientation_LandscapePrimary | eScreenOrientation_LandscapeSecondary:
    case eScreenOrientation_Default:
      java::GeckoAppShell::LockScreenOrientation(orientation);
      return true;
    default:
      return false;
  }
}

void
UnlockScreenOrientation()
{
  java::GeckoAppShell::UnlockScreenOrientation();
}

} // hal_impl
} // mozilla

