# Daily Lighting Timeline

A single-app [Hubitat](https://hubitat.com/) approach to daily lighting routines. Instead of juggling many separate rules, this app keeps one daily timeline of **timeslots** — fixed clock times or **sunrise/sunset offsets** — and a visual editor that shows what every light does over the course of the day.

## Features

- **Schedules by day of week** – keep several timelines (say *Weekdays* and *Weekends*) and pick the days each one runs, either on the app's settings page or in the timeline page. Switch between them with the tabs above the timeline; new schedules can start as a copy of an existing one. A day runs from 4 AM to 4 AM, so a 12:30 AM timeslot after Friday evening belongs to Friday's schedule, and the timeline's columns run in that order. The page and the app page warn about days with no schedule, or with more than one (the first listed wins).
- **Visual timeline page** – every selected light as a row, grouped by its Hubitat room, with timeslots as columns. Each cell is colored by its target level (brown for dim through orange and yellow to warm white for bright), and the name column shows each light's live level.
- **Drag to retime** – drag a timeslot's header left or right (5-minute steps), or focus it and use the arrow keys. Sunrise/sunset timeslots keep following the sun; dragging changes their offset.
- **Per-light rules** – in each timeslot, every light has two independent settings:
  - Power: **Turn on**, **Turn off**, or **Leave as is**
  - Level: **Set to**, **Raise to at least**, **Dim to at most**, or **No level change**
- **On/off switches** – plain switches and plugs can be added alongside the dimmers. They appear in their rooms with an on/off chip and take **Turn on** / **Turn off** rules only; copying a level-only rule skips them, and catch-up after a restart leaves them alone.
- **Pre-staging** – level rules always update the light's next "on" level when the device supports it (`setDefaultDimmerLevel`, `setDefaultLevel`, or `presetLevel`; plus P14 on Inovelli Red Series 800). Lights that can't pre-stage get a pending level that is applied the next time they turn on, so an off light is never switched on just to change its level.
- **Editing tools** – click a cell to edit one light, see that light's whole day, and copy its rule to other lights; click a header to edit the whole timeslot; add a timeslot for one light with the row's **+**; create a timeslot **empty** or **from current light levels**; duplicate, delete, merge same-time timeslots, undo, and **Test now**.
- **Catch-up after restarts** – after a hub reboot, level rules (never on/off) from any timeslots missed while it was down are replayed in order.
- **Resilient execution** – a single misbehaving device won't stop the rest of a timeslot.

## Installation

1. In the Hubitat admin UI, go to **Apps Code → New App**, paste [`daily-lighting-timeline.groovy`](daily-lighting-timeline.groovy), and click **Save**.
2. On the same page, click **OAuth → Enable OAuth in App → Update**. The timeline page is served through the app's local API endpoint, which needs an access token.
3. Go to **Settings → File Manager** and upload [`daily-lighting-timeline.html`](daily-lighting-timeline.html) (keep the file name).
4. Go to **Apps → Add User App** and select **Daily Lighting Timeline**.
5. Choose the dimmers (and any on/off switches) the app may control, click **Done**, then reopen the app and click **Open the timeline**. Bookmark that page; its link includes the access token.

### Upgrading from v1

Updating the code in place keeps your schedule. A v2 timeline from before schedules existed becomes a single **Every day** schedule; add more from the app page or the timeline page. On first use, v2 converts the v1 time slots into the new timeline: slots at the same time are merged, "Only lights brighter than the setpoint" becomes **Dim to at most**, "Only lights dimmer than the setpoint" becomes **Raise to at least**, and the rest become **Set to**. The v1 settings are left untouched, so pasting the old code back restores the old behavior. After updating, open the app and click **Done** once so the new schedules and subscriptions are created.

## How a rule runs

When a timeslot fires, for each light:

1. **Level** – the target is the set value, or the higher (*raise to at least*) or lower (*dim to at most*) of the light's level and the rule's value. The pre-staged level is updated the same way.
2. **Power** – *Turn on* switches the light on at the target level; *Turn off* switches it off; *Leave as is* only changes the level if the light is already on.

The app schedules each timeslot as a one-time job, taken from the schedule in charge of the day it belongs to; a nightly task at 11:58 PM schedules the next day using that day's sun times. Saving in the editor reschedules immediately.

## API

All endpoints are local (`http://<hub>/apps/api/<app id>/…`) and need `access_token`:

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/ui` | The timeline page (from File Manager) |
| GET | `/timeline` | Schedules and their timeslots, lights with rooms and live state, today's sun times and schedule |
| POST | `/timeline` | Save `{baseVersion, timeline: {schedules: [...]}}`; returns 409 if it changed elsewhere |
| GET | `/state` | Live light state only |
| POST | `/test/:slotId` | Run a timeslot now |

## Compatibility

- Requires a Hubitat hub (platform 2.3.4 or later for File Manager access) with location and sunrise/sunset data configured.
- Dimmers: any device exposing the `SwitchLevel` capability. On/off switches: any device exposing the `Switch` capability.

## License

Released under the [MIT License](LICENSE).
