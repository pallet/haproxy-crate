[Repository](https://github.com/pallet/haproxy-crate) &#xb7;
[Issues](https://github.com/pallet/haproxy-crate/issues) &#xb7;
[API docs](http://palletops.com/haproxy-crate/0.8/api) &#xb7;
[Annotated source](http://palletops.com/haproxy-crate/0.8/annotated/uberdoc.html) &#xb7;
[Release Notes](https://github.com/pallet/haproxy-crate/blob/develop/ReleaseNotes.md)

A [pallet](http://palletops.com/) crate to install and configure
 [haproxy](http://haproxy.1wt.eu/).

### Dependency Information

```clj
:dependencies [[com.palletops/haproxy-crate "0.8.0-alpha.3"]]
```

### Releases

<table>
<thead>
  <tr><th>Pallet</th><th>Crate Version</th><th>Repo</th><th>GroupId</th></tr>
</thead>
<tbody>
  <tr>
    <th>0.8.0-RC.4</th>
    <td>0.8.0-alpha.3</td>
    <td>clojars</td>
    <td>com.palletops</td>
    <td><a href='https://github.com/pallet/haproxy-crate/blob/0.8.0-alpha.3/ReleaseNotes.md'>Release Notes</a></td>
    <td><a href='https://github.com/pallet/haproxy-crate/blob/0.8.0-alpha.3/'>Source</a></td>
  </tr>
</tbody>
</table>

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

The `:frontend` and `:backend` keys can also be used to define an
application, e.g.

```clj
{:proxy-group :myproxy
 :config {:frontend {:myapp {:server-address "0.0.0.0:80"}}
          :backend {:myapp {}}}}
```

The `proxied-by` function should be called in the settings phase of
services that want to be proxied behind haproxy.  The `:proxy-group`
setting, which defaults to the group-name for the current target,
should match the `proxy-group-name` argument passed to `proxied-by`.

For example, this would add the current target as a server for the
`:my-app` application, contactable on the 8080 port.

```clj
(haproxy/proxied-by :proxy-group :myapp {:server-port 8080})
```

When the `:listen` key is used to set up the application, this will
result in server entries under in the listen configuration section.
When a `:backend` key is used to set up the application, this will
result in server entries under the backend configuration section.

The `install` function is responsible for actually installing haproxy.

## Live test on vmfest

For example, to run the live test on VMFest, using Ubuntu 13:

```sh
lein with-profile +vmfest pallet up --selectors ubuntu-13
lein with-profile +vmfest pallet down --selectors ubuntu-13
```

## License

Copyright (C) 2012, 2013 Hugo Duncan

Distributed under the Eclipse Public License.
