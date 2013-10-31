## Usage

The haproxy crate provides a `server-spec` function that returns a
server-spec. This server spec will install and run the haproxy server.
You pass a map of options to configure haproxy, as specified in
`settings`.

The `server-spec` provides an easy way of using the crate functions, and you can
use the following crate functions directly if you need to.

The `settings` function provides a plan function that should be called
in the `:settings` phase.  The function puts the configuration options
into the pallet session, where they can be found by the other crate
functions, or by other crates wanting to interact with haproxy.

The `:proxy-group` keyword can be used to provide a logical name the
haproxy instance, and defaults to pallet group name.

The haproxy configuration file is based on the `:config` key in the
settings map, and has four sub-keys, The `:listen` sub-key is used to
specific a map of applications to proxy, where the key is a keyword
naming the application, and the values are a map of configuration
options for the listen clause for that application.

For example, this would configure a `:my-app` application, listening
on port 80.

```clj
{:proxy-group :myproxy
 :config {:listen {:myapp {:server-address "0.0.0.0:80"}}}}
```

The `proxied-by` function should be called in the settings phase of
services that want to be proxied behind haproxy.  The `:proxy-group`
setting, which defaults to the group-name for the current target,
should match the `proxy-group-name` argument passed to `proxied-by`.

For example, this would add the current target as a backend for the
`:my-app` application, contactable on the 8080 port.

```clj
(haproxy/proxied-by :proxy-group :myapp {:server-port 8080})
```

The `install` function is responsible for actually installing haproxy.

## Live test on vmfest

For example, to run the live test on VMFest, using Ubuntu 13:

```sh
lein with-profile +vmfest pallet up --selectors ubuntu-13
lein with-profile +vmfest pallet down --selectors ubuntu-13
```
