# System map

One diagram of the running system. Each box group is a trust boundary.

```mermaid
flowchart TB
  subgraph devices["Trust boundary: user device"]
    ios[iPhone app]
    watch[Watch app]
    browser[Dashboard in the browser]
  end
  subgraph edge["Trust boundary: public edge"]
    app[app.freedriver.io]
    auth[auth.freedriver.io]
    mqttname[mqtt.freedriver.io]
  end
  subgraph compose["Trust boundary: compose network"]
    dash[Dashboard]
    kc[Keycloak]
    broker[MQTT broker]
    automcp[Autonomy MCP]
    disp[Dispatcher]
  end
  subgraph home["Trust boundary: home"]
    autonomy[Autonomy]
  end
  subgraph operator["Trust boundary: operator host"]
    bridge[Agent Bridge]
  end
  browser --> app
  ios --> app
  watch --> app
  app --> dash
  browser --> auth
  ios --> auth
  watch --> auth
  auth --> kc
  dash --> kc
  dash --> broker
  automcp --> dash
  autonomy --> mqttname
  mqttname --> broker
  bridge --> app
  app --> disp
  bridge --> auth
```

The dashboard, the iPhone and the Watch sign in at Keycloak on `auth.freedriver.io` and call the dashboard on `app.freedriver.io`. Autonomy at home reaches the MQTT broker through `mqtt.freedriver.io`. The Agent Bridge runs on the operator host, outside the compose network. It calls the dispatcher through `app.freedriver.io` and signs in at `auth.freedriver.io`. The dispatcher does not call the operator host. The dispatcher host API is the path Caddy routes for that host.
