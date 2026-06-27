# Daily Lighting Timeline

A single-app [Hubitat](https://hubitat.com/) approach to daily lighting routines. Instead of juggling many separate rules, this app presents one chronologically sorted timeline of dynamic triggers — **sunrise**, **sunset**, and your own **custom times** — and lets you set dimmer levels at each point in the day.

## Features

- **Unified daily timeline** – sunrise, sunset, and custom slots are merged and sorted automatically based on today's local sun data.
- **Custom time slots** – add a specific clock time, or a sunrise/sunset offset (e.g. "30 minutes before sunset"), each with an optional label.
- **Flexible apply rules** – for each slot, target:
  - All selected lights
  - Only lights brighter than the setpoint
  - Only lights dimmer than the setpoint
  - Only lights currently on
  - Only lights currently off
- **Pre-staging support** – if a device supports a default/preset level command (`setDefaultDimmerLevel`, `setDefaultLevel`, or `presetLevel`), the app stages the next "on" level without disturbing a light that is currently off.
- **Filtering** – filter the timeline to just the slots that include a specific light.
- **Test button** – fire any fully-configured slot on demand to confirm behavior.
- **Resilient execution** – attribute values are parsed safely, and a single misbehaving device won't abort the rest of a slot.

## Installation

1. In the Hubitat admin UI, go to **Apps Code → New App**.
2. Paste the contents of [`daily-lighting-timeline.groovy`](daily-lighting-timeline.groovy) and click **Save**.
3. Go to **Apps → Add User App** and select **Daily Lighting Timeline**.
4. Open the app and configure your time slots.

## Usage

- Each time slot in the timeline can be expanded to select dimmable lights, a target level (1–100%), and an apply rule.
- Use **Add Custom Time Slot** at the bottom to add a specific time or a sunrise/sunset offset.
- The app schedules each configured slot via a one-time job; a nightly task at 11:58 PM pre-computes the next day's exact event times. Schedules are also rebuilt on hub reboot.

## Compatibility

- Requires a Hubitat hub with location/sunrise-sunset data configured.
- Works with any device exposing the `SwitchLevel` capability. Pre-staging is automatically used when the device supports it.

## License

Released under the [MIT License](LICENSE).
