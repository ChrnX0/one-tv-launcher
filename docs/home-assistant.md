# Home Assistant: your TV as a smart-home panel

One TV Launcher talks **directly** to your [Home Assistant](https://www.home-assistant.io) server on your home network,
through its [REST API](https://developers.home-assistant.io/docs/api/rest/). No cloud account and no extra add-on.

## What you get

- **Control from the couch.** *Extras → Home* on the TV lists your lights, plugs, fans, covers, scenes, scripts and
  `input_boolean` helpers. Press OK to toggle one.
- **Control from your phone.** In the web panel's *Extras* tab, the *Home* card shows the same devices.
- **Control by voice through Claude.** With the MCP server on, an assistant can list devices and turn them on or off
  with the `casa_listar` / `casa_acionar` tools.
- **The TV becomes a sensor.** The launcher publishes `sensor.tv_<name>` with `ligada` (on) or `dormindo` (asleep) and
  the app in front. If *Now playing* is on, it also publishes the media state and title. You can build automations
  from it, like *"movie paused → dim the lights"*.
- **Doorbell on screen.** Pick a doorbell sensor and a camera. When the sensor turns `on`, the camera snapshot shows up
  on top of whatever is playing.

To keep this safe, the list of actions is fixed: only **on / off / toggle**, and only on the device types above. The
TV cannot call any other Home Assistant service.

## Setup (2 minutes)

1. In Home Assistant, open your **profile → Security → Long-lived access tokens → Create token**.
2. On your phone or PC, open the launcher's web panel (`http://<tv-ip>:8080`) → **Extras** → **Home**.
3. Enter your server address, e.g. `http://homeassistant.local:8123`, paste the token and save.
4. On the TV: **Extras → Home**. Your devices appear.

The token is encrypted with the Android Keystore and never shows up in the panel log. *Forget* in the same card erases
it. TLS verification is always on: a server with a self-signed HTTPS certificate won't connect. Use plain `http://`
on your LAN, or a certificate the TV trusts.

## Got Google Home, Tuya / Smart Life or other ecosystems?

Bring them into Home Assistant with its official integrations. One TV Launcher then sees them like any other device.
